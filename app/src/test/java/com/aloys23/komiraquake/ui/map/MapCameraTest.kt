package com.aloys23.komiraquake.ui.map

import com.aloys23.komiraquake.model.EarthquakeEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapCameraTest {
    private fun event(latitude: Double = 35.0, timestamp: Long = 1_000L) = EarthquakeEvent(
        id = "event-1",
        eventId = "event-1",
        magnitude = 5.0,
        latitude = latitude,
        longitude = 105.0,
        depth = 10.0,
        location = "测试震中",
        timestamp = timestamp,
        source = "test",
        distanceKm = 0.0,
        estimatedIntensity = "I",
    )

    @Test
    fun cameraRefocusesWhenSameEventReceivesUpdatedCoordinates() {
        val before = focusCameraKey(event(), request = 1L, hasFocus = true,
            widthPx = 1080f, heightPx = 2000f, topOcclusionPx = 280f)
        val after = focusCameraKey(event(latitude = 36.0), request = 1L, hasFocus = true,
            widthPx = 1080f, heightPx = 2000f, topOcclusionPx = 280f)

        assertNotEquals(before, after)
    }

    @Test
    fun cameraRefocusesWhenViewportInsetsSettle() {
        val before = focusCameraKey(event(), request = 1L, hasFocus = true,
            widthPx = 0f, heightPx = 0f, topOcclusionPx = 280f)
        val after = focusCameraKey(event(), request = 1L, hasFocus = true,
            widthPx = 1080f, heightPx = 2000f, topOcclusionPx = 520f)

        assertNotEquals(before, after)
    }

    @Test
    fun userLocationFocusUsesCloserZoomThanOverview() {
        assertTrue(USER_LOCATION_ZOOM > 6f)
    }

    @Test
    fun noWavesFallsBackTo300Km() {
        // P/S 全部隐藏（历史事件或已离开中国范围）时用固定 300 km。
        assertEquals(300.0, focusRadiusKm(-1.0, -1.0), 0.0)
    }

    @Test
    fun tinyWavesClampTo100KmFloor() {
        assertEquals(100.0, focusRadiusKm(2.0, 5.0), 0.0)
        assertEquals(100.0, focusRadiusKm(-1.0, 30.0), 0.0)
    }

    @Test
    fun followsLargerWave() {
        assertEquals(520.0, focusRadiusKm(400.0, 520.0), 0.0)
        assertEquals(520.0, focusRadiusKm(520.0, 400.0), 0.0)
    }
}
