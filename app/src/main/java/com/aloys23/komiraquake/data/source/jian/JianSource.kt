package com.aloys23.komiraquake.data.source.jian

import com.aloys23.komiraquake.core.AppClock
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
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Jian 数据源（api.sismotide.top，Jian Project）。单条 `/all` 聚合 WebSocket 覆盖全部地震类频道。
 *
 * 鉴权：设置页填登录密钥 `lk_…` → 换刷新令牌 `rt_…`（本地持久化）→ 连接前用 `rt_` 换访问令牌
 * `at_…`（约 1 小时），以 `?key=at_…` 附加在握手。`at_` 过期（错误码 4004）时自动重取。
 */
class JianSource(
    private val scope: CoroutineScope,
    okHttp: OkHttpClient,
    private val locationProvider: () -> Pair<Double, Double>?,
    private val standardProvider: () -> IntensityStandard,
    private val onRefreshToken: (String) -> Unit = {},
    socketFactory: WebSocket.Factory? = null,
    private val callFactory: Call.Factory = okHttp,
    /** 网络感知重连；默认恒在线，退化为固定退避。 */
    private val networkGate: NetworkGate = NetworkGate.AlwaysOnline,
) : EarthquakeSource {
    override val id: String get() = SourceIds.JIAN
    private val lock = Any()
    private val _events = MutableSharedFlow<SourceEvent>(extraBufferCapacity = 128)
    override val events: SharedFlow<SourceEvent> = _events.asSharedFlow()
    private val _status = MutableStateFlow(
        DataSourceInfo(
            id = SourceIds.JIAN, name = "Jian", region = "全球",
            description = BASE_DESCRIPTION,
        )
    )
    override val status: StateFlow<DataSourceInfo> = _status.asStateFlow()
    private val sockets = socketFactory ?: okHttp.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(25, TimeUnit.SECONDS).build()

    private var running = false
    private var generation = 0L
    private var attempt = 0L
    private var retryCount = 0
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var lastMessageMonoMs = 0L

    @Volatile private var refreshToken: String = ""
    @Volatile private var accessToken: String = ""
    @Volatile private var accessExpiryMonoMs = 0L

    private fun current(session: Long) = running && generation == session
    private fun current(session: Long, socketAttempt: Long) = current(session) && attempt == socketAttempt

    override fun isCurrent(event: SourceEvent): Boolean = synchronized(lock) { current(event.generation) }

    /** 未登录（无刷新令牌）即视为未配置：上层据此不启动本源，不会建立任何连接。 */
    override fun isConfigured(): Boolean = refreshToken.isNotEmpty()

    /** 注入已持久化的刷新令牌；变化时重新鉴权并连接。 */
    fun setRefreshToken(token: String) {
        if (refreshToken == token) return
        val restart = synchronized(lock) {
            refreshToken = token
            accessToken = ""
            accessExpiryMonoMs = 0
            if (!running) return
            generation++
            attempt++
            reconnectJob?.cancel(); reconnectJob = null
            heartbeatJob?.cancel(); heartbeatJob = null
            val old = webSocket
            webSocket = null
            old?.cancel()
            true
        }
        if (restart) ensureAccessAndConnect(generation)
    }

    override fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            retryCount = 0
            generation++
            val session = generation
            ensureAccessAndConnect(session)
        }
    }

    override fun stop() = synchronized(lock) {
        running = false
        generation++
        attempt++
        reconnectJob?.cancel(); reconnectJob = null
        heartbeatJob?.cancel(); heartbeatJob = null
        val old = webSocket
        webSocket = null
        old?.cancel()
        if (_status.value.status != ConnectionStatus.DISCONNECTED) {
            _status.value = _status.value.copy(
                status = ConnectionStatus.DISCONNECTED, latencyMs = null, description = BASE_DESCRIPTION,
            )
        }
    }

    /** 派生字段由仓库统一重算，源内不因定位变化重启网络。 */
    override fun onLocationChanged() = Unit

    override fun refreshDirectory() = synchronized(lock) {
        if (!running) return@synchronized
        webSocket?.send(JianProtocol.LIST_COMMAND)
        Unit
    }

    /** 用登录密钥换取刷新令牌；成功后回调 [onRefreshToken] 交由上层持久化。 */
    fun login(loginKey: String) {
        val key = loginKey.trim()
        if (key.isEmpty()) return
        val request = Request.Builder().url(JianProtocol.REFRESH_URL)
            .header("Authorization", "Bearer $key")
            .post(ByteArray(0).toRequestBody())
            .build()
        callFactory.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                setStatus(ConnectionStatus.ERROR, "登录失败：${e.message ?: "网络错误"}")
            }

            override fun onResponse(call: Call, response: Response) = response.use {
                val token = JianParser.parseAuthToken(it.body?.string().orEmpty())
                if (!it.isSuccessful || token == null) {
                    setStatus(ConnectionStatus.ERROR, "登录失败：${if (it.isSuccessful) "登录密钥无效或已过期" else "HTTP ${it.code}"}")
                    return@use
                }
                synchronized(lock) {
                    refreshToken = token
                    accessToken = ""
                    accessExpiryMonoMs = 0
                }
                onRefreshToken(token)
                if (running) ensureAccessAndConnect(generation)
            }
        })
    }

    private fun setStatus(status: ConnectionStatus, description: String? = null) = synchronized(lock) {
        _status.value = _status.value.copy(
            status = status,
            description = description ?: BASE_DESCRIPTION,
            latencyMs = if (status == ConnectionStatus.CONNECTED) _status.value.latencyMs else null,
        )
    }

    private fun ensureAccessAndConnect(session: Long) {
        if (!current(session)) return
        val token = refreshToken
        if (token.isEmpty()) {
            setStatus(ConnectionStatus.DISCONNECTED, "未登录：请在设置中填入登录密钥（lk_…）")
            return
        }
        if (accessToken.isEmpty() || AppClock.elapsedMs() >= accessExpiryMonoMs) {
            fetchAccessToken(session, token)
            return
        }
        connectSocket(session)
    }

    private fun fetchAccessToken(session: Long, token: String) {
        setStatus(ConnectionStatus.CONNECTING, "获取访问令牌…")
        val request = Request.Builder().url(JianProtocol.ACCESS_URL)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        callFactory.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!current(session)) return
                setStatus(ConnectionStatus.ERROR, "获取访问令牌失败：${e.message ?: "网络错误"}")
                disconnected(session, failed = true)
            }

            override fun onResponse(call: Call, response: Response) = response.use {
                val at = JianParser.parseAuthToken(it.body?.string().orEmpty())
                if (!current(session)) return@use
                if (!it.isSuccessful || at == null) {
                    setStatus(
                        ConnectionStatus.ERROR,
                        "获取访问令牌失败：" + if (it.isSuccessful) "刷新令牌无效或已过期" else "HTTP ${it.code}",
                    )
                    disconnected(session, failed = true)
                    return@use
                }
                synchronized(lock) {
                    accessToken = at
                    // at_ 约 1 小时；留 5 分钟余量，避免握手瞬间过期。
                    accessExpiryMonoMs = AppClock.elapsedMs() + 55L * 60_000
                }
                connectSocket(session)
            }
        })
    }

    private fun connectSocket(session: Long) {
        if (!current(session)) return
        val at = accessToken
        if (at.isEmpty()) return
        val socketAttempt = ++attempt
        synchronized(lock) {
            webSocket?.cancel()
            webSocket = null
            setStatus(ConnectionStatus.CONNECTING)
        }
        val url = JianProtocol.WS_URL.toHttpUrl().newBuilder()
            .addQueryParameter("key", at)
            .build()
        val socket = sockets.newWebSocket(Request.Builder().url(url).build(), listener(session, socketAttempt))
        synchronized(lock) { webSocket = socket }
    }

    private fun disconnected(session: Long, failed: Boolean) {
        if (!current(session)) return
        attempt++
        heartbeatJob?.cancel(); heartbeatJob = null
        if (failed) setStatus(ConnectionStatus.ERROR, "WS 连接失败")
        val delayMs = (3 + retryCount++).coerceIn(3, 15) * 1000L
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val resumed = awaitReconnectDelay(networkGate, delayMs)
            if (!current(session)) return@launch
            if (resumed) synchronized(lock) { retryCount = 0 }
            ensureAccessAndConnect(session)
        }
    }

    private fun startHeartbeatWatch(session: Long, socketAttempt: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(JianProtocol.HEARTBEAT_TIMEOUT_MS / 3)
                if (!current(session, socketAttempt)) return@launch
                if (AppClock.elapsedMs() - lastMessageMonoMs > JianProtocol.HEARTBEAT_TIMEOUT_MS) {
                    attempt++   // 退休本次尝试，避免随后到达的 onClosed 重复调度
                    setStatus(ConnectionStatus.ERROR, "心跳超时")
                    webSocket?.cancel()
                    disconnected(session, failed = true)
                    return@launch
                }
            }
        }
    }

    private fun handleMessage(text: String, session: Long) {
        lastMessageMonoMs = AppClock.elapsedMs()
        val obj = try { JSONObject(text) } catch (_: Exception) { return }
        val type = obj.optString("type", "")
        when {
            type == "heartbeat" || type == "pong" -> return

            type == "error" -> {
                if (obj.optInt("code", 0) == 4004) {   // 访问令牌过期：丢弃并重取
                    synchronized(lock) { accessToken = ""; accessExpiryMonoMs = 0 }
                }
                val message = obj.optString("message", obj.optString("error", "服务端错误"))
                setStatus(ConnectionStatus.ERROR, message)
                if (!current(session)) return
                attempt++
                webSocket?.cancel()
                disconnected(session, failed = true)
                return
            }

            type == "all" -> {
                // 首帧快照：{"type":"all","source:xxx":{"Data":{…},"md5":…},…}
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (!key.startsWith("source:")) continue
                    val data = obj.optJSONObject(key)?.optJSONObject("Data") ?: continue
                    handleRecord(key.removePrefix("source:"), data, session)
                }
                return
            }

            type.endsWith("list_response") -> {
                val base = type.removeSuffix("list_response")
                val data = obj.opt("Data")
                if (data is JSONArray) {
                    for (i in 0 until data.length()) {
                        data.optJSONObject(i)?.let { handleRecord(base, it, session) }
                    }
                } else if (data is JSONObject) handleRecord(base, data, session)
                return
            }
        }
        obj.optJSONObject("Data")?.let { handleRecord(type, it, session) }
    }

    private fun handleRecord(type: String, data: JSONObject, session: Long) {
        if (!current(session)) return
        val channel = JianProtocol.channelFor(type) ?: return
        val parsed = JianParser.parseRecord(channel, data, locationProvider(), standardProvider()) ?: return
        if (channel.kind == SourceEventKind.LIVE &&
            parsed.timestamp > 0 &&
            AppClock.now() - parsed.timestamp > JianProtocol.EEW_LIVE_WINDOW_MS
        ) return
        _events.tryEmit(SourceEvent(parsed, channel.kind, session))
    }

    private fun listener(session: Long, socketAttempt: Long) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(lock) {
            if (!current(session, socketAttempt)) { webSocket.cancel(); return@synchronized }
            retryCount = 0
            lastMessageMonoMs = AppClock.elapsedMs()
            setStatus(ConnectionStatus.CONNECTED)
            startHeartbeatWatch(session, socketAttempt)
            // 请求历史列表：/all 会依次回推 cenc/cwa/jma/hko 的 *list_response。
            webSocket.send(JianProtocol.LIST_COMMAND)
        }

        override fun onMessage(webSocket: WebSocket, text: String) = synchronized(lock) {
            if (!current(session, socketAttempt)) return@synchronized
            lastMessageMonoMs = AppClock.elapsedMs()
            if (text.trimStart().startsWith("[")) {
                try {
                    val arr = JSONArray(text)
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { handleMessage(it.toString(), session) }
                    }
                } catch (_: Exception) {
                }
            } else handleMessage(text, session)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = synchronized(lock) {
            if (current(session, socketAttempt)) disconnected(session, failed = false)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = synchronized(lock) {
            if (current(session, socketAttempt)) disconnected(session, failed = false)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = synchronized(lock) {
            if (current(session, socketAttempt)) disconnected(session, failed = true)
        }
    }

    private companion object {
        const val BASE_DESCRIPTION = "Jian（api.sismotide.top）聚合（CENC/CEA/JMA/CWA/HKO）"
    }
}
