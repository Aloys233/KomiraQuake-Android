package com.aloys23.komiraquake.data.source.whews

import com.aloys23.komiraquake.core.AppClock
import com.aloys23.komiraquake.core.IntensityStandard
import com.aloys23.komiraquake.data.source.SourceEvent
import com.aloys23.komiraquake.model.ConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WhewsSourceTest {
    private class Socket : WebSocket {
        var canceled = false
        override fun request() = Request.Builder().url("https://example.com").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteString) = true
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
        .protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .body("{}".toResponseBody()).build()

    private fun source(scope: CoroutineScope, sockets: Sockets) = WhewsSource(
        scope, OkHttpClient(), { null }, { IntensityStandard.CSIS }, sockets,
    )

    /** 无令牌即未配置：上层不启动本源，源自身也不得建立任何连接。 */
    @Test fun unconfiguredSourceNeverConnects() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sockets = Sockets()
        val source = source(scope, sockets)
        try {
            assertFalse(source.isConfigured())
            source.start()
            source.refreshDirectory()
            assertTrue(sockets.requests.isEmpty())
            assertEquals(ConnectionStatus.DISCONNECTED, source.status.value.status)
        } finally { source.stop(); scope.cancel() }
    }

    /** 首个尝试必须指向国内备用站 2v8.cn，且令牌以 ?token= 附加在握手。 */
    @Test fun firstAttemptTargetsDomesticBackupSiteWithToken() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sockets = Sockets()
        val source = source(scope, sockets)
        try {
            source.setToken("wat_secret")
            assertTrue(source.isConfigured())
            source.start()
            val request = sockets.requests.single()
            // OkHttp 的 Request.Builder.url(String) 接受 ws/wss，但内部会把 wss 改写为
            // https（同一 TLS 通道）；故此处校验 host/path/token，而非 scheme。
            assertEquals("api.2v8.cn", request.url.host)
            assertEquals("/ws/all", request.url.encodedPath)
            assertEquals("wat_secret", request.url.queryParameter("token"))
        } finally { source.stop(); scope.cancel() }
    }

    /** 国内站传输失败后才轮换到主站；连通后不回落（再次失败才继续轮换）。 */
    @Test fun transportFailureRotatesToMainSite() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sockets = Sockets()
        val source = source(scope, sockets)
        try {
            source.setToken("wat_secret")
            source.start()
            val (first, listener) = sockets.connections.single()
            listener.onFailure(first, IllegalStateException("domestic down"), null)
            // 退避后重连；Unconfined + delay(3s) 下等待第二次握手出现。
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (sockets.requests.size < 2 && System.nanoTime() < deadline) Thread.sleep(20)
            assertEquals("api.beecld.com", sockets.requests[1].url.host)
            // 成功握手后不回落到国内站：再失败一次才轮换回索引 0。
            val (second, listener2) = sockets.connections[1]
            listener2.onFailure(second, IllegalStateException("main down"), null)
            while (sockets.requests.size < 3 && System.nanoTime() < deadline) Thread.sleep(20)
            assertEquals("api.2v8.cn", sockets.requests[2].url.host)
        } finally { source.stop(); scope.cancel() }
    }

    /** 4401/4403 为服务端明确拒绝：按文档停止重连，避免重连过频被智能封禁。 */
    @Test fun authRejectionStopsReconnecting() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sockets = Sockets()
        val source = source(scope, sockets)
        try {
            source.setToken("wat_bad")
            source.start()
            val (socket, listener) = sockets.connections.single()
            listener.onOpen(socket, response(socket.request()))
            listener.onClosed(socket, WhewsProtocol.CloseCode.UNAUTHORIZED, "unauthorized")
            assertEquals(ConnectionStatus.ERROR, source.status.value.status)
            assertTrue(source.status.value.description.contains("鉴权"))
            // 不应再有新的握手尝试。
            Thread.sleep(200)
            assertEquals(1, sockets.requests.size)
        } finally { source.stop(); scope.cancel() }
    }

    /** 首连快照为数组：应逐条解析并按频道区分 LIVE / DIRECTORY。 */
    @Test fun snapshotArrayIsParsedPerChannel() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sockets = Sockets()
        val source = source(scope, sockets)
        val delivered = CountDownLatch(2)
        val agencies = mutableListOf<String>()
        scope.launch {
            source.events.collect {
                synchronized(agencies) {
                    agencies += it.event.sourceAgency
                    delivered.countDown()
                }
            }
        }
        // EEW 频道带 30min 新鲜度窗口：发震时刻须取当下，否则会被当作回放丢弃。
        val now = AppClock.now()
        fun wallClock(offsetHours: Int) = Instant.ofEpochMilli(now)
            .atOffset(ZoneOffset.ofHours(offsetHours))
            .toLocalDateTime()
            .toString().replace("T", " ").substring(0, 19)
        try {
            source.setToken("wat_secret")
            source.start()
            val (socket, listener) = sockets.connections.single()
            listener.onOpen(socket, response(socket.request()))
            listener.onMessage(socket, """
                [
                  {"Data":{"id":"CD.20260813084717.000","shockTime":"${wallClock(8)}",
                    "latitude":36.06,"longitude":103.55,"depth":11.0,"magnitude":3.2,
                    "placeName":"甘肃临夏州永靖县"},"md5":"a","source":"cenc"},
                  {"Data":{"id":"20240101161010","shockTime":"${wallClock(9)}",
                    "latitude":37.5,"longitude":137.3,"depth":10.0,"magnitude":6.2,
                    "placeName":"石川県能登地方","epiIntensity":"6+"},"md5":"b","source":"jma_eew"}
                ]
            """.trimIndent())
            assertTrue(delivered.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("CENC", "JMA"), synchronized(agencies) { agencies.toList() })
        } finally { source.stop(); scope.cancel() }
    }

    /** 停止后排队的事件不得复活（会话令牌 fencing）。 */
    @Test fun emittedEventIsRejectedAfterStop() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sockets = Sockets()
        val source = source(scope, sockets)
        val delivered = CountDownLatch(1)
        var event: SourceEvent? = null
        scope.launch { source.events.collect { event = it; delivered.countDown() } }
        try {
            source.setToken("wat_secret")
            source.start()
            val (socket, listener) = sockets.connections.single()
            listener.onOpen(socket, response(socket.request()))
            listener.onMessage(
                socket,
                """{"Data":{"id":"CD.1","shockTime":"${Instant.ofEpochMilli(AppClock.now())
                    .atOffset(ZoneOffset.ofHours(8)).toLocalDateTime().toString()
                    .replace("T", " ").substring(0, 19)}","latitude":36.06,
                    "longitude":103.55,"magnitude":3.2},"md5":"a","source":"cenc"}""",
            )
            assertTrue(delivered.await(5, TimeUnit.SECONDS))
            val queued = checkNotNull(event)
            assertTrue(source.isCurrent(queued))
            source.stop()
            assertFalse(source.isCurrent(queued))
        } finally { source.stop(); scope.cancel() }
    }
}