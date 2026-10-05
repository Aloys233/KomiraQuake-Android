package com.aloys23.komiraquake.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aloys23.komiraquake.core.AppClock
import com.aloys23.komiraquake.core.IntensityStandard
import com.aloys23.komiraquake.model.EarthquakeEvent
import com.aloys23.komiraquake.ui.components.*
import com.aloys23.komiraquake.ui.theme.AppSurfaces
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.TextField

private enum class FilterType(val label: String) {
    ALL("全部"), WITHIN_500("500km 内"), M4_PLUS("M4+"), RECENT_24H("近 24h"),
}

@Composable
fun EventListScreen(events: List<EarthquakeEvent>, dark: Boolean, hasLocation: Boolean,
    onSelect: (EarthquakeEvent) -> Unit, modifier: Modifier = Modifier,
    isMapFocused: (String) -> Boolean = { false }, onToggleFocus: (String) -> Unit = {},
    onRefresh: () -> Unit = {}, standard: IntensityStandard = IntensityStandard.CSIS) {
    var filter by remember { mutableStateOf(FilterType.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var now by remember { mutableStateOf(AppClock.now()) }
    LaunchedEffect(filter) { while (filter == FilterType.RECENT_24H) { now = AppClock.now(); delay(60_000) } }
    LaunchedEffect(hasLocation) { if (!hasLocation && filter == FilterType.WITHIN_500) filter = FilterType.ALL }
    val filtered = remember(events, filter, searchQuery, now) {
        val base = when (filter) {
            FilterType.ALL -> events
            FilterType.WITHIN_500 -> events.filter { it.distanceKm in 0.0..500.0 }
            FilterType.M4_PLUS -> events.filter { it.magnitude >= 4.0 }
            FilterType.RECENT_24H -> events.filter { now - it.timestamp in 0L..(24L * 60 * 60 * 1000) }
        }
        val query = searchQuery.trim()
        (if (query.isEmpty()) base else base.filter { it.location.contains(query, ignoreCase = true) || it.source.contains(query, ignoreCase = true) })
            .sortedByDescending { it.timestamp }
    }
    Box(modifier.fillMaxSize().background(AppSurfaces.surface(dark)), contentAlignment = Alignment.TopCenter) {
        // Header scrolls with the list, so it cannot consume the viewport at large font scales.
        LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "heading") { PageHeader("地震列表", "事件目录 · 按发震时刻排序", AppIcon.List, dark) }
            item(key = "search") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextField(searchQuery, { searchQuery = it }, label = "搜索地名 / 区域", modifier = Modifier.weight(1f))
                    AppIconButton(AppIcon.Refresh, "刷新地震目录", dark, onRefresh)
                }
            }
            item(key = "filters") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterType.entries.forEach { type ->
                        val enabled = type != FilterType.WITHIN_500 || hasLocation
                        AppChip(if (enabled) type.label else "500km 内 · 需定位", filter == type, dark, { filter = type }, enabled = enabled)
                    }
                }
            }
            item(key = "count") { Label("${filtered.size} 条事件 · 暂无事件不代表安全", AppSurfaces.outline(dark), 12.sp) }
            if (filtered.isEmpty()) {
                item(key = "empty") {
                    AppCard(dark, Modifier.fillMaxWidth()) {
                        LucideIcon(AppIcon.Search, AppSurfaces.outline(dark), modifier = Modifier.padding(bottom = 12.dp).size(28.dp))
                        Label("暂无符合条件的地震事件", AppSurfaces.onSurface(dark), 17.sp, bold = true)
                        Label("可调整筛选或刷新目录。暂无事件不代表安全。", AppSurfaces.outline(dark), 14.sp,
                            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
                        AppButton("刷新目录", dark, onRefresh, icon = AppIcon.Refresh)
                    }
                }
            } else {
                items(filtered, key = { it.identity }) { event ->
                    EarthquakeTile(event, dark, { onSelect(event) }, mapFocused = isMapFocused(event.identity),
                        onToggleFocus = { onToggleFocus(event.identity) }, standard = standard)
                }
            }
        }
    }
}
