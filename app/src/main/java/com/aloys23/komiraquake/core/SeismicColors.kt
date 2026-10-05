package com.aloys23.komiraquake.core

import com.aloys23.komiraquake.model.WarningLevel
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 语义配色令牌（ARGB Long）。《NATIVE_PORT_SPEC》 §9。
 * 与 UI 层解耦：此处返回 Long，UI 再转换为 Compose Color。
 */
object SeismicColors {
    // 预警级别 Light / Dark
    const val NORMAL_LIGHT = 0xFF006874
    const val NORMAL_DARK = 0xFF4DDAD7
    const val WATCH_LIGHT = 0xFF7A5900
    const val WATCH_DARK = 0xFFFFBA28
    const val WARNING_LIGHT = 0xFFBC2800
    const val WARNING_DARK = 0xFFFF8C66
    const val CRITICAL_LIGHT = 0xFFBA1A1A
    const val CRITICAL_DARK = 0xFFFFB4AB

    // 固定色
    const val P_WAVE = 0xFF0288D1
    const val S_WAVE = 0xFFE65100
    const val ON_DARK = 0xFFFFFFFF
    const val ON_LIGHT = 0xFF1A1C1E

    fun severity(level: WarningLevel, dark: Boolean): Long = when (level) {
        WarningLevel.NORMAL -> if (dark) NORMAL_DARK else NORMAL_LIGHT
        WarningLevel.WATCH -> if (dark) WATCH_DARK else WATCH_LIGHT
        WarningLevel.WARNING -> if (dark) WARNING_DARK else WARNING_LIGHT
        WarningLevel.CRITICAL -> if (dark) CRITICAL_DARK else CRITICAL_LIGHT
    }

    /** severity 颜色叠加 14% 不透明度，用作容器底色。 */
    fun container(level: WarningLevel, dark: Boolean): Long = withAlpha(severity(level, dark), 0.14)

    /** 中国地震烈度色阶（对齐 kanameishi CSIS 配色）。 */
    fun intensityColor(rawIntensity: Double): Long = when (val level = rawIntensity.roundToInt()) {
        1 -> 0xFF9F9F9F // 灰
        2 -> 0xFFCFCFCF // 浅灰
        3 -> 0xFF5FCFFF // 天蓝
        4 -> 0xFF3FAFFF // 蓝
        5 -> 0xFF5FDF8F // 绿
        6 -> 0xFFF7E757 // 黄
        7 -> 0xFFFF8F00 // 橙
        8 -> 0xFFFF4F00 // 橙红
        9 -> 0xFFDF0F0F // 红
        else -> if (level >= 10) 0xFF7F007F else 0xFF9F9F9F // 紫
    }

    fun magnitudeColor(magnitude: Double): Long = when {
        magnitude < 3.0 -> 0xFF00796B
        magnitude < 4.5 -> 0xFFF57F17
        magnitude < 6.0 -> 0xFFE64A19
        else -> 0xFFC2185B
    }

    /** 前景色：深底白字、浅底墨字。 */
    fun on(bg: Long): Long = if (relativeLuminance(bg) < 0.5) ON_DARK else ON_LIGHT

    private fun withAlpha(argb: Long, alpha: Double): Long {
        val a = (255.0 * min(1.0, max(0.0, alpha))).toLong()
        return (a shl 24) or (argb and 0x00FFFFFF)
    }

    private fun relativeLuminance(argb: Long): Double {
        fun channel(c: Long): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        val r = channel((argb shr 16) and 0xFF)
        val g = channel((argb shr 8) and 0xFF)
        val b = channel(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}
