package com.aloys23.komiraquake.data.source.simulated

import com.aloys23.komiraquake.core.AppClock
import com.aloys23.komiraquake.core.IntensityStandard
import com.aloys23.komiraquake.core.NetworkGate
import com.aloys23.komiraquake.data.source.EarthquakeSource
import com.aloys23.komiraquake.data.source.SourceEvent
import com.aloys23.komiraquake.data.source.SourceEventKind
import com.aloys23.komiraquake.data.source.awaitReconnectDelay
import com.aloys23.komiraquake.model.ConnectionStatus
import com.aloys23.komiraquake.model.DataSourceInfo
import com.aloys23.komiraquake.model.EarthquakeEvent
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 模拟数据源（协议 `sim-eew/1`，服务端见仓库 `Simulated/`）。**仅供开发与自测。**
 *
 * 连接用户在设置页自填的地址（`ws://host:port/ws`），接收合成的 `report` /
 * `directory` 帧。帧内 `type` 决定实时预警还是目录情报。
 *
 * 门控：**开发者模式关闭或地址为空即视为未配置**，上层据此不启动本源，
 * 因而不会建立任何连接。模拟源故意不进 `defaultDisabledSources()` ——那套语义是
 * 「需要用户另行获取的凭据」，地址不是凭据。
 *
 * 机构固定为 `SIM`：合并键是 `sourceAgency|eventId`，若与真实报文撞键，
 * `EventGate` 会判进同一条目，告警不触发，用户看到真实数据被假数据覆盖。
 */
class SimulatedSource(
    private val scope: CoroutineScope,
    okHttp: OkHttpClient,
    private val locationProvider: () -> Pair<Double, Double>?,
    private val standardProvider: () -> IntensityStandard,
    /** 开发者模式开关。实时读取，翻转开关无需重推。 */
    private val devModeProvider: () -> Boolean,
    socketFactory: WebSocket.Factory? = null,
    /** 网络感知重连；默认恒在线，退化为固定退避。 */
    private val networkGate: NetworkGate = NetworkGate.AlwaysOnline,
) : EarthquakeSource {
    override val id: String get() = SourceIds.SIMULATED

    private val lock = Any()
    private val _events = MutableSharedFlow<SourceEvent>(extraBufferCapacity = 128)
    override val events: SharedFlow<SourceEvent> = _events.asSharedFlow()
    private val _status = MutableStateFlow(
        DataSourceInfo(
            id = SourceIds.SIMULATED, name = "模拟数据源", region = "自测",
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

    @Volatile private var url: String = ""
    /** 最近一次发出的帧，供 [onAdmission] 匹配回执（乱序时不得张冠李戴）。 */
    private var lastEmitted: EarthquakeEvent? = null

    private fun current(session: Long) = running && generation == session
    private fun current(session: Long, socketAttempt: Long) = current(session) && attempt == socketAttempt

    override fun isCurrent(event: SourceEvent): Boolean = synchronized(lock) { current(event.generation) }

    /** 开发者模式开启且地址非空才视为已配置：上层据此不启动本源，不会建立任何连接。 */
    override fun isConfigured(): Boolean = devModeProvider() && url.isNotBlank()

    /** 注入地址；变化时重连。 */
    fun setUrl(value: String) {
        val next = value.trim()
        if (url == next) return
        val restart = synchronized(lock) {
            url = next
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

    /**
     * 仓库层判定后回报最终处置：墓碑 / 重复 / 取消这些在链路上不报错，
     * 不回报就表现为静默丢弃（对自建的服务端尤其难排查）。
     */
    fun onAdmission(event: EarthquakeEvent, status: String) {
        val sent = synchronized(lock) { lastEmitted } ?: return
        if (event.identity != sent.identity || event.reportNum != sent.reportNum) return
        val ack = JSONObject()
            .put("type", "ack")
            .put("eventId", event.eventId)
            .put("reportNum", event.reportNum)
            .put("status", status)
        synchronized(lock) { webSocket }?.send(ack.toString())
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

    /** 服务端按自己的节奏推报文，无需主动请求列表。 */
    override fun refreshDirectory() = Unit

    private fun connect(session: Long) {
        if (!current(session)) return
        if (!isConfigured()) {
            setStatus(
                ConnectionStatus.DISCONNECTED,
                if (devModeProvider()) "未配置地址：请在设置中填入模拟源地址" else "开发者模式未开启",
            )
            return
        }
        val socketAttempt = ++attempt
        val target = url
        setStatus(ConnectionStatus.CONNECTING)
        // Request.Builder.url(String) 接受 ws/wss（内部改写为 http/https）。
        // **不要**改用 toHttpUrl()：它只接受 http/https，对 wss:// 会抛异常
        // （WhewsSource.handshakeUrl 的注释与 JianSource 的现存 bug 都是这条）。
        // 本源无查询参数，直接传字符串即可。
        webSocket = sockets.newWebSocket(Request.Builder().url(target).build(),
            listener(session, socketAttempt))
    }

    private fun disconnected(session: Long, failed: Boolean) {
        if (!current(session)) return
        // 退休本次尝试：QWebSocket/OkHttp 在传输失败时可能同时回调多个终止事件，
        // 谁先跑谁 ++attempt_，另一个因此跳过调度，避免重复重连。
        attempt++
        heartbeatJob?.cancel(); heartbeatJob = null
        val oldSocket = webSocket
        webSocket = null
        oldSocket?.cancel()
        setStatus(if (failed) ConnectionStatus.ERROR else ConnectionStatus.DISCONNECTED)
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
                delay(SimulatedProtocol.HEARTBEAT_TIMEOUT_MS / 3)
                if (!current(session, socketAttempt)) return@launch
                if (AppClock.elapsedMs() - lastMessageMonoMs > SimulatedProtocol.HEARTBEAT_TIMEOUT_MS) {
                    attempt++   // 退休本次尝试，避免随后到达的回调重复调度
                    setStatus(ConnectionStatus.ERROR, "心跳超时")
                    webSocket?.cancel()
                    disconnected(session, failed = true)
                    return@launch
                }
            }
        }
    }

    private fun handleMessage(text: String, session: Long) {
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
        val type = frame.optString("type", "")
        // 控制帧：不产生事件，只刷新心跳。
        if (type == "ping" || type == "hello") {
            _status.value = _status.value.copy(lastHeartbeat = AppClock.now())
            return
        }
        val kind = if (type == "directory") SourceEventKind.DIRECTORY else SourceEventKind.LIVE
        val parsed = SimulatedParser.parseReport(frame, kind, locationProvider(), standardProvider())
            ?: return
        val now = AppClock.now()
        // 活跃窗口：陈旧帧丢弃。服务端会钳制未来时刻，但客户端这一侧仍要独立把关，
        // 且回执要让作者看到「这一帧为什么没生效」。
        if (kind == SourceEventKind.LIVE && parsed.timestamp > 0 &&
            now - parsed.timestamp > SimulatedProtocol.EEW_LIVE_WINDOW_MS
        ) {
            onAdmission(parsed, "stale")
            return
        }
        if (parsed.timestamp > now + 60_000L) {
            onAdmission(parsed, "future")
            return
        }
        lastEmitted = parsed
        _events.tryEmit(SourceEvent(parsed, kind, session))
    }

    private fun listener(session: Long, socketAttempt: Long) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(lock) {
            if (!current(session, socketAttempt)) { webSocket.cancel(); return@synchronized }
            retryCount = 0
            lastMessageMonoMs = AppClock.elapsedMs()
            setStatus(ConnectionStatus.CONNECTED)
            startHeartbeatWatch(session, socketAttempt)
            // 报个到，服务端控制台据此显示是哪个客户端接入了。
            webSocket.send(JSONObject().put("type", "hello").put("client", "android").toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) = synchronized(lock) {
            if (!current(session, socketAttempt)) return@synchronized
            lastMessageMonoMs = AppClock.elapsedMs()
            handleMessage(text, session)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = synchronized(lock) {
            if (!current(session, socketAttempt)) return@synchronized
            disconnected(session, failed = false)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = synchronized(lock) {
            if (!current(session, socketAttempt)) return@synchronized
            disconnected(session, failed = false)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
            synchronized(lock) {
                if (!current(session, socketAttempt)) return@synchronized
                disconnected(session, failed = true)
            }
    }

    private companion object {
        const val BASE_DESCRIPTION = "本地模拟源（开发自测用，机构 SIM，不参与真实预警）"
    }
}
