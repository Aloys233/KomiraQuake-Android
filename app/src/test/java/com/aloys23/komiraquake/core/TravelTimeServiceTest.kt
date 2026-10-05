package com.aloys23.komiraquake.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TravelTimeServiceTest {
    @Before
    fun setUp() {
        // 2x2 微型表便于手算
        TravelTimeService.loadFromString(
            """
            {"t":{
              "depths":[0,10],
              "distances":[0,100],
              "p_times":[[0,10],[0,20]],
              "s_times":[[0,20],[0,40]]
            }}
            """.trimIndent(),
        )
    }

    @Test
    fun bilinearInterpolationP() {
        assertEquals(5.0, TravelTimeService.estimateTravelTimes(0.0, 50.0, "t").first, 1e-9)
        assertEquals(7.5, TravelTimeService.estimateTravelTimes(5.0, 50.0, "t").first, 1e-9)
        assertEquals(10.0, TravelTimeService.estimateTravelTimes(10.0, 50.0, "t").first, 1e-9)
    }

    @Test
    fun bilinearInterpolationS() {
        assertEquals(10.0, TravelTimeService.estimateTravelTimes(0.0, 50.0, "t").second, 1e-9)
        assertEquals(15.0, TravelTimeService.estimateTravelTimes(5.0, 50.0, "t").second, 1e-9)
    }

    @Test
    fun clampsBeyondBounds() {
        assertEquals(10.0, TravelTimeService.estimateTravelTimes(0.0, 9999.0, "t").first, 1e-9)
        assertEquals(0.0, TravelTimeService.estimateTravelTimes(0.0, -5.0, "t").first, 1e-9)
    }

    @Test
    fun inverseDistanceForTime() {
        // depth 0, distance 100 -> p 10s；求 10s 对应距离应为 100
        assertEquals(100.0, TravelTimeService.distanceForTime(0.0, 10.0, true, "t"), 1e-6)
        // 5s 对应 50km
        assertEquals(50.0, TravelTimeService.distanceForTime(0.0, 5.0, true, "t"), 1e-6)
    }

    @Test
    fun unknownTableFallsBackToConstantVelocity() {
        val (p, _) = TravelTimeService.estimateTravelTimes(0.0, 60.0, "missing")
        assertEquals(10.0, p, 1e-9)
    }

    @Test
    fun realAssetLoads() {
        val raw = requireNotNull(javaClass.classLoader?.getResourceAsStream(TravelTimeService.ASSET_PATH)) {
            "Production travel-time asset must be available to unit tests"
        }
        raw.bufferedReader().use { TravelTimeService.loadFromString(it.readText()) }
        assertTrue(TravelTimeService.availableTables.contains("jma2001"))
        val travel = TravelTimeService.estimateTravelTimes(10.0, 100.0, "jma2001")
        assertTrue(travel.first > 0 && travel.second > travel.first)
        assertEquals(100.0, TravelTimeService.distanceForTime(10.0, travel.second, false, "jma2001"), 1.0)
        assertEquals(0.0, TravelTimeService.distanceForTime(10.0, -1.0, false, "jma2001"), 0.01)
    }
}
