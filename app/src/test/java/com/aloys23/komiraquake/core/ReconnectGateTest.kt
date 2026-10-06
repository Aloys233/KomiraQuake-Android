package com.aloys23.komiraquake.core

import com.aloys23.komiraquake.data.source.awaitReconnectDelay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网络感知重连的行为约定。这是手机端独有逻辑：桌面端为固定有线网络，不存在
 * 「断网期间退避空转、恢复后预警仍迟到」的问题。
 */
class ReconnectGateTest {

    private class FakeGate(initiallyOnline: Boolean) : NetworkGate {
        private val state = MutableStateFlow(initiallyOnline)
        override val online = state
        fun set(value: Boolean) { state.value = value }
        override suspend fun awaitOnline(): Boolean {
            if (state.value) return false
            state.first { it }
            return true
        }
    }

    /** 在线时返回 false，调用方照常走退避。 */
    @Test fun onlineReportsNoResumeSoBackoffStillApplies() {
        val gate = FakeGate(true)
        var resumed: Boolean? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scope.launch { resumed = awaitReconnectDelay(gate, 0L) }
        assertEquals(false, resumed)
        scope.cancel()
    }

    /** 断网时挂起（不空转），恢复后返回 true 让调用方复位退避与站点索引。 */
    @Test fun offlineSuspendsUntilOnlineThenReportsResume() {
        val gate = FakeGate(false)
        var resumed: Boolean? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scope.launch { resumed = awaitReconnectDelay(gate, 60_000L) }
        assertNull("断网期间不应返回，也不应空转退避", resumed)

        gate.set(true)
        assertEquals(true, resumed)
        scope.cancel()
    }

    /**
     * 挂起不得退化为「无限重试」：一次恢复只报告一次 true，调用方据此重连；
     * 多次 true 会导致重复连接。
     */
    @Test fun resumeIsReportedExactlyOncePerRecovery() {
        val gate = FakeGate(false)
        val resumes = mutableListOf<Boolean>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scope.launch { resumes += awaitReconnectDelay(gate, 60_000L) }
        gate.set(true)
        gate.set(false)
        gate.set(true)
        assertEquals(listOf(true), resumes)
        scope.cancel()
    }

    /** 默认实现必须完全不阻塞，退化为各源原有的固定退避。 */
    @Test fun alwaysOnlineGateNeverBlocks() {
        assertTrue(NetworkGate.AlwaysOnline.online.value)
        var resumed: Boolean? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scope.launch { resumed = awaitReconnectDelay(NetworkGate.AlwaysOnline, 0L) }
        assertEquals(false, resumed)
        scope.cancel()
    }
}
