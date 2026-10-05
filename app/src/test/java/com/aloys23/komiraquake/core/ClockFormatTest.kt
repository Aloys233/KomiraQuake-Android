package com.aloys23.komiraquake.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/**
 * 校时时钟的展示格式。《NATIVE_PORT_SPEC》 §12：固定 UTC+8，与设备时区无关。
 */
class ClockFormatTest {

    private lateinit var original: TimeZone

    @Before
    fun setUp() {
        original = TimeZone.getDefault()
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(original)
    }

    @Test
    fun matchesKnownUtc8Instant() {
        // 1790587107186 ms 是实测到的 api.wolfx.jp/ntp.json 返回值：
        // 同一响应里 CST(UTC+8) = "2026-09-28 17:18:27"、JST(UTC+9) = "2026-09-28 18:18:27"。
        assertEquals("2026-09-28 17:18:27", ClockFormat.utc8Stamp(1_790_587_107_186L))
    }

    @Test
    fun formatIgnoresDeviceTimeZone() {
        val epochMs = 1_790_587_107_186L
        val zones = listOf("GMT+08:00", "GMT+09:00", "GMT-05:00", "UTC", "GMT+05:30")
        val rendered = zones.map { zone ->
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            ClockFormat.utc8Stamp(epochMs)
        }
        // 设备时区无论如何变化，输出必须稳定为同一串 UTC+8 时刻。
        assertEquals(1, rendered.distinct().size)
        assertEquals("2026-09-28 17:18:27", rendered.first())
    }

    @Test
    fun midnightBoundaryRollsDateForward() {
        // 2026-09-28 16:00:00 UTC → UTC+8 已是次日 00:00:00
        assertEquals("2026-09-29 00:00:00", ClockFormat.utc8Stamp(1_790_611_200_000L))
    }
}
