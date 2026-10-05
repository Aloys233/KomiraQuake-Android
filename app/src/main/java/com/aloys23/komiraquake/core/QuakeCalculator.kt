package com.aloys23.komiraquake.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** 《NATIVE_PORT_SPEC》 §4.1 距离与常量速度。 */
object QuakeCalculator {
    const val P_WAVE_SPEED = 6.0
    const val S_WAVE_SPEED = 3.5
    const val EARTH_RADIUS_KM = 6371.0

    /** 同一地震判定窗口：发震时刻相差不超过 30 s 且震中相距不超过 25 km。 */
    const val SAME_QUAKE_WINDOW_MS = 30_000L
    const val SAME_QUAKE_RADIUS_KM = 25.0

    fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (!lat1.isFinite() || !lon1.isFinite() || !lat2.isFinite() || !lon2.isFinite()) return 0.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val h = sin(dLat / 2).let { it * it } +
            cos(rLat1) * cos(rLat2) * sin(dLon / 2).let { it * it }
        val c = 2.0 * atan2(sqrt(h), sqrt(1.0 - h))
        return EARTH_RADIUS_KM * c
    }

    fun hypocenterDistance(epicentralKm: Double, depthKm: Double): Double {
        val d = max(depthKm, 0.0)
        return sqrt(epicentralKm * epicentralKm + d * d)
    }

    /** 常量速度兜底走时（秒）。 */
    fun estimateTravelTimes(epicentralKm: Double, depthKm: Double): Pair<Double, Double> {
        val hypo = hypocenterDistance(epicentralKm, depthKm)
        return Pair(hypo / P_WAVE_SPEED, hypo / S_WAVE_SPEED)
    }

    fun isValidCoordinate(lat: Double, lon: Double): Boolean =
        lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

    fun elapsedSeconds(originEpochMs: Long, nowEpochMs: Long): Double = (nowEpochMs - originEpochMs) / 1000.0

    /** 震中到「中国范围」包围盒最远角点的距离（km），即波前扫过全境所需的半径。 */
    private fun maxDistanceToChinaKm(lat: Double, lon: Double): Double {
        val lats = doubleArrayOf(CoordinateTransform.CHINA_MIN_LAT, CoordinateTransform.CHINA_MAX_LAT)
        val lons = doubleArrayOf(CoordinateTransform.CHINA_MIN_LON, CoordinateTransform.CHINA_MAX_LON)
        var far = 0.0
        for (la in lats) {
            for (lo in lons) far = max(far, haversineDistance(lat, lon, la, lo))
        }
        return far
    }

    /**
     * P、S 波前是否都已超出中国范围；是则两个波前应一起隐藏（-1 表示已超出量程，按已超出处理）。
     * 只有两者都离开中国后才隐藏，避免 P 波（更快）先消失而 S 波仍在。
     */
    fun bothWavesBeyondChina(lat: Double, lon: Double, pKm: Double, sKm: Double): Boolean {
        val far = maxDistanceToChinaKm(lat, lon)
        val pOut = pKm < 0.0 || pKm > far
        val sOut = sKm < 0.0 || sKm > far
        return pOut && sOut
    }

    /**
     * 两条报次（实时预警 / 目录）是否描述同一次地震：发震时刻接近且震中邻近。
     * 实时预警与 CENC 目录的事件 ID 格式不同（如 `202609301831.0001` 与 `CC.20260930184025.5`），
     * 只能按发震时刻与震中归并，不能靠 ID 对齐。
     */
    fun isSameQuake(
        timestampA: Long, latA: Double, lonA: Double,
        timestampB: Long, latB: Double, lonB: Double,
    ): Boolean {
        if (timestampA <= 0L || timestampB <= 0L) return false
        if (abs(timestampA - timestampB) > SAME_QUAKE_WINDOW_MS) return false
        return haversineDistance(latA, lonA, latB, lonB) <= SAME_QUAKE_RADIUS_KM
    }
}
