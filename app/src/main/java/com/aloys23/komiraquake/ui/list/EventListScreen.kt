package com.aloys23.komiraquake.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aloys23.komiraquake.core.AppClock
import com.aloys23.komiraquake.core.IntensityStandard
import com.aloys23.komiraquake.model.EarthquakeEvent
import com.aloys23.komiraquake.ui.components.*
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class FilterType(val label: String) {
    ALL("全部"), WITHIN_500("500km 内"), M4_PLUS("M4+"), RECENT_24H("近 24h"),
}

@Composable
fun EventListScreen(events: List<EarthquakeEvent>, dark: Boolean, hasLocation: Boolean,
    onSelect: (EarthquakeEvent) -> Unit, modifier: Modifier = Modifier,
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
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(modifier.fillMaxSize().background(MiuixTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
        // Header scrolls with the list, so it cannot consume the viewport at large font scales.
        // 列数按可用宽度自适应：当前平板宽度会得到三列，更窄时自动降为两列/一列。
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 360.dp),
            modifier = Modifier.widthIn(max = 1200.dp).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp + statusTop, end = 16.dp, bottom = 96.dp + navBottom),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "heading", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    LucideIcon(AppIcon.List, MiuixTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    Column(Modifier.weight(1f)) {
                        Label("地震列表", MiuixTheme.colorScheme.onSurface, 26.sp, bold = true,
                            modifier = Modifier.semantics { heading() })
                        Label("事件目录 · 按发震时刻排序", MiuixTheme.colorScheme.onSurfaceSecondary, 13.sp)
                    }
                }
            }
            item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextField(searchQuery, { searchQuery = it }, label = "搜索地名 / 区域", modifier = Modifier.weight(1f))
                    IconButton(onClick = onRefresh) { LucideIcon(AppIcon.Refresh, MiuixTheme.colorScheme.primary, "刷新地震目录") }
                }
            }
            item(key = "filters", span = { GridItemSpan(maxLineSpan) }) {
                TabRowWithContour(
                    tabs = FilterType.entries.map { it.label },
                    selectedTabIndex = filter.ordinal,
                    onTabSelected = { index ->
                        val type = FilterType.entries[index]
                        if (type != FilterType.WITHIN_500 || hasLocation) filter = type
                    },
                )
            }
            item(key = "count", span = { GridItemSpan(maxLineSpan) }) { Label("${filtered.size} 条事件 · 暂无事件不代表安全", MiuixTheme.colorScheme.onSurfaceSecondary, 12.sp) }
            if (filtered.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                        LucideIcon(AppIcon.Search, MiuixTheme.colorScheme.onSurfaceSecondary, modifier = Modifier.padding(bottom = 12.dp).size(28.dp))
                        Label("暂无符合条件的地震事件", MiuixTheme.colorScheme.onSurface, 17.sp, bold = true)
                        Label("可调整筛选或刷新目录。暂无事件不代表安全。", MiuixTheme.colorScheme.onSurfaceSecondary, 14.sp,
                            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
                        Button(onClick = onRefresh) {
                            LucideIcon(AppIcon.Refresh, MiuixTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("刷新目录", style = MiuixTheme.textStyles.button)
                        }
                    }
                }
            } else {
                items(filtered, key = { it.identity }, contentType = { "event" }) { event ->
                    EarthquakeTile(event, dark, { onSelect(event) },
                        modifier = Modifier.height(144.dp), standard = standard)
                }
            }
        }
    }
}
