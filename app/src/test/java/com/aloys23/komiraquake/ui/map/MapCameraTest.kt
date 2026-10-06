package com.aloys23.komiraquake.ui.map

import org.junit.Assert.assertEquals
import org.junit.Test

class MapCameraTest {
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
