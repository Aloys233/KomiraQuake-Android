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

    /**
     * CSIS 烈度衰减到 [level]（默认 I，可感下限）时的最大震中距（km），用作波前"影响半径"：
     * 超过它波前逐渐隐去。rawCsis 关于距离单调不增，二分求交；量程上限对齐走时表 10000 km。
     */
    fun distanceForCsis(magnitude: Double, depthKm: Double, level: Double = 1.0): Double {
        if (magnitude <= 0.0) return 0.0
        if (rawCsis(magnitude, 0.0, depthKm) < level) return 0.0
        var lo = 0.0
        var hi = 10000.0
        repeat(40) {
            val mid = (lo + hi) / 2.0
            if (rawCsis(magnitude, mid, depthKm) >= level) lo = mid else hi = mid
        }
        return lo
    }

    /** 在 [minRadius, maxRadius] 上从 maxOpacity 过渡到 minOpacity（对齐 kanameishi 的 calcOpacity）。 */
    private fun rampedOpacity(
        radius: Double, minRadius: Double, maxRadius: Double,
        minOpacity: Double, maxOpacity: Double,
    ): Double {
        if (maxRadius <= minRadius) return if (radius <= minRadius) maxOpacity else minOpacity
        if (radius <= minRadius * 0.2 + maxRadius * 0.8) return maxOpacity
        if (radius >= maxRadius) return minOpacity
        val k = 5.0 * (minOpacity - maxOpacity) / (maxRadius - minRadius)
        val b = (5.0 * maxOpacity * maxRadius - 4.0 * minOpacity * maxRadius - minOpacity * minRadius) /
            (maxRadius - minRadius)
        return k * radius + b
    }

    /**
     * 波前描边透明度（对齐 kanameishi 的 P/S 波）：半径 ≤ 影响半径 [fadeKm] 时由 1 渐隐到 0.25，
     * 其后由 0.25 渐隐到 0，超过硬上限 [hardMaxKm] 完全隐藏。
     */
    fun waveOpacity(radiusKm: Double, fadeKm: Double, hardMaxKm: Double = 10000.0): Double {
        if (radiusKm <= 0.0 || fadeKm <= 0.0 || radiusKm >= hardMaxKm) return 0.0
        return if (radiusKm <= fadeKm) rampedOpacity(radiusKm, 0.0, fadeKm, 0.25, 1.0)
        else rampedOpacity(radiusKm, fadeKm, hardMaxKm, 0.0, 0.25)
    }

    /**
     * S 波径向渐变填充的不透明度：影响半径 [fadeKm] 内由 0.25 递减到 0（对齐 kanameishi 的
     * sWaveFill），超过 [fadeKm] 不再填充。需与中心透明、边缘着色的径向渐变叠加使用。
     */
    fun waveFillOpacity(radiusKm: Double, fadeKm: Double): Double {
        if (radiusKm <= 0.0 || fadeKm <= 0.0 || radiusKm > fadeKm) return 0.0
        return rampedOpacity(radiusKm, 0.0, fadeKm, 0.0, 0.25)
    }

    /**
     * 显示烈度（罗马数字的阿拉伯数字形式）：[formatCsis] 取整所用的同一个值，下限 0。
     * 「本地烈度过滤」按它比较而非按 [rawCsis] 的原始连续值，否则 raw 2.6 显示为Ⅲ度、
     * 阈值 3.0 却判为未达到，用户看到Ⅲ度却收不到提醒。
     */
    fun displayLevel(raw: Double): Int = raw.roundToInt().coerceIn(0, ROMAN.size - 1)

    fun formatCsis(raw: Double): String = ROMAN[displayLevel(raw)]

    /** JMA 震度的连续值 s（未分档）。[formatJma] 的分档与 [jmaLevel] 均由它派生。 */
    fun jmaValue(magnitude: Double, distanceKm: Double, depthKm: Double): Double {
        val r = max(1e-6, sqrt(distanceKm * distanceKm + depthKm * depthKm))
        return 2.0 * magnitude - 4.68 * log10(r) - 0.007 * r - 1.66
    }

    /**
     * JMA 分档表：上界、显示文本、级数。[formatJma] 与 [jmaLevel] 共用这一张表，
     * 保证「显示成什么」与「过滤按几级比较」不可能分叉。
     * 5弱/5强 同为 5 级、6弱/6强 同为 6 级（过滤不区分强弱）。
     */
    private val JMA_BANDS = listOf(
        Triple(0.5, "0", 0), Triple(1.5, "1", 1), Triple(2.5, "2", 2),
        Triple(3.5, "3", 3), Triple(4.5, "4", 4), Triple(5.0, "5弱", 5),
        Triple(5.5, "5强", 5), Triple(6.0, "6弱", 6), Triple(6.5, "6强", 6),
    )
    private const val JMA_MAX_LEVEL = 7

    /** JMA 显示震度（0–7 的整数）。与 [formatJma] 用同一组分档边界。 */
    fun jmaLevel(magnitude: Double, distanceKm: Double, depthKm: Double): Int {
        val s = jmaValue(magnitude, distanceKm, depthKm)
        return JMA_BANDS.firstOrNull { s < it.first }?.third ?: JMA_MAX_LEVEL
    }

    /**
     * 当前烈度制式下的显示级数：CSIS 取 raw 值的取整档，JMA 取震度分档。
     * 「本地烈度过滤」按它与阈值比较，使过滤语义始终跟随设置页选择的显示标准。
     */
    fun displayedLevel(
        magnitude: Double, rawCsis: Double, distanceKm: Double, depthKm: Double,
        standard: IntensityStandard,
    ): Double = when (standard) {
        IntensityStandard.CSIS -> displayLevel(rawCsis).toDouble()
        IntensityStandard.JMA -> jmaLevel(magnitude, distanceKm, depthKm).toDouble()
    }

    fun formatJma(magnitude: Double, distanceKm: Double, depthKm: Double = 10.0): String {
        val s = jmaValue(magnitude, distanceKm, depthKm)
        return JMA_BANDS.firstOrNull { s < it.first }?.second ?: "7"
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
