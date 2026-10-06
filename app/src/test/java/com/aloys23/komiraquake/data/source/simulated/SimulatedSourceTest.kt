package com.aloys23.komiraquake.data.source.simulated

import com.aloys23.komiraquake.core.IntensityStandard
import com.aloys23.komiraquake.data.source.EewParser
import com.aloys23.komiraquake.data.source.SourceEvent
import com.aloys23.komiraquake.data.source.SourceEventKind
import com.aloys23.komiraquake.model.ConnectionStatus
import com.aloys23.komiraquake.model.EarthquakeEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 模拟源（sim-eew/1）行为。协议契约见《NATIVE_PORT_SPEC》 §2.7。 */
class SimulatedSourceTest {

    private class Socket : WebSocket {
        var canceled = false
        val sent = mutableListOf<String>()
        override fun request() = Request.Builder().url("https://example.com").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { sent.add(text); return true }
        override fun send(bytes: ByteString): Boolean = true
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { canceled = true }
    }

    private class Sockets : WebSocket.Factory {
        val requests = mutableListOf<Request>()
        val connections = mutableListOf<Pair<Socket, WebSocketListener>>()
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            requests.add(request)
            return Socket().also { connections.add(it to listener) }
        }
    }

    private fun response(request: Request) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols")
        .body("".toResponseBody()).build()

    private fun source(
        scope: CoroutineScope,
        sockets: Sockets,
        devMode: Boolean = true,
    ) = SimulatedSource(
        scope, OkHttpClient(), { null }, { IntensityStandard.CSIS },
        devModeProvider = { devMode }, socketFactory = sockets,
    )

    /** 一帧合法报文；originTime 缺省取「现在」，故不会被新鲜度窗口与未来时刻闸门拦下。 */
    private fun frame(
        eventId: String = "sim-1-a",
        reportNum: Int = 1,
        originTime: Long = System.currentTimeMillis(),
        type: String = "report",
        extra: Map<String, Any?> = emptyMap(),
    ): String = JSONObject()
        .put("type", type)
        .put("eventId", eventId)
        .put("reportNum", reportNum)
        .put("originTime", originTime)
        .put("magnitude", 6.5)
        .put("latitude", 20.0)
        .put("longitude", 160.0)
        .put("depth", 12.0)
        .put("location", "模拟震源")
        .apply { extra.forEach { (k, v) -> put(k, v) } }
        .toString()

    /** 起源、握手、开始收集事件。返回收集协程与已开连接。 */
    private fun started(
        scope: CoroutineScope,
        sockets: Sockets,
        source: SimulatedSource,
    ): Pair<Socket, WebSocketListener> {
        source.setUrl("ws://10.0.2.2:8080/ws")
        source.start()
        val (socket, listener) = sockets.connections.single()
        listener.onOpen(socket, response(socket.request()))
        return socket to listener
    }

    // ── 门控：开发者模式 + 地址缺一不可 ─────────────────────────────

    /** 开发者模式关闭即视为未配置：上层不启动本源，源自身也不得建立任何连接。 */
    @Test fun devModeOffMeansNotConfiguredAndNeverConnects() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sockets = Sockets()
        val source = source(scope, sockets, devMode = false)
        try {
            source.setUrl("ws://10.0.2.2:8080/ws")
            // 地址有效但开发者模式关：仍须未配置，否则会静默建立连接。
            assertFalse(source.isConfigured())
            source.start()
            assertTrue(sockets.requests.isEmpty())
            assertEquals(ConnectionStatus.DISCONNECTED, source.status.value.status)
        } finally { source.stop(); scope.cancel() }
    }

    /** 地址留空即未配置：默认值只作 UI 占位，不预填进持久状态。 */
    @Test fun blankUrlMeansNotConfigured() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sockets = Sockets()
        val source = source(scope, sockets)
        try {
            source.setUrl("   ")
            assertFalse(source.isConfigured())
            source.start()
            assertTrue(sockets.requests.isEmpty())
        } finally { source.stop(); scope.cancel() }
    }

    /** 两项齐备才配置；关闭开发者模式后立即退回未配置（供上层停连接）。 */
    @Test fun isConfiguredTracksBothGates() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sockets = Sockets()
        var dev = true
        val source = SimulatedSource(
            scope, OkHttpClient(), { null }, { IntensityStandard.CSIS },
            devModeProvider = { dev }, socketFactory = sockets,
        )
        try {
            assertFalse(source.isConfigured())          // 无地址
            source.setUrl("ws://10.0.2.2:8080/ws")
            assertTrue(source.isConfigured())           // 两者齐备
            dev = false
            assertFalse(source.isConfigured())          // 翻转开关即刻生效
        } finally { source.stop(); scope.cancel() }
    }

    /** ws:// 局域网地址必须可用。OkHttp 内部把 ws 改写为 http，故只校验 host/path。 */
    @Test fun configuredSourceConnectsWithPlainWsUrl() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sockets = Sockets()
        val source = source(scope, sockets)
        try {
            source.setUrl("ws://10.0.2.2:8080/ws")
            source.start()
            val request = sockets.requests.single()
            // 不得对 wss:// 调用 toHttpUrl()（会抛异常）；这里能建连即是证明。
            assertEquals("10.0.2.2", request.url.host)
            assertEquals(8080, request.url.port)
            assertEquals("/ws", request.url.encodedPath)
        } finally { source.stop(); scope.cancel() }
    }

    // ── 时间窗口：三道闸门 ────────────────────────────────────────

    /** 超过 30 分钟活跃窗口的实时帧必须丢弃（服务端会重放「最近一次」）。 */
    @Test fun staleLiveFrameIsDropped() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sockets = Sockets()
        val source = source(scope, sockets)
        val events = mutableListOf<SourceEvent>()
        val latch = CountDownLatch(1)
        val job = scope.launch { source.events.toList(events) }
        try {
            val (_, listener) = started(scope, sockets, source)
            val old = System.currentTimeMillis() - 31 * 60 * 1000L
            listener.onMessage(sockets.connections[0].first, frame(originTime = old))
            latch.await(300, TimeUnit.MILLISECONDS)
            assertTrue("超龄实时帧不应产生事件", events.isEmpty())
        } finally { source.stop(); scope.cancel(); job.cancel() }
    }

    /** 同一时刻的目录帧不受活跃窗口约束：只有 Live 受限。 */
    @Test fun staleDirectoryFrameIsKept() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sockets = Sockets()
        val source = source(scope, sockets)
        val events = mutableListOf<SourceEvent>()
        val job = scope.launch { source.events.toList(events) }
        try {
            val (_, listener) = started(scope, sockets, source)
            val old = System.currentTimeMillis() - 31 * 60 * 1000L
            listener.onMessage(sockets.connections[0].first, frame(type = "directory", originTime = old))
            assertEquals(1, events.size)
            assertEquals(SourceEventKind.DIRECTORY, events[0].kind)
        } finally { source.stop(); scope.cancel(); job.cancel() }
    }

    /** 发震时刻超出现在 +60s 的帧不得进入链路：它一出生就判过期。 */
    @Test fun futureOriginBeyond60sIsNotEmitted() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sockets = Sockets()
        val source = source(scope, sockets)
        val events = mutableListOf<SourceEvent>()
        val job = scope.launch { source.events.toList(events) }
        try {
            val (_, listener) = started(scope, sockets, source)
            val future = System.currentTimeMillis() + 5 * 60 * 1000L
            listener.onMessage(sockets.connections[0].first, frame(originTime = future))
            assertTrue("未来时刻的帧不应产生事件", events.isEmpty())
        } finally { source.stop(); scope.cancel(); job.cancel() }
    }

    // ── 会话守卫 ─────────────────────────────────────────────────

    /** stop 之后在途连接送来的帧必须被丢弃（generation 令牌）。 */
    @Test fun eventsAfterStopAreRejected() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sockets = Sockets()
        val source = source(scope, sockets)
        val events = mutableListOf<SourceEvent>()
        val job = scope.launch { source.events.toList(events) }
        val (socket, listener) = started(scope, sockets, source)
        try {
            source.stop()
            listener.onMessage(socket, frame())
            assertTrue("stop 后不应再产生事件", events.isEmpty())
            assertEquals(ConnectionStatus.DISCONNECTED, source.status.value.status)
        } finally { source.stop(); scope.cancel(); job.cancel() }
    }

    /** 传输失败后按 3–15s 退避重连，且握手次数随之增加。 */
    @Test fun reconnectBackoffIsBounded() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sockets = Sockets()
        val source = source(scope, sockets)
        try {
            val (socket, listener) = started(scope, sockets, source)
            listener.onFailure(socket, IllegalStateException("down"), null)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (sockets.requests.size < 2 && System.nanoTime() < deadline) Thread.sleep(20)
            assertEquals("退避后应重连", 2, sockets.requests.size)
        } finally { source.stop(); scope.cancel() }
    }

    // ── 解析与合并键 ─────────────────────────────────────────────

    /** 机构固定为 SIM：与真实 CENC 报文不得落进同一合并键，否则告警被静默吞掉。 */
    @Test fun agencySimNeverCollidesWithRealSources() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sockets = Sockets()
        val source = source(scope, sockets)
        val events = mutableListOf<SourceEvent>()
        val job = scope.launch { source.events.toList(events) }
        try {
            val (_, listener) = started(scope, sockets, source)
            listener.onMessage(sockets.connections[0].first, frame())
            val sim = events.single().event
            assertEquals("SIM", sim.sourceAgency)
            assertEquals("Simulated", sim.sourceProvider)
            assertEquals("sim_sim-1-a", sim.id)
            assertTrue("eventId 应落在 sim- 命名空间", sim.eventId.startsWith("sim-"))
            // 与一条形状相同的真实 CENC 报文比对：id 相同，identity 必须不同。
            val cenc = sim.copy(
                id = "wolfx_CD.1", eventId = "CD.1",
                sourceAgency = "CENC", sourceProvider = "Wolfx",
            )
            assertNotEquals("机构不同才不会撞合并键", cenc.identity, sim.identity)
            // 反向对照：同机构同 id 确实会合并（证明上面的差异来自机构而非巧合）。
            assertEquals(cenc.identity, cenc.copy(sourceProvider = "Pancakes").identity)
        } finally { source.stop(); scope.cancel(); job.cancel() }
    }

    /** 多报次递增、末报 final，且各报共用一个 identity（EventGate 靠它归并）。 */
    @Test fun multiReportIncrementsReportNumAndFinalFlag() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sockets = Sockets()
        val source = source(scope, sockets)
        val events = mutableListOf<SourceEvent>()
        val job = scope.launch { source.events.toList(events) }
        try {
            val (_, listener) = started(scope, sockets, source)
            val socket = sockets.connections[0].first
            val origin = System.currentTimeMillis()
            listener.onMessage(socket, frame(reportNum = 1, originTime = origin))
            listener.onMessage(socket, frame(reportNum = 2, originTime = origin))
            listener.onMessage(socket, frame(reportNum = 3, originTime = origin, extra = mapOf("isFinal" to true)))
            assertEquals(3, events.size)
            assertEquals(listOf(1, 2, 3), events.map { it.event.reportNum })
            assertEquals(listOf(false, false, true), events.map { it.event.isFinal })
            assertEquals(1, events.map { it.event.identity }.distinct().size)
        } finally { source.stop(); scope.cancel(); job.cancel() }
    }

    /** type 决定 kind：目录帧不得进入告警链路。 */
    @Test fun typeMapsToEventKind() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sockets = Sockets()
        val source = source(scope, sockets)
        val events = mutableListOf<SourceEvent>()
        val job = scope.launch { source.events.toList(events) }
        try {
            val (_, listener) = started(scope, sockets, source)
            val socket = sockets.connections[0].first
            listener.onMessage(socket, frame(type = "report"))
            listener.onMessage(socket, frame(eventId = "sim-2-b", type = "directory"))
            assertEquals(listOf(SourceEventKind.LIVE, SourceEventKind.DIRECTORY), events.map { it.kind })
            assertEquals("模拟数据源 地震预警", events[0].event.source)
            assertEquals("模拟数据源 地震情报", events[1].event.source)
        } finally { source.stop(); scope.cancel(); job.cancel() }
    }

    /** hello / ping 是控制帧：只刷新心跳，不得产生事件。 */
    @Test fun controlFramesProduceNoEvents() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sockets = Sockets()
        val source = source(scope, sockets)
        val events = mutableListOf<SourceEvent>()
        val job = scope.launch { source.events.toList(events) }
        try {
            val (_, listener) = started(scope, sockets, source)
            val socket = sockets.connections[0].first
            listener.onMessage(socket, """{"type":"hello","server":"sim-eew/1","agency":"SIM"}""")
            listener.onMessage(socket, """{"type":"ping","epoch":${System.currentTimeMillis()}}""")
            assertTrue(events.isEmpty())
        } finally { source.stop(); scope.cancel(); job.cancel() }
    }

    /** 畸形帧忽略而非致命：连接必须保持，断线重连不该被一条坏帧触发。 */
    @Test fun malformedFrameIsIgnoredAndSocketStaysOpen() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sockets = Sockets()
        val source = source(scope, sockets)
        val events = mutableListOf<SourceEvent>()
        val job = scope.launch { source.events.toList(events) }
        try {
            val (socket, listener) = started(scope, sockets, source)
            listener.onMessage(socket, """{"type":"report","eventId":""")   // 截断
            listener.onMessage(socket, """{"type":"report"}""")               // 缺必填
            listener.onMessage(socket, """{"type":"report","eventId":"x","originTime":1}""") // 缺震中
            listener.onMessage(socket, frame())                              // 随后一条正常帧
            assertEquals("坏帧应被跳过，正常帧仍应生效", 1, events.size)
            assertFalse("坏帧不应断连", socket.canceled)
        } finally { source.stop(); scope.cancel(); job.cancel() }
    }

    /** 烈度文本原文透传：JMA 式写法经 parseMaxIntensity 会被重新格式化而破坏。 */
    @Test fun maxIntensityTextIsPreservedVerbatim() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sockets = Sockets()
        val source = source(scope, sockets)
        val events = mutableListOf<SourceEvent>()
        val job = scope.launch { source.events.toList(events) }
        try {
            val (_, listener) = started(scope, sockets, source)
            listener.onMessage(sockets.connections[0].first,
                frame(extra = mapOf("maxIntensity" to 5.0, "maxIntensityText" to "5弱")))
            val e = events.single().event
            assertEquals("5弱", e.maxIntensityText)
            assertEquals(5.0, e.maxIntensityRaw, 1e-6)
        } finally { source.stop(); scope.cancel(); job.cancel() }
    }

    /** originTime 必须是 epoch 毫秒：解析不得把它当秒放大，也不得按本地时区偏移。 */
    @Test fun originTimeIsReadAsEpochMilliseconds() {
        val parsed = SimulatedParser.parseReport(
            JSONObject(frame(originTime = 1_760_000_045_000L)),
            SourceEventKind.LIVE, null, IntensityStandard.CSIS,
        )
        assertEquals(1_760_000_045_000L, parsed?.timestamp)
        // 缺震中/经度时，0/0 会被误当成几内亚湾的合法坐标，故必须拒绝。
        assertNull(
            SimulatedParser.parseReport(
                JSONObject().put("eventId", "x").put("originTime", 1_760_000_045_000L),
                SourceEventKind.LIVE, null, IntensityStandard.CSIS,
            )
        )
    }

    /** 无定位时派生字段按「未知」处理，等级仅按震级派生。 */
    @Test fun withoutLocationDerivedFieldsAreUnknown() {
        val e = SimulatedParser.parseReport(
            JSONObject(frame()), SourceEventKind.LIVE, null, IntensityStandard.CSIS,
        )!!
        assertEquals(EewParser.UNKNOWN_DISTANCE, e.distanceKm, 1e-6)
        assertEquals("--", e.estimatedIntensity)
        assertNull(e.pWaveArrival)
        assertNull(e.sWaveArrival)
    }
}
