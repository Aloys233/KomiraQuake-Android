package com.aloys23.komiraquake.data.source.pancakes

import com.aloys23.komiraquake.core.AppClock
import com.aloys23.komiraquake.core.ForegroundGate
import com.aloys23.komiraquake.core.IntensityStandard
import com.aloys23.komiraquake.core.NetworkGate
import com.aloys23.komiraquake.data.source.EarthquakeSource
import com.aloys23.komiraquake.data.source.SourceEvent
import com.aloys23.komiraquake.data.source.SourceEventKind
import com.aloys23.komiraquake.data.source.awaitReconnectDelay
import com.aloys23.komiraquake.model.ConnectionStatus
import com.aloys23.komiraquake.model.DataSourceInfo
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

/**
 * PancakesAPI 数据源：聚合 WebSocket 实时预警 + 各地震子源的 HTTP 目录轮询。
 *
 * 连接 `wss://api.aloys23.link/api/v1/alert/ws/all`，只处理 gq / usgs / jma_eew / jma_eqlist
 * 四类地震事件；气象、海洋与火山事件一律忽略。服务端每 30s 发送 WebSocket Ping，客户端
 * 由 OkHttp 自动回 Pong，无需业务层心跳或订阅报文。
 */
class PancakesSource(
    private val scope: CoroutineScope,
    okHttp: OkHttpClient,
    private val locationProvider: () -> Pair<Double, Double>?,
    private val standardProvider: () -> IntensityStandard,
    socketFactory: WebSocket.Factory? = null,
    private val callFactory: Call.Factory = okHttp,
    /** 网络感知重连；默认恒在线，退化为固定退避。 */
    private val networkGate: NetworkGate = NetworkGate.AlwaysOnline,
    /** 前后台闸门：目录 HTTP 轮询只在前台进行；实时 WS 长连接不受影响。 */
    private val foregroundGate: ForegroundGate = ForegroundGate.AlwaysForeground,
) : EarthquakeSource {
    override val id: String get() = SourceIds.PANCAKES
    private val lock = Any()
    private val _events = MutableSharedFlow<SourceEvent>(extraBufferCapacity = 128)
    override val events: SharedFlow<SourceEvent> = _events.asSharedFlow()
    private val _status = MutableStateFlow(DataSourceInfo(
        id = SourceIds.PANCAKES, name = "Pancakes", region = "全球",
        description = "Pancakes 聚合（GQ/USGS/JMA）",
    ))
    override val status: StateFlow<DataSourceInfo> = _status.asStateFlow()
    private val sockets = socketFactory ?: okHttp.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(25, TimeUnit.SECONDS).build()
    private var running = false
    private var generation = 0L
    private var attempt = 0L
    private var retryCount = 0
    private var webSocket: WebSocket? = null
    private val directoryCalls = HashSet<Call>()
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
                // 后台不刷新目录；回到前台由 awaitForeground 立即唤醒并刷一次。
                foregroundGate.awaitForeground()
                if (!current(session)) return@launch
                // 断网时不发注定失败的目录请求，等恢复后由 awaitOnline 立刻唤醒。
                if (networkGate.awaitOnline()) retryCount = 0
                pollDirectory(session)
                delay(PancakesProtocol.POLL_INTERVAL_MS)
            }
        }
    }

    override fun stop() = synchronized(lock) {
        running = false
        generation++
        attempt++
        pollJob?.cancel(); pollJob = null
        reconnectJob?.cancel(); reconnectJob = null
        directoryCalls.forEach { it.cancel() }
        directoryCalls.clear()
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
        val request = Request.Builder().url(PancakesProtocol.WS_URL).build()
        webSocket = sockets.newWebSocket(request, listener(session, socketAttempt))
    }

    private fun disconnected(session: Long, failed: Boolean) {
        attempt++
        val oldSocket = webSocket
        webSocket = null
        oldSocket?.cancel()
        _status.value = _status.value.copy(status = if (failed) ConnectionStatus.ERROR else ConnectionStatus.DISCONNECTED)
        val delayMs = (3 + retryCount++).coerceIn(3, 15) * 1000L
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val resumed = awaitReconnectDelay(networkGate, delayMs)
            synchronized(lock) {
                if (!current(session)) return@synchronized
                if (resumed) retryCount = 0
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
            if (!current(session)) return
            _status.value = _status.value.copy(directoryStatus = ConnectionStatus.CONNECTING, directoryError = null)
        }
        var error: String? = null
        for (source in PancakesProtocol.QUAKE_SOURCES) {
            if (!current(session)) return
            try {
                fetchList(source, session)
            } catch (e: Exception) {
                error = e.message ?: "目录请求失败"
            }
        }
        synchronized(lock) {
            if (!current(session)) return
            _status.value = if (error == null) {
                _status.value.copy(
                    directoryStatus = ConnectionStatus.CONNECTED,
                    directoryLastSuccessAt = AppClock.now(), directoryError = null,
                )
            } else {
                _status.value.copy(directoryStatus = ConnectionStatus.ERROR, directoryError = error)
            }
        }
    }

    private fun fetchList(source: String, session: Long) {
        val started = AppClock.elapsedMs()
        val call = callFactory.newCall(Request.Builder().url(PancakesProtocol.listUrl(source)).build())
        synchronized(lock) {
            if (!current(session)) return
            directoryCalls.add(call)
        }
        try {
            call.execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                val body = checkNotNull(response.body?.string()) { "Empty response" }
                val array = JSONArray(body)
                synchronized(lock) {
                    if (!current(session)) return
                    val loc = locationProvider()
                    val standard = standardProvider()
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val parsed = PancakesParser.parseListItem(item, loc, standard, AppClock.now()) ?: continue
                        _events.tryEmit(SourceEvent(parsed, SourceEventKind.DIRECTORY, session))
                    }
                    _status.value = _status.value.copy(directoryLatencyMs = AppClock.elapsedMs() - started)
                }
            }
        } finally {
            synchronized(lock) { directoryCalls.remove(call) }
        }
    }

    private fun handleMessage(text: String, session: Long) {
        val obj = try { JSONObject(text) } catch (_: Exception) { return }
        if (obj.optString("type") == "pong" || obj.optString("type") == "heartbeat") return
        val parsed = PancakesParser.parseRealtime(obj, locationProvider(), standardProvider(), AppClock.now()) ?: return
        val now = AppClock.now()
        if (parsed.event.timestamp > 0 && now - parsed.event.timestamp > PancakesProtocol.LIVE_WINDOW_MS) return
        _events.tryEmit(SourceEvent(parsed.event, parsed.kind, session))
    }

    private fun listener(session: Long, socketAttempt: Long) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(lock) {
            if (!current(session, socketAttempt)) { webSocket.cancel(); return@synchronized }
            retryCount = 0
            _status.value = _status.value.copy(status = ConnectionStatus.CONNECTED, lastHeartbeat = AppClock.now())
        }

        override fun onMessage(webSocket: WebSocket, text: String) = synchronized(lock) {
            if (!current(session, socketAttempt)) return@synchronized
            _status.value = _status.value.copy(lastHeartbeat = AppClock.now())
            if (text.trimStart().startsWith("[")) {
                try {
                    val arr = JSONArray(text)
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        handleMessage(obj.toString(), session)
                    }
                } catch (_: Exception) { }
            } else handleMessage(text, session)
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
}
