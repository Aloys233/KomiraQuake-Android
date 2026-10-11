package com.aloys23.komiraquake.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 与桌面端 `desktop/tests/core_engine_test.cpp` 的 GeoGeometry 段一一对应，
 * 锁定两端「点在面内判定 / 点到面距离」行为一致。
 */
class GeoGeometryTest {
    private val kmPerDeg = 111.32

    /** 1° 见方的闭合环（首尾点相同）。 */
    private val square = listOf(
        0.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0, 1.0 to 0.0, 0.0 to 0.0,
    )

    @Test
    fun pointInPolygonDetectsInsideAndOutside() {
        assertTrue(GeoGeometry.pointInPolygon(listOf(square), 0.5, 0.5))
        assertFalse(GeoGeometry.pointInPolygon(listOf(square), 1.5, 0.5))
        assertFalse(GeoGeometry.pointInPolygon(listOf(square), 0.5, -0.5))
        // 恰好落在边上的点：射线法在此情形是任意的（测度为零），故只断言明显在面外的点。
        assertFalse(GeoGeometry.pointInPolygon(listOf(square), -0.1, 0.5))
        assertFalse(GeoGeometry.pointInPolygon(listOf(square), 0.5, 1.1))
    }

    @Test
    fun holeIsNotPartOfTheFace() {
        // 外环 0..3，其内挖一个 1..2 的洞。洞内必须判为「不在」——
        // 若用「任一环命中即命中」会误判为在内，区县着色就会把洞画成实心。
        val outer = listOf(
            0.0 to 0.0, 0.0 to 3.0, 3.0 to 3.0, 3.0 to 0.0, 0.0 to 0.0,
        )
        val hole = listOf(
            1.0 to 1.0, 1.0 to 2.0, 2.0 to 2.0, 2.0 to 1.0, 1.0 to 1.0,
        )
        val withHole = listOf(outer, hole)

        assertTrue(GeoGeometry.pointInPolygon(withHole, 0.5, 0.5))
        assertFalse(GeoGeometry.pointInPolygon(withHole, 1.5, 1.5))
        // 洞不属于该区县，故距离取到洞边界（0.5°）而非 0。
        assertEquals(
            0.5 * kmPerDeg,
            GeoGeometry.distanceToPolygonKm(withHole, 1.5, 1.5),
            0.5,
        )
    }

    @Test
    fun distanceToPolygonMeasuresToBoundary() {
        val face = listOf(square)
        assertEquals(0.0, GeoGeometry.distanceToPolygonKm(face, 0.5, 0.5), 1e-9)
        // 正北 0.5°
        assertEquals(0.5 * kmPerDeg, GeoGeometry.distanceToPolygonKm(face, 0.5, 1.5), 0.5)
        // 角点外侧：到最近角点。
        val corner = kotlin.math.sqrt(2.0) * 0.5 * kmPerDeg
        assertEquals(corner, GeoGeometry.distanceToPolygonKm(face, -0.5, -0.5), 0.5)
    }

    @Test
    fun longitudeDistanceShrinksByCosLatitude() {
        val face = listOf(square)
        // 赤道上无 cos 收缩。
        assertEquals(0.5 * kmPerDeg, GeoGeometry.distanceToPolygonKm(face, 0.0, 1.5), 0.5)
        // 面须跨过查询点所在纬度，否则最近点是角点而非正东的边。
        val midLat = listOf(
            40.0 to 0.0, 40.0 to 1.0, 41.0 to 1.0, 41.0 to 0.0, 40.0 to 0.0,
        )
        val expected = 0.5 * kmPerDeg * kotlin.math.cos(Math.toRadians(40.5))
        assertEquals(
            expected,
            GeoGeometry.distanceToPolygonKm(listOf(midLat), 40.5, 1.5),
            0.5,
        )
    }

    @Test
    fun distanceToSegmentClampsToEndpoint() {
        // 投影落在线段内部。
        assertEquals(
            0.5 * kmPerDeg,
            GeoGeometry.distanceToSegmentKm(0.0, 0.0, 1.0, 0.0, 0.5, 0.5),
            0.5,
        )
        // 点 (5,0) 在延长线上：夹到端点 (1,0)，距离 4°。
        assertEquals(
            4.0 * kmPerDeg,
            GeoGeometry.distanceToSegmentKm(0.0, 0.0, 1.0, 0.0, 5.0, 0.0),
            0.5,
        )
    }

    @Test
    fun boundsUpperBoundCoversRealDistance() {
        val bounds = GeoGeometry.boundsOf(listOf(square))
        assertEquals(0.0, bounds.minLat, 0.0)
        assertEquals(1.0, bounds.maxLat, 0.0)
        assertEquals(0.0, bounds.minLon, 0.0)
        assertEquals(1.0, bounds.maxLon, 0.0)
        assertTrue(bounds.contains(0.5, 0.5))
        assertFalse(bounds.contains(1.5, 0.5))

        // 上界必须 >= 真实距离，否则剪枝会漏掉受影响区县；它是包围盒对角线，刻意保守。
        val real = GeoGeometry.distanceToPolygonKm(listOf(square), 0.5, 1.5)
        val upper = bounds.maxDistanceKm(0.5, 1.5)
        assertTrue("upper=$upper real=$real", upper >= real)
        assertTrue("upper=$upper", upper < real * 4.0)
        // 远离本面的查询点，上界应显著大于 0（否则剪枝会误杀）。
        assertTrue(bounds.maxDistanceKm(30.0, 120.0) > 1000.0)
    }
}