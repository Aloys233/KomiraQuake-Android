package com.aloys23.komiraquake.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuakeCalculatorTest {
    @Test
    fun haversineBejingToShanghai() {
        // 北京 ~ 上海 直线距离约 1067 km
        val d = QuakeCalculator.haversineDistance(39.9042, 116.4074, 31.2304, 121.4737)
        assertTrue("distance=$d", d in 1050.0..1090.0)
    }

    @Test
    fun haversineNonFiniteReturnsZero() {
        assertEquals(0.0, QuakeCalculator.haversineDistance(Double.NaN, 0.0, 0.0, 0.0), 0.0)
    }

    @Test
    fun hypocenterDistanceCombines3d() {
        // sqrt(3^2 + 4^2) = 5
        assertEquals(5.0, QuakeCalculator.hypocenterDistance(3.0, 4.0), 1e-9)
    }

    @Test
    fun constantVelocityTravelTimes() {
        val (p, s) = QuakeCalculator.estimateTravelTimes(0.0, 60.0)
        assertEquals(10.0, p, 1e-9) // 60 / 6.0
        assertEquals(60.0 / 3.5, s, 1e-9)
    }

    @Test
    fun coordinateValidation() {
        assertTrue(QuakeCalculator.isValidCoordinate(30.0, 104.0))
        assertTrue(!QuakeCalculator.isValidCoordinate(95.0, 104.0))
    }

    @Test
    fun sameQuakeMatchesLiveAndCatalogReports() {
        // 2026-09-30 18:31 云南昆明：EEW 与目录发震时刻相同、震中相近。
        val t = 1_700_000_000_000L
        assertTrue(QuakeCalculator.isSameQuake(t, 25.088, 102.737, t, 25.09, 102.73))
    }

    @Test
    fun sameQuakeRejectsDifferentTimeOrPlace() {
        val t = 1_700_000_000_000L
        assertTrue(!QuakeCalculator.isSameQuake(t, 25.088, 102.737, t + 120_000, 25.09, 102.73))
        assertTrue(!QuakeCalculator.isSameQuake(t, 25.088, 102.737, t, 26.5, 102.73))
        assertTrue(!QuakeCalculator.isSameQuake(0L, 25.088, 102.737, t, 25.09, 102.73))
    }
}
