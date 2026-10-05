package com.aloys23.komiraquake.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** 单调锚定与状态机。《NATIVE_PORT_SPEC》 §13.4。 */
class ClockCoreTest {

    /** 可手动推进的假时钟。 */
    private class FakeTime(var monoMs: Long = 0L, var wallMs: Long = 1_700_000_000_000L) {
        fun mono(): Long = monoMs
        fun wall(): Long = wallMs
    }

    @After
    fun tearDown() {
        AppClock.resetForTest()
    }

    @Test
    fun localClockBeforeAnySync() {
        val time = FakeTime()
        val core = ClockCore(time::mono, time::wall)
        assertEquals(ClockState.LOCAL, core.state())
        assertEquals(0L, core.currentOffsetMs())
        assertEquals(time.wallMs, core.now())
    }

    @Test
    fun offsetIsAnchoredToMonotonicClock() {
        // 系统墙钟快 30 s：offset = −30000 应把 now() 拉回真实时间
        val time = FakeTime(monoMs = 10_000L, wallMs = 1_700_000_030_000L)
        val core = ClockCore(time::mono, time::wall)

        core.applyOffset(-30_000L, 42L, "SNTP ntp.aliyun.com")
        assertEquals(ClockState.SYNCED, core.state())
        assertEquals(1_700_000_000_000L, core.now())
        assertEquals(-30_000L, core.currentOffsetMs())
        assertEquals(42L, core.lastDelayMs)
        assertEquals("SNTP ntp.aliyun.com", core.sourceLabel)

        // 单调推进 5 s → now 前进 5 s
        time.monoMs += 5_000L
        assertEquals(1_700_000_005_000L, core.now())

        // 系统墙钟被改动 60 s：不影响 now()
        time.wallMs += 60_000L
        assertEquals(1_700_000_005_000L, core.now())

        // 墙钟被改回去同样不影响
        time.wallMs -= 120_000L
        assertEquals(1_700_000_005_000L, core.now())
    }

    @Test
    fun smallOffsetDifferenceIsTreatedAsNoise() {
        val time = FakeTime()
        val core = ClockCore(time::mono, time::wall)
        assertTrue(core.applyOffset(1_000L, 10L, "a"))
        assertFalse(core.applyOffset(1_030L, 10L, "b")) // 相差 30 ms < NOISE_MS
        assertEquals(1_000L, core.currentOffsetMs())
        assertTrue(core.applyOffset(1_080L, 10L, "c")) // 相差 80 ms ≥ NOISE_MS
        assertEquals(1_080L, core.currentOffsetMs())
    }

    @Test
    fun staleWhenMonotonicExceedsThresholdAndNowKeepsAnchoring() {
        val time = FakeTime()
        val core = ClockCore(time::mono, time::wall, staleAfterMs = 1_000L)
        core.applyOffset(0L, 5L, "x")
        assertEquals(ClockState.SYNCED, core.state())

        time.monoMs += 1_001L
        assertEquals(ClockState.STALE, core.state())
        // stale 只是 UI 告警：now() 仍走锚定值（基准墙钟 + 单调增量）
        assertEquals(1_700_000_001_001L, core.now())
    }

    @Test
    fun resetReturnsToLocalClock() {
        val time = FakeTime()
        val core = ClockCore(time::mono, time::wall)
        core.applyOffset(5_000L, 5L, "x")
        core.reset()
        assertEquals(ClockState.LOCAL, core.state())
        assertEquals(0L, core.currentOffsetMs())
        assertEquals(-1L, core.lastDelayMs)
        assertEquals(time.wallMs, core.now())
    }

    @Test
    fun appClockFallsBackToWallClockWhenDisabled() {
        AppClock.resetForTest()
        AppClock.applyOffset(-30_000L, 10L, "test")
        val corrected = AppClock.now()
        val wallNow = System.currentTimeMillis()
        assertTrue(abs((corrected - wallNow) + 30_000L) < 5_000L)

        AppClock.enable(false)
        val info = AppClock.info.value
        assertFalse(info.enabled)
        assertEquals(ClockState.LOCAL, info.state)
        // 关闭校时后回退本地墙钟
        assertTrue(abs(AppClock.now() - System.currentTimeMillis()) < 5_000L)
    }
}
