package com.aloys23.komiraquake.data.source.whews

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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Whews 数据源（备用站国内 api.2v8.cn / 主站 api.beecld.com）。单条 `/ws/all` 聚合
 * WebSocket 覆盖全部已接入地震类频道；帧内 `source` 短名决定频道与机构。
 *
 * 站点：按 [WhewsProtocol.WS_HOSTS] 顺序**优先直连国内备用站**，仅在该站连接失败时
 * 才轮换到主站；连通后不回落（urlIndex 只在失败时前进）。唯一例外是断网恢复——此前的
 * 失败由无网造成而非站点不可用，故回到首选站重试。
 *
 * 鉴权：设置页粘贴 `wat_…` 令牌（本地持久化），以 `?token=` 附加在握手。无令牌即视为
 * 未配置，不建立任何连接。服务端对无效令牌回 `4401`、封禁回 `4403`，此时按文档要求
 * **停止重连**（重连过频会被智能封禁）。
 */
class WhewsSource(
    private val scope: CoroutineScope,
    okHttp: OkHttpClient,
    private val locationProvider: () -> Pair<Double, Double>?,
    private val standardProvider: () -> IntensityStandard,
    socketFactory: WebSocket.Factory? = null,
    /** 网络感知重连；默认恒在线，退化为固定退避。 */
    private val networkGate: NetworkGate = NetworkGate.AlwaysOnline,
) : EarthquakeSource {
    override val id: String get() = SourceIds.WHEWS
    private val lock = Any()
    private val _events = MutableSharedFlow<SourceEvent>(extraBufferCapacity = 128)
    override val events: SharedFlow<SourceEvent> = _events.asSharedFlow()
    private val _status = MutableStateFlow(
        DataSourceInfo(
            id = SourceIds.WHEWS, name = "Whews", region = "全球",
            description = BASE_DESCRIPTION,
        )
    )
    override val status: StateFlow<DataSourceInfo> = _status.asStateFlow()
    private val sockets = socketFactory ?: okHttp.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(25, TimeUnit.SECONDS).build()

    private var running = false
    private var generation = 0L
    private var attempt = 0L
    /** 站点索引：0 = 国内备用站（优先），失败才前进到主站。 */
    private var urlIndex = 0
    private var retryCount = 0
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var lastMessageMonoMs = 0L

    @Volatile private var token: String = ""

    private fun current(session: Long) = running && generation == session
    private fun current(session: Long, socketAttempt: Long) = current(session) && attempt == socketAttempt

    override fun isCurrent(event: SourceEvent): Boolean = synchronized(lock) { current(event.generation) }

    /** 未填令牌即视为未配置：上层据此不启动本源，不会建立任何连接。 */
    override fun isConfigured(): Boolean = token.isNotEmpty()

    /** 注入已持久化的令牌；变化时重连。 */
    fun setToken(value: String) {
        val next = value.trim()
        if (token == next) return
        val restart = synchronized(lock) {
            token = next
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
        if (restart) connect(generation)
    }

    override fun start() = synchronized(lock) {
        if (running) return@synchronized
        running = true
        retryCount = 0
        generation++
        connect(generation)
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

    /** 情报类端点首连即补发近期缓存，无需主动请求列表。 */
    override fun refreshDirectory() = Unit

    private fun connect(session: Long) {
        if (!current(session)) return
        if (token.isEmpty()) {
            setStatus(ConnectionStatus.DISCONNECTED, "未配置令牌：请在设置中填入 whews 令牌")
            return
        }
        val socketAttempt = ++attempt
        val host = WhewsProtocol.WS_HOSTS[urlIndex % WhewsProtocol.WS_HOSTS.size]
        setStatus(ConnectionStatus.CONNECTING)
        webSocket = sockets.newWebSocket(Request.Builder().url(handshakeUrl(host)).build(),
            listener(session, socketAttempt))
    }

    /**
     * 握手 URL：`<host>/ws/all?token=wat_…`。
     *
     * OkHttp 的 `HttpUrl` 只接受 http/https，直接 `toHttpUrl()` 解析 `wss://` 会抛
     * IllegalArgumentException；`Request.Builder.url(String)` 则接受 ws/wss（内部改写为
     * http/https）。这里先用 https 版 HttpUrl 做查询参数编码，再把 scheme 换回 wss，
     * 兼顾「参数正确转义」与「ws/wss 可用」。
     */
    private fun handshakeUrl(host: String): String {
        val https = "https" + host.removePrefix("wss") + WhewsProtocol.WS_PATH
        val encoded = https.toHttpUrl().newBuilder()
            .addQueryParameter("token", token)
            .build()
        return "wss" + encoded.toString().removePrefix("https")
    }

    /**
     * @param failed 传输失败（而非服务端主动关闭）；为 true 时前进站点索引并调度重连。
     * @param fatal 服务端明确拒绝鉴权（4401/4403）：按文档停止重连，避免触发智能封禁。
     */
    private fun disconnected(session: Long, failed: Boolean, fatal: Boolean = false) {
        if (!current(session)) return
        attempt++
        heartbeatJob?.cancel(); heartbeatJob = null
        val oldSocket = webSocket
        webSocket = null
        oldSocket?.cancel()
        if (fatal) {
            setStatus(ConnectionStatus.ERROR, "鉴权被拒绝：请检查令牌是否有效或已被吊销")
            return
        }
        setStatus(if (failed) ConnectionStatus.ERROR else ConnectionStatus.DISCONNECTED)
        if (failed) urlIndex++
        val delayMs = (3 + retryCount++).coerceIn(3, 15) * 1000L
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val resumed = awaitReconnectDelay(networkGate, delayMs)
            synchronized(lock) {
                if (!current(session)) return@synchronized
                // 断网期间的失败不归咎于站点：退避与站点索引一并复位回首选站。
                if (resumed) { retryCount = 0; urlIndex = 0 }
                connect(session)
            }
        }
    }

    private fun setStatus(status: ConnectionStatus, description: String? = null) = synchronized(lock) {
        _status.value = _status.value.copy(
            status = status,
            description = description ?: BASE_DESCRIPTION,
            latencyMs = if (status == ConnectionStatus.CONNECTED) _status.value.latencyMs else null,
        )
    }

    private fun startHeartbeatWatch(session: Long, socketAttempt: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(WhewsProtocol.HEARTBEAT_TIMEOUT_MS / 3)
                if (!current(session, socketAttempt)) return@launch
                if (AppClock.elapsedMs() - lastMessageMonoMs > WhewsProtocol.HEARTBEAT_TIMEOUT_MS) {
                    attempt++   // 退休本次尝试，避免随后到达的 onClosed 重复调度
                    setStatus(ConnectionStatus.ERROR, "心跳超时")
                    webSocket?.cancel()
                    disconnected(session, failed = true)
                    return@launch
                }
            }
        }
    }

    /** 帧：`{"Data":{…},"md5":…,"source":"<短名>"}`；首连为 JSON 数组，之后为单条对象。 */
    private fun handleMessage(text: String, session: Long) {
        lastMessageMonoMs = AppClock.elapsedMs()
        if (text.trimStart().startsWith("[")) {
            try {
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { handleFrame(it, session) }
                }
            } catch (_: Exception) {
            }
            return
        }
        try {
            handleFrame(JSONObject(text), session)
        } catch (_: Exception) {
        }
    }

    private fun handleFrame(frame: JSONObject, session: Long) {
        if (!current(session)) return
        val data = frame.optJSONObject("Data") ?: return
        val channel = WhewsProtocol.channelFor(frame.optString("source", "")) ?: return
        val parsed = WhewsParser.parseRecord(channel, data, locationProvider(), standardProvider()) ?: return
        if (channel.kind == SourceEventKind.LIVE &&
            parsed.timestamp > 0 &&
            AppClock.now() - parsed.timestamp > WhewsProtocol.EEW_LIVE_WINDOW_MS
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
        }

        override fun onMessage(webSocket: WebSocket, text: String) = synchronized(lock) {
            if (!current(session, socketAttempt)) return@synchronized
            lastMessageMonoMs = AppClock.elapsedMs()
            handleMessage(text, session)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = synchronized(lock) {
            if (!current(session, socketAttempt)) return@synchronized
            if (isFatalClose(code)) {
                disconnected(session, failed = false, fatal = true)
                return@synchronized
            }
            disconnected(session, failed = false)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = synchronized(lock) {
            if (!current(session, socketAttempt)) return@synchronized
            if (isFatalClose(code)) {
                disconnected(session, failed = false, fatal = true)
                return@synchronized
            }
            disconnected(session, failed = false)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = synchronized(lock) {
            if (!current(session, socketAttempt)) return@synchronized
            disconnected(session, failed = true)
        }
    }

    /** `4401` 令牌无效、`4403` 被封禁：文档要求停止盲目重连。 */
    private fun isFatalClose(code: Int): Boolean =
        code == WhewsProtocol.CloseCode.UNAUTHORIZED || code == WhewsProtocol.CloseCode.BANNED

    private companion object {
        const val BASE_DESCRIPTION = "Whews 聚合（CENC/CEA/JMA/CWA/USGS/EMSC 等 43 频道，优先国内站）"
    }
}