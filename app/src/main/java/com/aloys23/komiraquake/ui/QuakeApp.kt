package com.aloys23.komiraquake.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aloys23.komiraquake.AppContainer
import com.aloys23.komiraquake.service.GuardService
import com.aloys23.komiraquake.ui.components.*
import com.aloys23.komiraquake.ui.list.EventDetailScreen
import com.aloys23.komiraquake.ui.list.EventListScreen
import com.aloys23.komiraquake.ui.map.MapScreen
import com.aloys23.komiraquake.ui.map.TileLoader
import com.aloys23.komiraquake.ui.map.Basemaps
import com.aloys23.komiraquake.ui.map.basemapById
import com.aloys23.komiraquake.ui.settings.SettingsScreen
import com.aloys23.komiraquake.ui.theme.LocalAppDark
import com.aloys23.komiraquake.ui.theme.LocalReduceMotion
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Three sections, one map-only backdrop, and an independent solid emergency surface. */
@Composable
fun QuakeApp(container: AppContainer) {
    val dark = LocalAppDark.current
    val context = LocalContext.current
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val history by container.repository.historyEvents.collectAsStateWithLifecycle()
    val eventList by container.repository.eventList.collectAsStateWithLifecycle()
    val activeWarning by container.repository.activeWarning.collectAsStateWithLifecycle()
    val activeWarnings by container.repository.activeWarnings.collectAsStateWithLifecycle()
    val hudIndex by container.repository.hudIndex.collectAsStateWithLifecycle()
    val hudCount by container.repository.hudCount.collectAsStateWithLifecycle()
    val mapFocus by container.repository.mapFocus.collectAsStateWithLifecycle()
    val overlayVisible by container.repository.warningOverlayVisible.collectAsStateWithLifecycle()
    val cameraRequest by container.repository.mapCameraRequest.collectAsStateWithLifecycle()
    val countdown by container.countdown.collectAsStateWithLifecycle()
    val location by container.location.state.collectAsStateWithLifecycle()
    val sourceInfo by container.repository.sourceInfo.collectAsStateWithLifecycle()
    val sourceInfos by container.repository.sourceInfos.collectAsStateWithLifecycle()
    val clockInfo by container.clockInfo.collectAsStateWithLifecycle()
    val selectedEvent = mapFocus ?: activeWarning ?: history.firstOrNull()
    // 显式焦点：活跃预警或用户点选。只有此时才自动取景震中并画波前；history 回退的最近事件只画 X。
    val mapHasFocus = mapFocus != null || activeWarning != null
    // 波前走完后收起左上角 HUD；新的波前或切换到其它事件时恢复。
    var wavesDoneId by remember { mutableStateOf<String?>(null) }
    val tileLoader = remember { TileLoader(container.tileClient) }
    var section by remember { mutableIntStateOf(0) }
    // 详情页按 identity 从最新列表解析，源更新后详情内容随之刷新。
    var detailId by remember { mutableStateOf<String?>(null) }
    val detailEvent = detailId?.let { id -> eventList.firstOrNull { it.identity == id } }
    val density = LocalDensity.current
    var topOcclusion by remember { mutableStateOf(280.dp) }
    // 全屏沉浸：内容铺满整屏，只有浮层/HUD 自己让开系统栏。
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val backdrop = rememberLayerBackdrop()
    val view = LocalView.current
    val blurEnabled = canUseMapBlur(settings.backgroundBlur, view.isHardwareAccelerated, isRuntimeShaderSupported())
    val glass = remember(backdrop, blurEnabled) { MapGlassState(backdrop, blurEnabled) }
    // 详情页自带一张地图，独立 backdrop，避免与主地图共用图层相互覆盖。
    val detailBackdrop = rememberLayerBackdrop()
    val detailGlass = remember(detailBackdrop, blurEnabled) { MapGlassState(detailBackdrop, blurEnabled) }
    // 常驻的内容 backdrop，专给悬浮底栏：切页时它不 detach。
    // miuix 的 layerBackdrop 在 detach 时会把图层坐标置空，而 drawBackdrop 遇到空坐标直接不画，
    // 于是每次切页玻璃都会有一帧画不出来并多触发两次状态失效——这就是切页卡顿的根因。
    val barBackdrop = rememberLayerBackdrop()
    var barReady by remember { mutableStateOf(false) }
    // 三页常驻的 Pager：切换时页面不销毁重组，也就没有每次切页的首帧卡顿。
    val pagerState = rememberPagerState(pageCount = { 3 })
    val navigateScope = rememberCoroutineScope()
    val navigateTo: (Int) -> Unit = { index ->
        if (index != section) {
            section = index
            navigateScope.launch { pagerState.animateScrollToPage(index) }
        }
    }

    LaunchedEffect(settings.enableBackgroundGuard) {
        if (settings.enableBackgroundGuard) GuardService.start(context) else GuardService.stop(context)
    }
    CompositionLocalProvider(LocalReduceMotion provides settings.reduceMotion) {
        Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
            Box(
                Modifier.fillMaxSize()
                    .then(if (glass.enabled) Modifier.layerBackdrop(barBackdrop) else Modifier)
                    .onGloballyPositioned { if (glass.enabled) barReady = true },
            ) {
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = false,
                    beyondViewportPageCount = 2,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    when (page) {
                        0 -> CompositionLocalProvider(LocalMapGlass provides glass) {
                            BoxWithConstraints(Modifier.fillMaxSize()) {
                                MapScreen(
                                    userLat = location.latitude, userLon = location.longitude, dark = dark,
                                    basemap = basemapById(settings.basemapId), tileLoader = tileLoader,
                                    focusEvent = selectedEvent, cameraRequest = cameraRequest,
                                    onCycleBasemap = {
                                        val index = Basemaps.indexOfFirst { it.id == settings.basemapId }
                                        val next = Basemaps[(index + 1) % Basemaps.size]
                                        container.settings.update { it.copy(basemapId = next.id) }
                                    },
                                    // 只有活跃预警或用户显式焦点才取景震中并画波前圆；history 回退的最近事件只画 X。
                                    waveEligible = mapHasFocus,
                                    hasFocus = mapHasFocus,
                                    warningActive = activeWarning != null,
                                    topOcclusion = topOcclusion, modifier = Modifier.fillMaxSize(),
                                    onWavesStarted = { wavesDoneId = null },
                                    onWavesFinished = { wavesDoneId = selectedEvent?.identity },
                                    active = section == 0,
                                )
                                // One measured, scrollable stack: no fixed offsets that collide at large font scales.
                                Column(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp).widthIn(max = 420.dp).fillMaxWidth()
                                    .heightIn(max = maxHeight * 0.52f)
                                    .onSizeChanged { topOcclusion = with(density) { it.height.toDp() } + 24.dp }
                                    .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    // 首页顶部只保留地震 HUD；标题/状态栏、预警引导与无事件占位均已移除。
                                    val hud = selectedEvent?.takeIf { it.identity != wavesDoneId }
                                    if (hud != null) {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            QuakeHudCard(hud, dark, standard = settings.intensityStandard,
                                                isActive = activeWarnings.any { it.identity == hud.identity })
                                            if (hudCount > 1) {
                                                Row(Modifier.fillMaxWidth().mapGlass(dark).padding(horizontal = 12.dp, vertical = 4.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    IconButton(onClick = { container.repository.previousWarning() }) {
                                                        LucideIcon(AppIcon.ChevronLeft, MiuixTheme.colorScheme.primary, "上一个事件")
                                                    }
                                                    Label("${hudIndex + 1} / $hudCount", MiuixTheme.colorScheme.onSurfaceSecondary, 12.sp, modifier = Modifier.weight(1f))
                                                    IconButton(onClick = { container.repository.nextWarning() }) {
                                                        LucideIcon(AppIcon.ChevronRight, MiuixTheme.colorScheme.primary, "下一个事件")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                Column(Modifier.align(Alignment.BottomStart)
                                    .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 104.dp + navBottom)
                                    .fillMaxWidth(0.68f).widthIn(max = 360.dp)
                                    .heightIn(max = maxHeight * 0.3f).verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    // 左下角数据源状态（对齐桌面端）：时钟徽章之上。
                                    SourceStatusLabel(sourceInfo, dark)
                                    NtpClockLabel(clockInfo, dark)
                                }
                            }
                        }
                        1 -> EventListScreen(eventList, dark, location.hasLocation,
                            { detailId = it.identity },
                            standard = settings.intensityStandard,
                            onRefresh = { container.repository.refreshCatalog() })
                        else -> SettingsScreen(settings, location, sourceInfos, clockInfo, dark,
                            onUpdate = { container.settings.update(it) },
                            onRequestLocation = { container.location.requestCurrentPosition() },
                            onSetManualLocation = { lat, lon -> container.location.setManual(lat, lon) },
                            onRefreshClock = { container.ntp.refresh() },
                            onLoginJian = { key -> container.repository.loginJian(key) },
                            onSaveWhewsToken = { token ->
                                container.settings.update { it.copy(whewsToken = token) }
                            },
                            onSaveSimulatedUrl = { url ->
                                container.settings.update { it.copy(simulatedUrl = url) }
                            },
                            onResetDefaults = { container.settings.resetToDefaults() },
                            onExit = { navigateTo(0) },
                            active = section == 2)
                    }
                }
            }
            // 底栏必须在 barBackdrop 图层之外：它在图层内采样该图层会让渲染树成环，原生栈溢出崩溃。
            AppNavigation(
                selected = section, backdrop = barBackdrop, blurEnabled = glass.enabled && barReady,
                onSelect = navigateTo,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(start = 40.dp, end = 40.dp, bottom = 14.dp)
                    // 平板/大屏上不再无限拉长：悬浮胶囊限制在手机级宽度并居中。
                    .widthIn(max = 480.dp),
            )
            // 详情页盖住底栏，但让位于全屏预警。
            detailEvent?.let { event ->
                CompositionLocalProvider(LocalMapGlass provides detailGlass) {
                    EventDetailScreen(
                        event = event, dark = dark, standard = settings.intensityStandard,
                        userLat = location.latitude, userLon = location.longitude,
                        basemap = basemapById(settings.basemapId), tileLoader = tileLoader,
                        onBack = { detailId = null }, modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (overlayVisible) {
                WarningOverlay(activeWarning, countdown, { container.collapseAlert() }, dark = dark,
                    reduceMotion = settings.reduceMotion, standard = settings.intensityStandard,
                    onMute = { container.muteAlert() }, onStop = { container.stopAlert() })
            }
        }
    }
}

/**
 * 悬浮液态玻璃底栏：中性配色，选中项是一枚可拖动的折射胶囊——点按即时切换，
 * 横向拖动跟手滑动，松手吸附到最近项。backdrop 未就绪或不支持时回退为实色胶囊。
 */
@Composable
private fun AppNavigation(
    selected: Int, backdrop: Backdrop, blurEnabled: Boolean,
    onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
) {
    val items = listOf("地图" to AppIcon.Map, "列表" to AppIcon.List, "设置" to AppIcon.Settings)
    FloatingBottomBar(
        modifier = modifier,
        selectedIndex = selected,
        onSelected = onSelect,
        backdrop = backdrop,
        tabsCount = items.size,
        isBlurEnabled = blurEnabled,
    ) { activateTab ->
        items.forEachIndexed { index, (label, icon) ->
            FloatingBottomBarItem(
                selected = selected == index,
                onClick = { activateTab(index) },
            ) {
                val tint = LocalBottomBarContentColor.current
                LucideIcon(icon, tint)
                Label(label, tint, 11.sp, bold = selected == index, maxLines = 1)
            }
        }
    }
}
