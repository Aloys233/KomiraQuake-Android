package com.aloys23.komiraquake.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntensityCalculatorTest {
    @Test
    fun rawCsisIncreasesWithMagnitude() {
        val weak = IntensityCalculator.rawCsis(3.0, 100.0, 10.0)
        val strong = IntensityCalculator.rawCsis(6.0, 100.0, 10.0)
        assertTrue("weak=$weak strong=$strong", strong > weak)
    }

    @Test
    fun rawCsisZeroMagnitudeIsZero() {
        assertEquals(0.0, IntensityCalculator.rawCsis(0.0, 10.0, 10.0), 1e-9)
    }

    @Test
    fun rawCsisClampedAtZero() {
        assertEquals(0.0, IntensityCalculator.rawCsis(1.0, 5000.0, 10.0), 1e-9)
    }

    @Test
    fun rawCsisFollowsCeaAttenuation() {
        // CEA-CSIS 衰减关系（kanameishi calcCsis）固定值。
        assertEquals(4.2749, IntensityCalculator.rawCsis(6.0, 100.0, 10.0), 1e-3)
        assertEquals(9.3048, IntensityCalculator.rawCsis(7.0, 0.0, 10.0), 1e-3)
    }

    @Test
    fun formatCsisRomanNumerals() {
        assertEquals("0", IntensityCalculator.formatCsis(0.2))
        assertEquals("I", IntensityCalculator.formatCsis(1.4))
        assertEquals("IV", IntensityCalculator.formatCsis(4.2))
        assertEquals("XII", IntensityCalculator.formatCsis(20.0))
    }

    @Test
    fun jmaBands() {
        // 用极端输入触发各档位边界附近的行为
        assertEquals("7", IntensityCalculator.formatJma(9.0, 1.0, 1.0))
        assertTrue(IntensityCalculator.formatJma(2.0, 400.0, 10.0) in setOf("0", "1"))
    }
}
