package com.aloys23.komiraquake.data.source.wolfx

import com.aloys23.komiraquake.core.IntensityStandard
import com.aloys23.komiraquake.data.source.SourceEvent
import com.aloys23.komiraquake.model.ConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import okhttp3.Call
import okhttp3.Callback
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WolfxSourceTest {
    private class Socket : WebSocket {
        var canceled = false
        val sent = mutableListOf<String>()
        override fun request() = Request.Builder().url("https://example.com").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { sent.add(text); return true }
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { canceled = true }
    }
    private class Sockets : WebSocket.Factory {
        val connections = mutableListOf<Pair<Socket, WebSocketListener>>()
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
            Socket().also { connections.add(it to listener) }
    }
    private fun response(request: Request) = Response.Builder().request(request)
        .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(
            if (request.url.toString().contains("jma_eqlist")) {
                """{"No1":{"EventID":"20261006134727","time_full":"2026/10/06 13:47:00","location":"熊本県熊本地方","magnitude":"2.9","shindo":"1","depth":"10km","latitude":"32.6","longitude":"130.7"}}"""
                    .toResponseBody()
            } else {
                """{"No1":{"id":"catalog-id","magnitude":4.5,"latitude":30,"longitude":100}}""".toResponseBody()
            },
        ).build()

    /** Ignores cancel intentionally, modeling a response already in flight at disable time. */
    private inner class Calls : Call.Factory {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        override fun newCall(request: Request): Call = object : Call {
            override fun request() = request
            override fun execute(): Response {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return response(request).also { returned.countDown() }
            }
            override fun enqueue(responseCallback: Callback) = error("Not used")
            override fun cancel() = Unit
            override fun isExecuted() = false
            override fun isCanceled() = false
            override fun timeout() = Timeout.NONE
            override fun clone(): Call = newCall(request)
        }
    }

    @Test fun disabledLocationAndRefreshDoNotStartNetworking() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sockets = Sockets()
        val source = WolfxSource(scope, OkHttpClient(), { null }, { IntensityStandard.values().first() }, sockets, Calls())
        try {
            source.onLocationChanged()
            source.refreshDirectory()
            assertTrue(sockets.connections.isEmpty())
            assertEquals(ConnectionStatus.DISCONNECTED, source.status.value.status)
        } finally { source.stop(); scope.cancel() }
    }

    @Test fun callbacksFromDisabledAndPreviousSessionCannotChangeHealthOrSendPong() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sockets = Sockets()
        val calls = Calls()
        val source = WolfxSource(scope, OkHttpClient(), { null }, { IntensityStandard.values().first() }, sockets, calls)
        try {
            source.start()
            assertTrue(calls.entered.await(5, TimeUnit.SECONDS))
            val (old, listener) = sockets.connections.single()
            source.stop()
            listener.onOpen(old, response(old.request()))
            listener.onMessage(old, """{"type":"heartbeat"}""")
            listener.onFailure(old, IllegalStateException("late"), null)
            assertEquals(ConnectionStatus.DISCONNECTED, source.status.value.status)
            assertTrue(old.sent.isEmpty())
            source.start()
            listener.onClosed(old, 1000, "late close")
            listener.onMessage(old, """{"type":"heartbeat"}""")
            assertEquals(ConnectionStatus.CONNECTING, source.status.value.status)
            assertTrue(old.sent.isEmpty())
            assertEquals(2, sockets.connections.size)
        } finally { source.stop(); calls.release.countDown(); scope.cancel() }
    }

    @Test fun emittedEventIsRejectedAfterStopAndRestart() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = Calls()
        val source = WolfxSource(scope, OkHttpClient(), { null }, { IntensityStandard.CSIS }, Sockets(), calls)
        val delivered = CountDownLatch(1)
        var event: SourceEvent? = null
        scope.launch { source.events.collect { event = it; delivered.countDown() } }
        try {
            source.start()
            calls.release.countDown()
            assertTrue(delivered.await(5, TimeUnit.SECONDS))
            val queued = checkNotNull(event)
            assertTrue(source.isCurrent(queued))
            source.stop()
            assertFalse(source.isCurrent(queued))
            source.start()
            assertFalse(source.isCurrent(queued))
        } finally { source.stop(); calls.release.countDown(); scope.cancel() }
    }

    @Test fun successfulCatalogDoesNotHealFailedWebSocket() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sockets = Sockets()
        val calls = Calls()
        val source = WolfxSource(scope, OkHttpClient(), { null }, { IntensityStandard.values().first() }, sockets, calls)
        try {
            source.start()
            assertTrue(calls.entered.await(5, TimeUnit.SECONDS))
            val (socket, listener) = sockets.connections.single()
            listener.onFailure(socket, IllegalStateException("offline"), null)
            calls.release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (source.status.value.directoryStatus != ConnectionStatus.CONNECTED && System.nanoTime() < deadline) Thread.yield()
            assertEquals(ConnectionStatus.CONNECTED, source.status.value.directoryStatus)
            assertNotNull(source.status.value.directoryLastSuccessAt)
            assertEquals(ConnectionStatus.ERROR, source.status.value.status)
            assertNull(source.status.value.lastHeartbeat)
        } finally { source.stop(); calls.release.countDown(); scope.cancel() }
    }
}
