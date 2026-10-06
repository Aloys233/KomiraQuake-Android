package com.aloys23.komiraquake.ui.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aloys23.komiraquake.core.IntensityStandard
import com.aloys23.komiraquake.data.prefs.Settings
import com.aloys23.komiraquake.ui.components.AppCard
import com.aloys23.komiraquake.ui.components.AppChip
import com.aloys23.komiraquake.ui.components.AppSliderRow
import com.aloys23.komiraquake.ui.components.AppSwitchRow
import com.aloys23.komiraquake.ui.components.Label
import com.aloys23.komiraquake.ui.components.SectionHeader
import com.aloys23.komiraquake.ui.theme.AppSurfaces

/**
 * 「预警提醒」分区：总开关、烈度过滤与声音。
 *
 * 依赖关系显式化：上级开关关闭时下级控件置灰（enabled=false），并在说明里写明
 * 被谁挡住，避免用户反复试探「为什么改了没反应」。
 */
@Composable
internal fun WarningSection(
    settings: Settings,
    dark: Boolean,
    onUpdate: ((Settings) -> Settings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val alertsOn = settings.enableWarnings
    val audible = alertsOn && !settings.isMuted && settings.enableSoundAlert

    Column(modifier) {
        SectionHeader("提醒条件", dark)
        if (!alertsOn) {
            // 总开关关着时，下方所有控件都是置灰的；先讲清楚后果，否则像坏了。
            Label(
                "地震预警当前已关闭：地震事件只展示，不产生声音、震动或全屏预警。",
                AppSurfaces.outline(dark), 13.sp,
            )
            Spacer(Modifier.height(8.dp))
        }
        AppCard(dark) {
            AppSwitchRow(
                "地震预警", settings.enableWarnings, dark,
                onChange = { checked -> onUpdate { it.copy(enableWarnings = checked) } },
                summary = "总开关。开启后按本地烈度过滤决定是否提醒；关闭时地震事件仅展示，" +
                    "不产生声音、震动或全屏预警。",
            )
            Spacer(Modifier.height(8.dp))
            Label(
                if (alertsOn) "烈度标准" else "烈度标准（地震预警已关闭，以下不生效）",
                if (alertsOn) AppSurfaces.onSurface(dark) else AppSurfaces.disabled(dark),
                size = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppChip(
                    "中国烈度 (CSIS)", settings.intensityStandard == IntensityStandard.CSIS,
                    dark, enabled = alertsOn,
                    onClick = { onUpdate { it.copy(intensityStandard = IntensityStandard.CSIS) } },
                )
                AppChip(
                    "日本震度 (JMA)", settings.intensityStandard == IntensityStandard.JMA,
                    dark, enabled = alertsOn,
                    onClick = { onUpdate { it.copy(intensityStandard = IntensityStandard.JMA) } },
                )
            }
            Spacer(Modifier.height(12.dp))
            AppSliderRow(
                title = "本地烈度过滤",
                valueText = if (settings.localIntensityFilter <= 0.0) "关闭"
                else "%.1f 度".format(settings.localIntensityFilter),
                value = settings.localIntensityFilter.toFloat(),
                onValueChange = { v -> onUpdate { it.copy(localIntensityFilter = v.toDouble()) } },
                dark = dark, valueRange = 0f..8f, steps = 15,
                enabled = alertsOn,
                summary = "仅当本地预估烈度达到该值及以上时提醒（按上方所选烈度标准的显示档位比较）；0 表示不作筛选。",
            )
        }

        Spacer(Modifier.height(14.dp))
        SectionHeader("声音提醒", dark)
        AppCard(dark) {
            AppSwitchRow(
                "警报音效", settings.enableSoundAlert, dark,
                onChange = { checked -> onUpdate { it.copy(enableSoundAlert = checked) } },
                summary = when {
                    !alertsOn -> "「地震预警」已关闭，开启本项也不会出声。"
                    settings.isMuted -> "「全局静音」已开启，开启本项也不会出声。"
                    else -> "播放预警音效与倒计时提示音。"
                },
                enabled = alertsOn && !settings.isMuted,
            )
            AppSwitchRow(
                "全局静音", settings.isMuted, dark,
                onChange = { checked -> onUpdate { it.copy(isMuted = checked) } },
                summary = "停止声音，保留视觉提醒。",
            )
            Spacer(Modifier.height(6.dp))
            AppSliderRow(
                title = "警报音量",
                valueText = "${(settings.alertVolume * 100).toInt()}%",
                value = settings.alertVolume.toFloat(),
                onValueChange = { v -> onUpdate { it.copy(alertVolume = v.toDouble()) } },
                dark = dark, valueRange = 0f..1f, steps = 19,
                enabled = audible,
            )
        }
    }
}
