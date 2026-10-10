package com.aloys23.komiraquake.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor

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

    @Test
    fun antimeridianPanKeepsTilesContinuous() {
        // 相机越过 ±180° 时，原始瓦片列与原点会同步平移整 n 格，同一地理位置在屏上不动。
        val z = 3
        val n = 1 shl z
        val zoom = z.toFloat()
        val w = 1080f
        val geoLon = 179.9
        val geoRaw = floor((geoLon + 180.0) / 360.0 * n).toInt()

        fun rawColumnFor(centerLon: Double): Int {
            val g = tileGrid(0.0, centerLon, zoom, w, 2000f, maxZoom = 18, margin = 2)
            return (g.minX..g.maxX).first { ((it % n) + n) % n == geoRaw }
        }

        // 180.1° 越过反经线后表述为 -179.9°；用原始列定位，屏上位置几乎不变。
        val rawWest = rawColumnFor(179.9)
        val rawEast = rawColumnFor(-179.9)
        assertEquals(tileScreenX(rawWest, 179.9, zoom, z, w), tileScreenX(rawEast, -179.9, zoom, z, w), 2f)

        // 对照：若拿折叠后的列定位（旧实现），位置会整整跳一个世界宽度。
        val wrapped = { raw: Int -> ((raw % n) + n) % n }
        val jump = abs(tileScreenX(wrapped(rawWest), 179.9, zoom, z, w) -
            tileScreenX(wrapped(rawEast), -179.9, zoom, z, w))
        assertTrue(jump > w)
    }
}
