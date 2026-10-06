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
    fun distanceForCsisReturnsThresholdCrossing() {
        val r = IntensityCalculator.distanceForCsis(6.0, 10.0, 1.0)
        assertTrue("r=$r", r > 0.0)
        // 交点处烈度应恰好落在阈值附近。
        assertEquals(1.0, IntensityCalculator.rawCsis(6.0, r, 10.0), 0.02)
    }

    @Test
    fun distanceForCsisGrowsWithMagnitudeAndZeroForTiny() {
        val m4 = IntensityCalculator.distanceForCsis(4.0, 10.0, 1.0)
        val m7 = IntensityCalculator.distanceForCsis(7.0, 10.0, 1.0)
        assertTrue("m4=$m4 m7=$m7", m7 > m4)
        assertEquals(0.0, IntensityCalculator.distanceForCsis(0.0, 10.0, 1.0), 1e-9)
    }

    @Test
    fun waveOpacityFadesWithRadius() {
        val fade = 1000.0
        assertEquals(1.0, IntensityCalculator.waveOpacity(400.0, fade), 1e-9)   // ≤0.8·fade
        assertEquals(0.25, IntensityCalculator.waveOpacity(fade, fade), 1e-9)   // 影响半径处
        assertEquals(0.0, IntensityCalculator.waveOpacity(10000.0, fade), 1e-9) // 硬上限
        assertEquals(0.0, IntensityCalculator.waveOpacity(12000.0, fade), 1e-9)
        assertTrue(IntensityCalculator.waveOpacity(900.0, fade) > IntensityCalculator.waveOpacity(1100.0, fade))
    }

    @Test
    fun waveFillOpacityOnlyInsideInfluenceRadius() {
        val fade = 1000.0
        assertEquals(0.25, IntensityCalculator.waveFillOpacity(200.0, fade), 1e-9)  // ≤0.8·fade
        assertEquals(0.0, IntensityCalculator.waveFillOpacity(fade, fade), 1e-9)    // 影响半径处归零
        assertEquals(0.0, IntensityCalculator.waveFillOpacity(1500.0, fade), 1e-9)  // 超出不填充
    }

    @Test
    fun jmaBands() {
        // 用极端输入触发各档位边界附近的行为
        assertEquals("7", IntensityCalculator.formatJma(9.0, 1.0, 1.0))
        assertTrue(IntensityCalculator.formatJma(2.0, 400.0, 10.0) in setOf("0", "1"))
    }
}
