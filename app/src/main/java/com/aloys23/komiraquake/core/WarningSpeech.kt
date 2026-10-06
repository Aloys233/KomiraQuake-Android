package com.aloys23.komiraquake.core

import com.aloys23.komiraquake.model.EarthquakeEvent
import com.aloys23.komiraquake.model.WarningLevel
import java.util.Locale

/**
 * 预警的无障碍播报文案。
 *
 * 预警信息此前只走视觉、声音、震动三条路：TalkBack 用户听不到震动、且可能在媒体
 * 静音下也听不到音效，等于完全拿不到预警。这里把同一份信息转成可播报的句子。
 *
 * 放在 core 而非 Composable 里：播报时机（何时值得打断用户）是纯逻辑，可直接单测。
 */
object WarningSpeech {

    /** 需要播报的倒计时档位（秒）。逐秒播报会淹没 TalkBack 队列，只在关键节点播。 */
    val COUNTDOWN_MILESTONES = listOf(60, 30, 20, 10, 5)

    /** 事件首次出现时的完整播报：级别、位置、震级、距离、预估烈度。 */
    fun summary(event: EarthquakeEvent?, standard: IntensityStandard): String? {
        if (event == null) return null
        val parts = mutableListOf<String>()
        parts += if (event.warningLevel == WarningLevel.CRITICAL) "严重地震预警" else "地震预警"
        event.location.takeIf { it.isNotBlank() }?.let { parts += it }
        parts += "震级 %.1f 级".format(event.magnitude)
        if (event.depth > 0) parts += "深度 %.0f 公里".format(event.depth)
        if (event.distanceKm >= 0) parts += "距你 %.0f 公里".format(event.distanceKm)
        val intensity = intensityPhrase(event, standard)
        if (intensity != null) parts += intensity
        return parts.joinToString("，") + "。请立即伏地、遮挡、抓牢。"
    }

    /**
     * 倒计时播报。[seconds] 为剩余秒数，返回 null 表示该档位不需要播报。
     * 负数（到时未知）与 0（已抵达）各有专属文案。
     */
    fun countdown(seconds: Int): String? = when {
        seconds < 0 -> "地震波预计到达时间未知，请立即避险。"
        seconds == 0 -> "地震波预计已抵达你所在区域，请保持避险姿势。"
        seconds in COUNTDOWN_MILESTONES -> "距离地震波抵达还有 $seconds 秒。"
        else -> null
    }

    /** JMA 与国标烈度的量纲不同，读法也不同，需分别措辞。 */
    private fun intensityPhrase(event: EarthquakeEvent, standard: IntensityStandard): String? {
        val hasLocal = event.distanceKm >= 0 &&
            event.estimatedIntensity.isNotEmpty() && event.estimatedIntensity != "--"
        val hasMax = event.maxIntensityRaw > 0 || event.maxIntensityText.isNotBlank()
        if (!hasLocal && !hasMax) return null
        val text = when {
            hasLocal -> event.estimatedIntensity
            else -> event.maxIntensityText.ifBlank {
                String.format(Locale.US, "%.1f", event.maxIntensityRaw)
            }
        }
        val jma = (hasLocal && standard == IntensityStandard.JMA) ||
            (!hasLocal && event.sourceAgency.equals("JMA", ignoreCase = true))
        val prefix = if (hasLocal) "本地预估" else "来源最大"
        return if (jma) "$prefix 日本震度 $text" else "$prefix 烈度 $text"
    }
}
