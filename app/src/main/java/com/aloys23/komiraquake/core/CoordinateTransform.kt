package com.aloys23.komiraquake.core

import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** WGS-84 <-> GCJ-02 纠偏。《NATIVE_PORT_SPEC》 §4.5。 */
object CoordinateTransform {
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    /** 中国范围包围盒，与 GCJ-02 适用边界一致；也用作波前是否已离开中国的判定范围。 */
    const val CHINA_MIN_LAT = 0.8293
    const val CHINA_MAX_LAT = 55.8271
    const val CHINA_MIN_LON = 72.004
    const val CHINA_MAX_LON = 137.8347

    fun wgs84ToGcj02(lat: Double, lng: Double): Pair<Double, Double> {
        if (outOfChina(lat, lng)) return Pair(lat, lng)
        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * Math.PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI)
        dLng = (dLng * 180.0) / (A / sqrtMagic * Math.cos(radLat) * Math.PI)
        return Pair(lat + dLat, lng + dLng)
    }

    fun gcj02ToWgs84(lat: Double, lng: Double): Pair<Double, Double> {
        if (outOfChina(lat, lng)) return Pair(lat, lng)
        var dLat = 0.0
        var dLng = 0.0
        repeat(3) {
            val (gLat, gLng) = wgs84ToGcj02(lat + dLat, lng + dLng)
            dLat += lat - gLat
            dLng += lng - gLng
        }
        return Pair(lat + dLat, lng + dLng)
    }

    private fun outOfChina(lat: Double, lng: Double): Boolean =
        lng < CHINA_MIN_LON || lng > CHINA_MAX_LON || lat < CHINA_MIN_LAT || lat > CHINA_MAX_LAT

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * Math.PI) + 40.0 * sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * Math.PI) + 320 * sin(y * Math.PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * Math.PI) + 20.0 * sin(2.0 * x * Math.PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * Math.PI) + 40.0 * sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * Math.PI) + 300.0 * sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
        return ret
    }
}
