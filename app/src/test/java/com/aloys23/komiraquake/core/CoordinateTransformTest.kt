package com.aloys23.komiraquake.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinateTransformTest {
    @Test
    fun outsideChinaIsIdentity() {
        val (lat, lng) = CoordinateTransform.wgs84ToGcj02(35.0, 139.0) // 东京
        assertEquals(35.0, lat, 1e-9)
        assertEquals(139.0, lng, 1e-9)
    }

    @Test
    fun insideChinaShiftsButRoundTrips() {
        val lat = 30.6586
        val lng = 104.0648 // 成都
        val (gLat, gLng) = CoordinateTransform.wgs84ToGcj02(lat, lng)
        val shift = kotlin.math.abs(gLat - lat) + kotlin.math.abs(gLng - lng)
        assertEquals(true, shift > 0.0 && shift < 1.0)
        val (bLat, bLng) = CoordinateTransform.gcj02ToWgs84(gLat, gLng)
        assertEquals(lat, bLat, 1e-5)
        assertEquals(lng, bLng, 1e-5)
    }
}
