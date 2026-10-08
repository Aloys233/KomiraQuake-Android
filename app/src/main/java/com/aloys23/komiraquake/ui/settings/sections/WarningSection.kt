package com.aloys23.komiraquake.ui.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aloys23.komiraquake.core.IntensityStandard
import com.aloys23.komiraquake.data.prefs.Settings
import com.aloys23.komiraquake.ui.components.Label
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
        SmallTitle("提醒条件")
        if (!alertsOn) {
            // 总开关关着时，下方所有控件都是置灰的；先讲清楚后果，否则像坏了。
            Label(
                "地震预警当前已关闭：地震事件只展示，不产生声音、震动或全屏预警。",
                MiuixTheme.colorScheme.onSurfaceSecondary, 13.sp,
            )
            Spacer(Modifier.height(8.dp))
        }
        Card {
            SwitchPreference(
                title = "地震预警",
                summary = "总开关。开启后按本地烈度过滤决定是否提醒；关闭时地震事件仅展示，" +
                    "不产生声音、震动或全屏预警。",
                checked = settings.enableWarnings,
                onCheckedChange = { checked -> onUpdate { it.copy(enableWarnings = checked) } },
            )
            Spacer(Modifier.height(8.dp))
            Label(
                if (alertsOn) "烈度标准" else "烈度标准（地震预警已关闭，以下不生效）",
                if (alertsOn) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.disabledOnSurface,
                size = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
            TabRowWithContour(
                tabs = listOf("中国烈度 (CSIS)", "日本震度 (JMA)"),
                selectedTabIndex = if (settings.intensityStandard == IntensityStandard.CSIS) 0 else 1,
                onTabSelected = { index ->
                    onUpdate {
                        it.copy(intensityStandard = if (index == 0) IntensityStandard.CSIS else IntensityStandard.JMA)
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
            SliderPreference(
                title = "本地烈度过滤",
                summary = "仅当本地预估烈度达到该值及以上时提醒（按上方所选烈度标准的显示档位比较）；0 表示不作筛选。",
                valueText = if (settings.localIntensityFilter <= 0.0) "关闭"
                else "%.1f 度".format(settings.localIntensityFilter),
                value = settings.localIntensityFilter.toFloat(),
                onValueChange = { v -> onUpdate { it.copy(localIntensityFilter = v.toDouble()) } },
                valueRange = 0f..8f, steps = 15,
                enabled = alertsOn,
            )
        }

        Spacer(Modifier.height(14.dp))
        SmallTitle("声音提醒")
        Card {
            SwitchPreference(
                title = "警报音效",
                summary = when {
                    !alertsOn -> "「地震预警」已关闭，开启本项也不会出声。"
                    settings.isMuted -> "「全局静音」已开启，开启本项也不会出声。"
                    else -> "播放预警音效与倒计时提示音。"
                },
                checked = settings.enableSoundAlert,
                onCheckedChange = { checked -> onUpdate { it.copy(enableSoundAlert = checked) } },
                enabled = alertsOn && !settings.isMuted,
            )
            SwitchPreference(
                title = "全局静音",
                summary = "停止声音，保留视觉提醒。",
                checked = settings.isMuted,
                onCheckedChange = { checked -> onUpdate { it.copy(isMuted = checked) } },
            )
            Spacer(Modifier.height(6.dp))
            SliderPreference(
                title = "警报音量",
                valueText = "${(settings.alertVolume * 100).toInt()}%",
                value = settings.alertVolume.toFloat(),
                onValueChange = { v -> onUpdate { it.copy(alertVolume = v.toDouble()) } },
                valueRange = 0f..1f, steps = 19,
                enabled = audible,
            )
        }
    }
}
