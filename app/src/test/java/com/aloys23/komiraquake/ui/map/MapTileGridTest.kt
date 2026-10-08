package com.aloys23.komiraquake.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 瓦片网格只在跨过瓦片边界或整数缩放层级时变化。这是拖动/捏合不再每帧重建瓦片列表、
 * 重启加载副作用的前提（对齐桌面端 MapView.qml 的 tileModel 增量同步）。
 */
class MapTileGridTest {

    private fun grid(
        lat: Double = 35.0,
        lon: Double = 105.0,
        zoom: Float = 6f,
        w: Float = 1080f,
        h: Float = 2000f,
        maxZoom: Int = 18,
        margin: Int = 2,
    ) = tileGrid(lat, lon, zoom, w, h, maxZoom, margin)

    @Test
    fun subTilePanKeepsGridEqual() {
        // 0.0005° ≈ 0.02px（z6 下 1° ≈ 45.5px），远小于一格，网格必须完全相等。
        val a = grid(lon = 105.0)
        val b = grid(lon = 105.0005)
        val c = grid(lat = 35.0005)
        assertEquals(a, b)
        assertEquals(a, c)
    }

    @Test
    fun crossingTileBoundaryChangesGrid() {
        // 6° ≈ 273px > 256px，必然跨过一整格，网格必须变化。
        assertNotEquals(grid(lon = 105.0), grid(lon = 111.0))
    }

    @Test
    fun zoomWithinSameIntegerLevelKeepsSameLevel() {
        // 6.1 / 6.4 都取整到 6 层；分数级缩放会改变可见瓦片范围，但层级不变。
        assertEquals(6, grid(zoom = 6.1f).z)
        assertEquals(6, grid(zoom = 6.4f).z)
        // 6.6 取整到 7，层级改变。
        assertEquals(7, grid(zoom = 6.6f).z)
    }

    @Test
    fun coversViewportWithPrefetchMargin() {
        val g = grid(zoom = 6f, w = 1080f, h = 2000f, margin = 2)
        assertEquals(6, g.z)
        assertEquals(64, g.n)
        // 视口 1080/256 ≈ 4.2 列、2000/256 ≈ 7.8 行，各加两侧 2 圈余量。
        assertTrue(g.maxX - g.minX + 1 in 5..12)
        assertTrue(g.maxY - g.minY + 1 in 9..16)
    }

    @Test
    fun zoomClampedToBasemapMax() {
        assertEquals(18, grid(zoom = 21f, maxZoom = 18).z)
        assertEquals(1, grid(zoom = -3f, maxZoom = 18).z)
    }

    @Test
    fun originMatchesGridAtIntegerZoom() {
        // 整数层级下 tileScale == 1：原点 = 投影坐标 - 半屏。
        val z = 6
        val ox = tileOriginX(105.0, 6f, z, 1080f)
        val oy = tileOriginY(35.0, 6f, z, 2000f)
        val g = grid(zoom = 6f)
        assertEquals((ox / 256.0).toInt(), g.minX + 2)
        assertEquals((oy / 256.0).toInt(), g.minY + 2)
    }
}
