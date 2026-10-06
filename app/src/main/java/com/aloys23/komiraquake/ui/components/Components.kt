package com.aloys23.komiraquake.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aloys23.komiraquake.core.AppClock
import com.aloys23.komiraquake.core.ClockFormat
import com.aloys23.komiraquake.core.ClockInfo
import com.aloys23.komiraquake.core.ClockState
import com.aloys23.komiraquake.core.IntensityCalculator
import com.aloys23.komiraquake.core.IntensityStandard
import com.aloys23.komiraquake.core.WarningSpeech
import com.aloys23.komiraquake.model.EarthquakeEvent
import com.aloys23.komiraquake.model.WarningLevel
import com.aloys23.komiraquake.ui.theme.AppFontFamily
import com.aloys23.komiraquake.ui.theme.AppSurfaces
import com.aloys23.komiraquake.ui.theme.AppTypography
import com.aloys23.komiraquake.ui.theme.LocalAppDark
import com.aloys23.komiraquake.ui.theme.SeismicColors
import kotlinx.coroutines.delay

/** 发震时刻：与校时时钟一致，固定渲染为 UTC+8 的完整时刻。 */
internal fun quakeTimeText(timestamp: Long): String = ClockFormat.utc8Stamp(timestamp)

@Composable
fun Label(text: String, color: Color, size: TextUnit = 14.sp, bold: Boolean = false,
    maxLines: Int = Int.MAX_VALUE, modifier: Modifier = Modifier) {
    BasicText(text, modifier, style = AppTypography.style(size.value.coerceAtLeast(12f), bold).copy(color = color),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Fixed UTC+8, actual clock status. Never treat no event as a safety indication. */
@Composable
fun NtpClockLabel(info: ClockInfo, dark: Boolean, modifier: Modifier = Modifier) {
    var nowMs by remember { mutableStateOf(AppClock.now()) }
    LaunchedEffect(Unit) { while (true) { nowMs = AppClock.now(); delay(200) } }
    val synced = info.enabled && info.state == ClockState.SYNCED
    val color = if (synced) SeismicColors.clockSynced(dark) else SeismicColors.clockUnsynced(dark)
    Column(modifier.mapGlass(dark, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Label(ClockFormat.utc8Stamp(nowMs), color, 12.sp, bold = true)
        Label("${ClockFormat.ZONE_LABEL} · ${if (synced) "已校准" else "未同步"}", AppSurfaces.outline(dark), 12.sp)
    }
}

data class IntensityDisplay(val text: String, val label: String, val shortLabel: String, val color: Color, val isLocal: Boolean)

fun intensityDisplayOf(event: EarthquakeEvent, standard: IntensityStandard = IntensityStandard.CSIS): IntensityDisplay {
    val hasLocal = event.distanceKm >= 0.0 && event.estimatedIntensity.isNotEmpty() && event.estimatedIntensity != "--"
    val hasMax = event.maxIntensityRaw > 0.0 || event.maxIntensityText.isNotEmpty()
    val text = when {
        hasLocal -> event.estimatedIntensity
        hasMax -> event.maxIntensityText.ifBlank { "%.1f".format(event.maxIntensityRaw) }
        else -> "--"
    }
    val raw = if (hasLocal) event.rawIntensity else event.maxIntensityRaw
    // rawIntensity remains CSIS even when the displayed local estimate is JMA.
    val color = if (!hasLocal && !hasMax) Color(0xFF657579)
        else if (hasLocal && standard == IntensityStandard.JMA) SeismicColors.jmaIntensityColor(text)
        else if (!hasLocal && event.sourceAgency.equals("JMA", ignoreCase = true)) SeismicColors.jmaIntensityColor(text)
        else SeismicColors.intensityColor(raw)
    val shortLabel = if (hasLocal) "预估烈度" else "最大烈度"
    return IntensityDisplay(text, if (hasLocal) "本地预估 · ${standard.name}" else "来源最大烈度", shortLabel, color, hasLocal)
}

/**
 * 列表徽章：固定展示「震源最大烈度」（震中当地量），不随定位变化。
 * 源报缺失时在震中（距离 0）按衰减关系估算，保证列表总有可读烈度。
 * 本地预估烈度只出现在 HUD / 全屏预警。
 */
fun listIntensityDisplayOf(event: EarthquakeEvent, standard: IntensityStandard = IntensityStandard.CSIS): IntensityDisplay {
    val hasMax = event.maxIntensityRaw > 0.0 || event.maxIntensityText.isNotEmpty()
    val raw = if (hasMax) event.maxIntensityRaw else IntensityCalculator.rawCsis(event.magnitude, 0.0, event.depth)
    val jmaStyled = event.sourceAgency.equals("JMA", ignoreCase = true) ||
        (!hasMax && standard == IntensityStandard.JMA)
    val text = when {
        hasMax -> event.maxIntensityText.ifBlank { "%.1f".format(event.maxIntensityRaw) }
        standard == IntensityStandard.JMA -> IntensityCalculator.formatJma(event.magnitude, 0.0, event.depth)
        else -> IntensityCalculator.formatCsis(raw)
    }
    val color = if (jmaStyled) SeismicColors.jmaIntensityColor(text) else SeismicColors.intensityColor(raw)
    return IntensityDisplay(text, "来源最大烈度", "最大烈度", color, isLocal = false)
}

/** kanameishi 式烈度色块：顶部标题 + 底部大数字。 */
@Composable
fun IntensityBadge(intensity: String, color: Color, size: Dp = 56.dp, showLabel: Boolean = true,
    modifier: Modifier = Modifier, label: String = "预估烈度", source: String = "") {
    val foreground = SeismicColors.on(color)
    // JMA 细分震度（5弱/5强/5-/5+）首位数字特大、修饰字较小。
    val modifierValue = Regex("^[0-9]+[弱强+\\-]$").matches(intensity)
    val valueSize = when {
        intensity.length <= 2 -> size.value * 0.60f
        intensity.length == 3 -> size.value * 0.52f
        else -> size.value * 0.46f
    }
    val titleSize = (size.value * 0.17f).coerceIn(9f, 14f)
    Column(modifier.size(size).clip(RoundedCornerShape(size * 0.18f))
        .background(color).semantics { contentDescription = "$label $intensity $source" }
        .padding(horizontal = size * 0.07f, vertical = size * 0.08f),
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (showLabel) BadgeText(label, foreground, titleSize, bold = false)
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.BottomCenter) {
            if (modifierValue) {
                Row(verticalAlignment = Alignment.Top) {
                    BadgeText(intensity.take(1), foreground, valueSize, bold = true)
                    BadgeText(intensity.drop(1), foreground, valueSize * 0.72f, bold = true)
                }
            } else {
                BadgeText(intensity, foreground, valueSize, bold = true)
            }
        }
    }
}

/** 徽章内文字：固定行高，避免 CJK 默认行距把标题与数字挤在一起。 */
@Composable
private fun BadgeText(text: String, color: Color, sizePx: Float, bold: Boolean) {
    BasicText(text, style = TextStyle(
        fontFamily = AppFontFamily,
        fontSize = sizePx.sp,
        lineHeight = (sizePx * 0.98f).sp,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        fontFeatureSettings = "tnum",
        color = color,
    ), maxLines = 1, overflow = TextOverflow.Clip)
}

/** kanameishi 式地图 HUD：左侧烈度色块，右侧「状态 / 震中 / 发震时刻 / 震级·深度·距离」。 */
@Composable
fun QuakeHudCard(event: EarthquakeEvent, dark: Boolean, modifier: Modifier = Modifier,
    standard: IntensityStandard = IntensityStandard.CSIS, isActive: Boolean = false) {
    val severity = SeismicColors.severity(event.warningLevel, dark)
    val intensity = intensityDisplayOf(event, standard)
    Row(modifier.fillMaxWidth().mapGlass(dark).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IntensityBadge(intensity.text, intensity.color, size = 72.dp, label = intensity.shortLabel,
            source = event.sourceTag)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LucideIcon(if (isActive) AppIcon.Radio else AppIcon.Activity, severity, modifier = Modifier.size(16.dp))
                // 报文展示名（对齐 kanameishi 的 titleText），不再硬编码「活动预警」。
                Label(when {
                    event.isCanceled -> "${event.source} · 取消报"
                    isActive -> "${event.source} · 第 ${event.reportNum} 报"
                    else -> event.source
                }, severity, 12.sp, bold = true, maxLines = 1, modifier = Modifier.weight(1f))
            }
            Label(event.location, AppSurfaces.onSurface(dark), 19.sp, bold = true, maxLines = 2)
            // 发震时刻独占一行，始终完整显示。
            Label(quakeTimeText(event.timestamp) + "  UTC+8", AppSurfaces.outline(dark), 12.sp, maxLines = 1)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("M %.1f".format(event.magnitude) + " · 深度 %.0f km".format(event.depth),
                    AppSurfaces.outline(dark), 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                // 数据源标注（提供方·机构）：次要信息，放在震级/深度行右侧。
                Label(event.sourceTag, AppSurfaces.outline(dark), 11.sp, maxLines = 1)
            }
        }
    }
}

@Composable
fun MapStatusBar(statusText: String, statusLevel: WarningLevel, locationName: String, dark: Boolean,
    onOpenList: (() -> Unit)?, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val color = SeismicColors.severity(statusLevel, dark)
    Row(modifier.fillMaxWidth().mapGlass(dark).padding(12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Label("KomiraQuake", AppSurfaces.onSurface(dark), 15.sp, bold = true)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                LucideIcon(if (statusLevel == WarningLevel.NORMAL) AppIcon.Radio else AppIcon.CircleAlert, color, modifier = Modifier.size(16.dp))
                Label(statusText, color, 12.sp, modifier = Modifier.weight(1f))
            }
            Label(locationName, AppSurfaces.outline(dark), 12.sp, maxLines = 2)
        }
        onOpenList?.let { AppIconButton(AppIcon.List, "打开地震列表", dark, it) }
        AppIconButton(AppIcon.Settings, "打开设置", dark, onOpenSettings)
    }
}

/** kanameishi 式列表项：左侧烈度色块，右侧「震中 / 发震时刻 / 震级·深度·距离」。 */
@Composable
fun EarthquakeTile(event: EarthquakeEvent, dark: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    mapFocused: Boolean = false, onToggleFocus: () -> Unit = {}, standard: IntensityStandard = IntensityStandard.CSIS) {
    // 列表固定展示「震源最大烈度」；本地预估烈度只出现在 HUD / 全屏预警。
    val intensity = listIntensityDisplayOf(event, standard)
    AppCard(dark, modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "在地图定位${event.location}", onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IntensityBadge(intensity.text, intensity.color, size = 60.dp, label = intensity.shortLabel)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (event.isActive) Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(AppSurfaces.accent(dark)))
                    Label(event.location, AppSurfaces.onSurface(dark), 16.sp, bold = true, maxLines = 2,
                        modifier = Modifier.weight(1f))
                }
                // 发震时刻独占一行，始终完整显示。
                Label(quakeTimeText(event.timestamp) + "  UTC+8", AppSurfaces.outline(dark), 12.sp, maxLines = 1)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Label("M %.1f".format(event.magnitude), AppSurfaces.onSurface(dark), 15.sp, bold = true)
                    Label("深度 %.0f km".format(event.depth),
                        AppSurfaces.outline(dark), 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    // 数据源标注（提供方·机构）：次要信息，放在震级/深度行右侧。
                    Label(event.sourceTag, AppSurfaces.outline(dark), 11.sp, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton(if (mapFocused) "取消显示" else "地图显示", dark, onToggleFocus,
                icon = if (mapFocused) AppIcon.EyeOff else AppIcon.MapPin, primary = mapFocused)
        }
    }
}

/**
 * 全屏预警的无障碍播报。
 *
 * 预警原本只走视觉、声音、震动三条路，TalkBack 用户拿不到任何信息（震动感知不到、
 * 媒体静音时音效也听不见）。这里用 assertive live region 在事件出现与倒计时关键档位
 * 各播报一次：逐秒播报会淹没 TalkBack 队列，反而让人听不到最后那句。
 *
 * 文案只在变化时改写（见 [WarningSpeech.countdown] 的档位过滤），因此 TalkBack 只在
 * 真正有新信息时才打断用户。
 */
@Composable
private fun AnnounceWarningAccessibility(
    event: EarthquakeEvent?, countdown: Int, standard: IntensityStandard,
) {
    // 零尺寸节点：不占版面、不接受焦点，只作为 live region 挂在语义树上。
    Box(Modifier.size(0.dp).semantics {
        liveRegion = LiveRegionMode.Assertive
        contentDescription = WarningSpeech.summary(event, standard).orEmpty()
    })
    val milestone = WarningSpeech.countdown(countdown)
    Box(Modifier.size(0.dp).semantics {
        liveRegion = LiveRegionMode.Assertive
        contentDescription = milestone.orEmpty()
    })
}

/** 倒计时卡片的固定读法，供 TalkBack 聚焦时使用（与播报文案一致）。 */
private fun countdownPhrase(countdown: Int): String = when {
    countdown > 0 -> "距离地震波抵达还有 $countdown 秒"
    countdown == 0 -> "地震波预计已抵达你所在区域"
    else -> "本地到时未知，请立即避险"
}

/**
 * 「预警尚未开启」引导卡。
 *
 * 预警总开关默认关闭——不擅自替用户打开安全功能。但新装用户若第一眼看不到这件事，
 * 就会以为「装好了却在震时不响」。故在地图页顶部显著位置提示一次，可关闭且不重复打扰。
 */
@Composable
fun WarningOnboardingCard(
    dark: Boolean,
    onEnable: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = SeismicColors.severity(WarningLevel.WARNING, dark)
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
        .background(AppSurfaces.surfaceContainer(dark))
        .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LucideIcon(AppIcon.BellOff, accent)
            Label("地震预警尚未开启", AppSurfaces.onSurface(dark), 16.sp, bold = true,
                modifier = Modifier.weight(1f)
                    .semantics { heading() })
            AppIconButton(AppIcon.Close, "关闭提示", dark, onDismiss)
        }
        Label(
            "当前只展示地震事件，不会发出声音、震动或全屏预警。开启后才会按本地烈度提醒。",
            AppSurfaces.outline(dark), 13.sp,
        )
        AppButton("开启地震预警", dark, onEnable, Modifier.fillMaxWidth(), AppIcon.Bell, primary = true)
    }
}

/** Solid, theme-consistent warning. No glass or decorative animation in the emergency path. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun WarningOverlay(event: EarthquakeEvent?, countdown: Int, onDismiss: () -> Unit, modifier: Modifier = Modifier,
    dark: Boolean = LocalAppDark.current, reduceMotion: Boolean = false, onMute: (() -> Unit)? = null,
    onStop: (() -> Unit)? = null, standard: IntensityStandard = IntensityStandard.CSIS) {
    val severity = SeismicColors.severity(event?.warningLevel ?: WarningLevel.WARNING, dark)
    val foreground = AppSurfaces.onSurface(dark)
    val secondary = AppSurfaces.outline(dark)
    AnnounceWarningAccessibility(event, countdown, standard)
    Box(modifier.fillMaxSize().background(AppSurfaces.surface(dark)).safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LucideIcon(AppIcon.Warning, severity, modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f)) {
                    Label(if (event?.warningLevel == WarningLevel.CRITICAL) "严重地震预警" else "地震预警", severity, 20.sp, bold = true,
                        modifier = Modifier.semantics { heading() })
                    Label("实时预警 · 请立即采取避险措施", secondary, 12.sp)
                }
                AppIconButton(AppIcon.ChevronDown, "收起全屏，保留提醒", dark, onDismiss)
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(severity).padding(20.dp)
                // 焦点落到该卡片时读出完整倒计时句，而不是逐个数字碎片。
                .semantics { contentDescription = countdownPhrase(countdown) },
                horizontalAlignment = Alignment.CenterHorizontally) {
                val ink = SeismicColors.on(severity)
                when {
                    countdown > 0 -> {
                        Label("本地预计 S 波到达倒计时", ink, 16.sp, bold = true)
                        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            val digits = countdown.toString().length.coerceAtLeast(2)
                            val numberSize = (maxWidth.value / (digits * 0.7f) / LocalDensity.current.fontScale).coerceIn(32f, 80f)
                            Label(countdown.toString(), ink, numberSize.sp, bold = true)
                        }
                        Label("秒后到达", ink, 16.sp)
                    }
                    countdown == 0 -> {
                        Label("地震波预计已抵达你所在区域", ink, 24.sp, bold = true)
                        Label("保持避险姿势，远离窗户与悬挂物", ink, 15.sp)
                    }
                    else -> {
                        Label("本地到时未知", ink, 24.sp, bold = true)
                        Label("定位或走时数据不可用，请立即避险", ink, 15.sp)
                    }
                }
            }
            AppCard(dark, Modifier.fillMaxWidth()) {
                Label(event?.location.orEmpty(), foreground, 22.sp, bold = true)
                if (event != null) {
                    Spacer(Modifier.height(8.dp))
                    Label("M %.1f · 深度 %.0f km".format(event.magnitude, event.depth) +
                        if (event.distanceKm >= 0) " · 距你 %.0f km".format(event.distanceKm) else " · 距离未知", secondary)
                    val intensity = intensityDisplayOf(event, standard)
                    Label("${intensity.label} ${intensity.text}", foreground, 20.sp, bold = true, modifier = Modifier.padding(top = 8.dp))
                    Label(event.sourceTag, secondary, 12.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LucideIcon(AppIcon.Shield, AppSurfaces.accent(dark))
                Column(Modifier.weight(1f)) {
                    Label("伏地 · 遮挡 · 抓牢", foreground, 22.sp, bold = true)
                    Label("Drop · Cover · Hold on", secondary, 13.sp)
                    Label("预计到达不代表危险结束，请持续避险", secondary, 14.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
            onMute?.let { AppButton("静音本次", dark, it, Modifier.fillMaxWidth(), AppIcon.Mute) }
            AppButton("收起全屏 · 保留提醒", dark, onDismiss, Modifier.fillMaxWidth(), AppIcon.ChevronDown, primary = true)
            onStop?.let { AppButton("停止本次提醒", dark, it, Modifier.fillMaxWidth(), AppIcon.BellOff) }
            Spacer(Modifier.height(8.dp))
        }
    }
}
