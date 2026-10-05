package com.aloys23.komiraquake.core

import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class IntensityStandard { CSIS, JMA }

/** 《NATIVE_PORT_SPEC》 §4.3 烈度。 */
object IntensityCalculator {
    private val ROMAN = listOf(
        "0", "I", "II", "III", "IV", "V", "VI",
        "VII", "VIII", "IX", "X", "XI", "XII",
    )

    /** 中国大陆 CEA 烈度衰减关系（对齐参考实现 kanameishi 的 calcCeaCsis）。 */
    private fun ceaCsis(magnitude: Double, distanceKm: Double): Double =
        1.297 * magnitude - 4.368 * log10(distanceKm + 15.0) + 5.363

    fun rawCsis(magnitude: Double, distanceKm: Double, depthKm: Double = 10.0): Double {
        if (magnitude <= 0.0) return 0.0
        if (distanceKm > 10000.0) return 0.0
        // 震源到观测点的直线距离（含地球曲率与深度）。深度过浅按 10 km 计，避免近场烈度虚高。
        val radius = QuakeCalculator.EARTH_RADIUS_KM
        val depth = max(depthKm, 10.0)
        val theta = distanceKm / radius
        val vertical = radius - depth
        val lineDistance = sqrt(vertical * vertical + radius * radius - 2.0 * vertical * radius * cos(theta))
        // 破裂尺度：把有限断层等效为一个可忽略的近场距离，取其与震中距的较大衰减。
        val rupture = 10.0.pow((magnitude - 3.821) / 1.86)
        val hypoDistance = maxOf(lineDistance - 10.0 - rupture, distanceKm - rupture, 0.2 * (lineDistance - 10.0), 0.0)
        return max(0.0, (ceaCsis(magnitude, distanceKm) + ceaCsis(magnitude, hypoDistance)) / 2.0)
    }

    fun formatCsis(raw: Double): String {
        val idx = raw.roundToInt().coerceIn(0, ROMAN.size - 1)
        return ROMAN[idx]
    }

    fun formatJma(magnitude: Double, distanceKm: Double, depthKm: Double = 10.0): String {
        val r = max(1e-6, sqrt(distanceKm * distanceKm + depthKm * depthKm))
        val s = 2.0 * magnitude - 4.68 * log10(r) - 0.007 * r - 1.66
        return when {
            s < 0.5 -> "0"
            s < 1.5 -> "1"
            s < 2.5 -> "2"
            s < 3.5 -> "3"
            s < 4.5 -> "4"
            s < 5.0 -> "5弱"
            s < 5.5 -> "5强"
            s < 6.0 -> "6弱"
            s < 6.5 -> "6强"
            else -> "7"
        }
    }

    fun getIntensityDescription(raw: Double): String = when (raw.roundToInt().coerceAtLeast(0)) {
        0 -> "无感，仪器仅可记录"
        1 -> "极轻微，少数敏感人群可感"
        2 -> "轻微，室内少数人有感，悬挂物微动"
        3 -> "明显，室内多数人有感，门窗轻微作响"
        4 -> "较强，室内普遍有感，器皿碰撞作响"
        5 -> "强烈，室外多数人有感，轻微破坏可能出现"
        6 -> "剧烈，多数人站立不稳，简易房屋可能出现破坏"
        7 -> "破坏性，房屋出现破坏，地表可能出现裂缝"
        else -> "毁灭性，建筑物严重破坏，地形显著变形"
    }
}
