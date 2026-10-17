package com.aloys23.komiraquake.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 地图几何：点在面内判定与点到面最短距离。供矢量地图（区县级 EEW 烈度着色）使用。
 *
 * 与桌面端 `desktop/src/core/geo_geometry.cpp` 逐行等价，是有意维护的跨端同源契约。
 *
 * 坐标一律为 WGS-84 度。经度不在这里做环绕：渲染层按视口把要素按 ±360° 复制绘制，
 * 副本只是经度整体平移，故点在面内的判定仍是简单的经度直接比较。
 */
/**
 * 一条闭合环：经纬度按 [lat, lon, lat, lon, …] 交错存进 [DoubleArray]（度，WGS-84）。
 *
 * 早先用 `List<Pair<Double, Double>>`：34 万个点每个都要一个 Pair 加两个装箱 Double，
 * 约 20 MB 垃圾；压成 DoubleArray 后同样数据约 5 MB，解析时的临时分配也少一个数量级。
 */
class GeoRing(val coords: DoubleArray) {
    val size: Int get() = coords.size / 2

    fun lat(index: Int): Double = coords[index * 2]
    fun lon(index: Int): Double = coords[index * 2 + 1]

    companion object {
        /** 便于测试与小数据构造（生产路径直接写 DoubleArray）。 */
        fun of(points: List<Pair<Double, Double>>): GeoRing {
            val out = DoubleArray(points.size * 2)
            for (i in points.indices) {
                out[i * 2] = points[i].first
                out[i * 2 + 1] = points[i].second
            }
            return GeoRing(out)
        }
    }
}

object GeoGeometry {
    private const val KM_PER_DEGREE_LAT = 111.32

    /** 一个面的全部环：首个为外环，其余为洞（飞地、内陆空洞）。 */
    typealias GeoPolygon = List<GeoRing>

    /**
     * 以查询点为原点的局部平面近似（等距圆柱）。
     *
     * 区县尺度（平均跨度约 50 km）下该近似的误差远小于数据本身的 5 m 量化步长，
     * 却把「点到线段」从每段一次球面三角运算降为二维叉积 —— 全量 1011 个区县
     * 约 34 万段，故可以放在事件更新时算而无需每帧。
     */
    private class LocalPlane(lat: Double, lon: Double) {
        private val lat0 = lat
        private val lon0 = lon

        // 与 pxPerKm 一致地夹住 cosLat，避免极点附近除零。
        private val kmPerDegLon = KM_PER_DEGREE_LAT * max(0.01, cos(Math.toRadians(lat)))

        fun x(lon: Double) = (lon - lon0) * kmPerDegLon
        fun y(lat: Double) = (lat - lat0) * KM_PER_DEGREE_LAT
    }

    /** 二维点 (px,py) 到线段 (ax,ay)-(bx,by) 的最短距离平方。 */
    private fun squaredDistanceToSegment2D(
        px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double,
    ): Double {
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq <= 0.0) {
            val ex = px - ax
            val ey = py - ay
            return ex * ex + ey * ey
        }
        // 参数 t 夹到 [0,1]，使投影落在线段上而非其延长线。
        val t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / lenSq))
        val ex = px - (ax + t * dx)
        val ey = py - (ay + t * dy)
        return ex * ex + ey * ey
    }

    /**
     * 射线法（even-odd）：统计点向右投射时与环边的交点个数，奇数则在面内。
     *
     * 在经纬度平面上直接做 —— 射线法只看拓扑（穿越次数），故经纬度平面上的结果与
     * 投影无关，无需先转平面坐标。
     */
    private fun pointInRing(ring: GeoRing, lat: Double, lon: Double): Boolean {
        var inside = false
        val n = ring.size
        var j = n - 1
        for (i in 0 until n) {
            val latI = ring.lat(i)
            val lonI = ring.lon(i)
            val latJ = ring.lat(j)
            val lonJ = ring.lon(j)
            // 半开区间比较，避免顶点恰好落在射线所在纬度上时被重复计数。
            if ((latI > lat) != (latJ > lat)) {
                val t = (lat - latI) / (latJ - latI)
                if (lon < lonI + t * (lonJ - lonI)) inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * 射线法（even-odd）判定点是否落在面内。点在洞内判为「不在」。
     *
     * 必须用 even-odd 规则而非「任一环命中即命中」：后者会让洞内的点被误判为在面内。
     */
    fun pointInPolygon(polygon: GeoPolygon, lat: Double, lon: Double): Boolean {
        var inside = false
        for (ring in polygon) {
            if (pointInRing(ring, lat, lon)) inside = !inside
        }
        return inside
    }

    /**
     * 点到面的最短距离（km）。点在面内返回 0。
     *
     * 洞内**不算**面内（洞不属于该区县），故返回到最近边界的距离而非 0。
     *
     * 区内取 0 是**有意的保守选择**：地震预警里显示更高烈度属于安全侧，
     * 而区内采样平均既更贵、又落在不安全侧。
     */
    fun distanceToPolygonKm(polygon: GeoPolygon, lat: Double, lon: Double): Double {
        if (pointInPolygon(polygon, lat, lon)) return 0.0

        val plane = LocalPlane(lat, lon)
        var bestSq = Double.POSITIVE_INFINITY
        for (ring in polygon) {
            val n = ring.size
            if (n < 2) continue
            var j = n - 1
            for (i in 0 until n) {
                val ax = plane.x(ring.lon(j))
                val ay = plane.y(ring.lat(j))
                val bx = plane.x(ring.lon(i))
                val by = plane.y(ring.lat(i))
                bestSq = min(bestSq, squaredDistanceToSegment2D(0.0, 0.0, ax, ay, bx, by))
                j = i
            }
        }
        return if (bestSq.isFinite()) sqrt(bestSq) else 0.0
    }

    /** 点到单条线段的最短距离（km）。 */
    fun distanceToSegmentKm(
        latA: Double, lonA: Double, latB: Double, lonB: Double, latP: Double, lonP: Double,
    ): Double {
        val plane = LocalPlane(latP, lonP)
        return sqrt(
            squaredDistanceToSegment2D(
                0.0, 0.0, plane.x(lonA), plane.y(latA), plane.x(lonB), plane.y(latB),
            ),
        )
    }

    /** 经纬度包围盒。用于按影响半径裁剪候选面，避免对全部区县做精确距离计算。 */
    data class GeoBounds(
        val minLat: Double,
        val minLon: Double,
        val maxLat: Double,
        val maxLon: Double,
    ) {
        fun contains(lat: Double, lon: Double) =
            lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon

        /**
         * 盒内任一点到 (lat, lon) 的上界（km），保守但无需遍历顶点。
         *
         * 经度跨度按最大 cos 因子（即 1.0）换算，故结果是保守上界。
         */
        fun maxDistanceKm(lat: Double, lon: Double): Double {
            val dLat = max(abs(lat - minLat), abs(maxLat - lat)) * KM_PER_DEGREE_LAT
            val dLon = max(abs(lon - minLon), abs(maxLon - lon)) * KM_PER_DEGREE_LAT
            return sqrt(dLat * dLat + dLon * dLon)
        }
    }

    /** 计算一组环的包围盒。 */
    fun boundsOf(polygon: GeoPolygon): GeoBounds {
        var minLat = 0.0
        var minLon = 0.0
        var maxLat = 0.0
        var maxLon = 0.0
        var first = true
        for (ring in polygon) {
            for (i in 0 until ring.size) {
                val lat = ring.lat(i)
                val lon = ring.lon(i)
                if (first) {
                    minLat = lat; maxLat = lat; minLon = lon; maxLon = lon
                    first = false
                    continue
                }
                minLat = min(minLat, lat)
                maxLat = max(maxLat, lat)
                minLon = min(minLon, lon)
                maxLon = max(maxLon, lon)
            }
        }
        return GeoBounds(minLat, minLon, maxLat, maxLon)
    }
}