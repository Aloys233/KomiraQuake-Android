package com.aloys23.komiraquake.ui.list

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aloys23.komiraquake.core.IntensityStandard
import com.aloys23.komiraquake.model.EarthquakeEvent
import com.aloys23.komiraquake.ui.components.AppIcon
import com.aloys23.komiraquake.ui.components.IntensityBadge
import com.aloys23.komiraquake.ui.components.Label
import com.aloys23.komiraquake.ui.components.LucideIcon
import com.aloys23.komiraquake.ui.components.intensityDisplayOf
import com.aloys23.komiraquake.ui.components.listIntensityDisplayOf
import com.aloys23.komiraquake.ui.components.quakeTimeText
import com.aloys23.komiraquake.ui.map.Basemap
import com.aloys23.komiraquake.ui.map.MapScreen
import com.aloys23.komiraquake.ui.map.TileLoader
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 地震详情页：上半为事件信息，下半为可拖动/缩放的震中地图。
 * 从列表点击卡片进入；返回键或左上角按钮回到列表。
 */
@Composable
fun EventDetailScreen(
    event: EarthquakeEvent,
    dark: Boolean,
    standard: IntensityStandard,
    userLat: Double?,
    userLon: Double?,
    basemap: Basemap,
    tileLoader: TileLoader,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    BackHandler(onBack = onBack)
    BoxWithConstraints(modifier.fillMaxSize().background(scheme.background)) {
        // 在进入 Column 作用域前取好上限，避免 BoxWithConstraintsScope.maxHeight 的隐式接收者失效。
        val infoMaxHeight = maxHeight * 0.5f
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding()
                    .padding(start = 4.dp, top = 8.dp, end = 16.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = onBack) { LucideIcon(AppIcon.Back, scheme.onSurface, "返回地震列表") }
                Label("地震详情", scheme.onSurface, 20.sp, bold = true,
                    modifier = Modifier.weight(1f).semantics { heading() })
            }
            // 信息区最多占半屏，字号放大时内部滚动，不把地图挤没。
            Column(
                Modifier.fillMaxWidth().heightIn(max = infoMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            ) { EventDetailCard(event, standard) }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                MapScreen(
                    userLat = userLat, userLon = userLon, dark = dark,
                    basemap = basemap, tileLoader = tileLoader,
                    focusEvent = event,
                    // 详情页只定位震中，不画 P/S 波前（历史事件本就没有活动波前）。
                    waveEligible = false, warningActive = false,
                    cameraRequest = 0L, topOcclusion = 0.dp,
                    // 详情页地图上方是详情卡片、下方没有悬浮底栏，无额外遮挡。
                    occlusionRects = emptyList(),
                    // 关闭空闲自动归位：用户拖动查看后不应被强行拉回默认视野。
                    idleResetMs = 30L * 60_000L,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** 事件信息卡：烈度色块 + 震中/时刻/来源，以及震级·深度·距离·坐标等明细。 */
@Composable
private fun EventDetailCard(event: EarthquakeEvent, standard: IntensityStandard) {
    val scheme = MiuixTheme.colorScheme
    val intensity = listIntensityDisplayOf(event, standard)
    val local = intensityDisplayOf(event, standard)
    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IntensityBadge(intensity.text, intensity.color, size = 88.dp, label = intensity.shortLabel,
                source = event.sourceTag)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (event.isActive) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(scheme.primary))
                        Label("实时告警中", scheme.primary, 12.sp, bold = true)
                    }
                }
                Label(event.location, scheme.onSurface, 19.sp, bold = true, maxLines = 3)
                Label(quakeTimeText(event.timestamp) + "  UTC+8", scheme.onSurfaceSecondary, 13.sp, maxLines = 1)
                Label(event.sourceTag, scheme.onSurfaceSecondary, 12.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.height(14.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DetailRow("震级", "M %.1f".format(event.magnitude))
            DetailRow("深度", "%.0f km".format(event.depth))
            DetailRow("距离", if (event.distanceKm >= 0) "%.0f km".format(event.distanceKm) else "未知")
            if (local.isLocal) DetailRow("本地预估 (${standard.name})", local.text)
            DetailRow("纬度", "%.4f°".format(event.latitude))
            DetailRow("经度", "%.4f°".format(event.longitude))
            if (event.reportNum > 1) DetailRow("报数", "第 ${event.reportNum} 报")
            if (event.isCanceled) DetailRow("状态", "已取消")
            else if (event.isFinal) DetailRow("状态", "最终报")
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Label(label, MiuixTheme.colorScheme.onSurfaceSecondary, 13.sp)
        Label(value, MiuixTheme.colorScheme.onSurface, 14.sp, bold = true)
    }
}
