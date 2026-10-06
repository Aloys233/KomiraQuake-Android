package com.aloys23.komiraquake.data.source.wolfx

import com.aloys23.komiraquake.core.AppClock
import com.aloys23.komiraquake.core.IntensityStandard
import com.aloys23.komiraquake.core.NetworkGate
import com.aloys23.komiraquake.data.source.EarthquakeSource
import com.aloys23.komiraquake.data.source.EewParser
import com.aloys23.komiraquake.data.source.SourceEvent
import com.aloys23.komiraquake.data.source.SourceEventKind
import com.aloys23.komiraquake.data.source.awaitReconnectDelay
import com.aloys23.komiraquake.model.ConnectionStatus
import com.aloys23.komiraquake.model.DataSourceInfo
import com.aloys23.komiraquake.model.EarthquakeEvent
import com.aloys23.komiraquake.model.SourceIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Independently monitored WebSocket warnings and HTTP catalog, fenced by start/stop session. */
class WolfxSource(
    private val scope: CoroutineScope,
    okHttp: OkHttpClient,
    private val locationProvider: () -> Pair<Double, Double>?,
    private val standardProvider: () -> IntensityStandard,
    socketFactory: WebSocket.Factory? = null,
    private val callFactory: Call.Factory = okHttp,
    /** 网络感知重连；默认恒在线，退化为固定退避。 */
    private val networkGate: NetworkGate = NetworkGate.AlwaysOnline,
) : EarthquakeSource {
    override val id: String get() = SourceIds.WOLFX
    private val lock = Any()
    private val _events = MutableSharedFlow<SourceEvent>(extraBufferCapacity = 128)
    override val events: SharedFlow<SourceEvent> = _events.asSharedFlow()
    private val _status = MutableStateFlow(DataSourceInfo(
        id = SourceIds.WOLFX, name = "Wolfx", region = "全球",
        description = "Wolfx all_eew 聚合（CENC/SC/JMA/CWA/FJ/CQ）",
    ))
    override val status: StateFlow<DataSourceInfo> = _status.asStateFlow()
    private val sockets = socketFactory ?: okHttp.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    private var running = false
    private var generation = 0L
    private var attempt = 0L
    private var urlIndex = 0
    private var retryCount = 0
    private var webSocket: WebSocket? = null
    private var directoryCall: Call? = null
    private var queryJob: Job? = null
    private var pollJob: Job? = null
    private var reconnectJob: Job? = null

    override fun isCurrent(event: SourceEvent): Boolean = synchronized(lock) { current(event.generation) }
    private fun current(session: Long) = running && generation == session
    private fun current(session: Long, socketAttempt: Long) = current(session) && attempt == socketAttempt

    override fun start() = synchronized(lock) {
        if (running) return@synchronized
        running = true
        val session = ++generation
        connect(session)
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                // 断网时不发注定失败的目录请求，等恢复后由 awaitOnline 立刻唤醒。
                if (networkGate.awaitOnline()) retryCount = 0
                pollDirectory(session)
                delay(WolfxProtocol.POLL_INTERVAL_MS)
            }
        }
    }

    override fun stop() = synchronized(lock) {
        running = false
        generation++
        attempt++
        queryJob?.cancel(); queryJob = null
        pollJob?.cancel(); pollJob = null
        reconnectJob?.cancel(); reconnectJob = null
        directoryCall?.cancel(); directoryCall = null
        val oldSocket = webSocket
        webSocket = null
        oldSocket?.cancel()
        _status.value = _status.value.copy(
            status = ConnectionStatus.DISCONNECTED,
            directoryStatus = ConnectionStatus.DISCONNECTED,
            latencyMs = null,
        )
    }

    /** Derived local values are recalculated by the repository, never by starting networking. */
    override fun onLocationChanged() = Unit

    private fun connect(session: Long) {
        if (!current(session)) return
        val socketAttempt = ++attempt
        _status.value = _status.value.copy(status = ConnectionStatus.CONNECTING)
        val request = Request.Builder().url(WolfxProtocol.WS_URLS[urlIndex % WolfxProtocol.WS_URLS.size]).build()
        webSocket = sockets.newWebSocket(request, listener(session, socketAttempt))
    }

    private fun disconnected(session: Long, failed: Boolean) {
        // Retire this attempt immediately: late frames/onOpen cannot race the reconnect delay.
        attempt++
        queryJob?.cancel(); queryJob = null
        val oldSocket = webSocket
        webSocket = null
        oldSocket?.cancel()
        _status.value = _status.value.copy(status = if (failed) ConnectionStatus.ERROR else ConnectionStatus.DISCONNECTED)
        if (failed) urlIndex++
        val delayMs = (3 + retryCount++).coerceIn(3, 15) * 1000L
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val resumed = awaitReconnectDelay(networkGate, delayMs)
            synchronized(lock) {
                if (!current(session)) return@synchronized
                // 断网期间的失败不归咎于站点：退避与站点索引一并复位回首选。
                if (resumed) { retryCount = 0; urlIndex = 0 }
                connect(session)
            }
        }
    }

    override fun refreshDirectory() = synchronized(lock) {
        if (!running) return@synchronized
        val session = generation
        scope.launch(Dispatchers.IO) { pollDirectory(session) }
        Unit
    }

    private fun pollDirectory(session: Long) {
        synchronized(lock) {
            if (!current(session) || directoryCall != null) return
            _status.value = _status.value.copy(directoryStatus = ConnectionStatus.CONNECTING, directoryError = null)
        }
        val started = AppClock.elapsedMs()
        var failure: String? = null
        // 一个轮询周期顺序拉取全部目录端点；全部成功才算目录健康。
        for ((url, agency) in DIRECTORY_ENDPOINTS) {
            val call = synchronized(lock) {
                if (!current(session)) return
                callFactory.newCall(Request.Builder().url(url).build()).also { directoryCall = it }
            }
            try {
                call.execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    val body = checkNotNull(response.body?.string()) { "Empty response" }
                    val root = JSONObject(body)
                    synchronized(lock) {
                        if (!current(session)) return
                        val loc = locationProvider()
                        val standard = standardProvider()
                        for (key in root.keys()) {
                            if (!key.startsWith("No")) continue
                            val item = root.optJSONObject(key) ?: continue
                            val parsed = parseDirectoryItem(url, item, loc, standard) ?: continue
                            _events.tryEmit(SourceEvent(parsed.copy(
                                sourceProvider = WolfxProtocol.PROVIDER,
                                sourceAgency = agency,
                            ), SourceEventKind.DIRECTORY, session))
                        }
                    }
                }
            } catch (error: Exception) {
                if (failure == null) failure = "$url: ${error.message ?: "directory request failed"}"
            } finally {
                synchronized(lock) { if (directoryCall === call) directoryCall = null }
            }
            if (!current(session)) return
        }
        synchronized(lock) {
            if (!current(session)) return
            if (failure == null) {
                _status.value = _status.value.copy(
                    directoryStatus = ConnectionStatus.CONNECTED,
                    directoryLatencyMs = AppClock.elapsedMs() - started,
                    directoryLastSuccessAt = AppClock.now(), directoryError = null,
                )
            } else {
                _status.value = _status.value.copy(
                    directoryStatus = ConnectionStatus.ERROR, directoryError = failure,
                )
            }
        }
    }

    private fun parseDirectoryItem(
        url: String, item: JSONObject, loc: Pair<Double, Double>?, standard: IntensityStandard,
    ): EarthquakeEvent? = when (url) {
        WolfxProtocol.JMA_EQ_LIST_URL -> EewParser.parseJmaDirectory(item, loc, standard)
        else -> EewParser.parseCencDirectory(item, loc, standard)
    }

    private fun handleMessage(text: String, ws: WebSocket, session: Long) {
        val obj = try { JSONObject(text) } catch (_: Exception) { return }
        val type = obj.optString("type", "")
        when (type) {
            "heartbeat" -> {
                ws.send("""{"type":"pong","timestamp":${AppClock.now()}}""")
                return
            }
            "pong" -> return
        }
        val resolvedType = type.ifEmpty { "cwa_eew" }
        if (resolvedType !in WolfxProtocol.EEW_TYPES) return
        val parsed = EewParser.parse(obj, locationProvider(), standardProvider(),
            WolfxProtocol.TITLES[resolvedType] ?: resolvedType, "wolfx_",
            originTimeIsJst = resolvedType == "jma_eew",
            eventNamespace = resolvedType) ?: return
        if (parsed.timestamp > 0 && AppClock.now() - parsed.timestamp > WolfxProtocol.EEW_LIVE_WINDOW_MS) return
        _events.tryEmit(SourceEvent(parsed.copy(
            sourceProvider = WolfxProtocol.PROVIDER,
            sourceAgency = WolfxProtocol.agencyFor(resolvedType),
        ), SourceEventKind.LIVE, session))
    }

    private fun listener(session: Long, socketAttempt: Long) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(lock) {
            if (!current(session, socketAttempt)) { webSocket.cancel(); return@synchronized }
            retryCount = 0
            _status.value = _status.value.copy(status = ConnectionStatus.CONNECTED, lastHeartbeat = AppClock.now())
            queryJob?.cancel()
            queryJob = scope.launch {
                while (isActive) {
                    synchronized(lock) {
                        if (!current(session, socketAttempt)) return@launch
                        WolfxProtocol.QUERIES.forEach { webSocket.send(it) }
                    }
                    delay(WolfxProtocol.QUERY_INTERVAL_MS)
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) = synchronized(lock) {
            if (!current(session, socketAttempt)) return@synchronized
            _status.value = _status.value.copy(lastHeartbeat = AppClock.now())
            if (text.trimStart().startsWith("[")) {
                try {
                    val arr = JSONArray(text)
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        handleMessage(obj.toString(), webSocket, session)
                    }
                } catch (_: Exception) { }
            } else handleMessage(text, webSocket, session)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = synchronized(lock) {
            if (current(session, socketAttempt)) disconnected(session, false)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = synchronized(lock) {
            if (current(session, socketAttempt)) disconnected(session, false)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = synchronized(lock) {
            if (current(session, socketAttempt)) disconnected(session, true)
        }
    }

    private companion object {
        /** 目录端点（URL → 该源报数机构）；全部成功才视为目录健康。 */
        val DIRECTORY_ENDPOINTS = listOf(
            WolfxProtocol.EQ_LIST_URL to WolfxProtocol.DIRECTORY_AGENCY,
            WolfxProtocol.JMA_EQ_LIST_URL to WolfxProtocol.DIRECTORY_AGENCY_JMA,
        )
    }
}
