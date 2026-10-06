package com.aloys23.komiraquake.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aloys23.komiraquake.AppContainer
import com.aloys23.komiraquake.model.WarningLevel
import com.aloys23.komiraquake.service.GuardService
import com.aloys23.komiraquake.ui.components.*
import com.aloys23.komiraquake.ui.list.EventListScreen
import com.aloys23.komiraquake.ui.map.MapScreen
import com.aloys23.komiraquake.ui.map.MapLegend
import com.aloys23.komiraquake.ui.map.TileLoader
import com.aloys23.komiraquake.ui.map.basemapById
import com.aloys23.komiraquake.ui.settings.SettingsScreen
import com.aloys23.komiraquake.ui.theme.AppSurfaces
import com.aloys23.komiraquake.ui.theme.LocalAppDark
import com.aloys23.komiraquake.ui.theme.LocalReduceMotion
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

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
    // 波前走完后收起左上角 HUD；新的波前或切换到其它事件时恢复。
    var wavesDoneId by remember { mutableStateOf<String?>(null) }
    val tileLoader = remember { TileLoader(container.tileClient) }
    var section by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    var topOcclusion by remember { mutableStateOf(280.dp) }

    val backdrop = rememberLayerBackdrop()
    val view = LocalView.current
    val blurEnabled = canUseMapBlur(settings.backgroundBlur, view.isHardwareAccelerated, isRuntimeShaderSupported())
    val glass = remember(backdrop, blurEnabled) { MapGlassState(backdrop, blurEnabled) }

    LaunchedEffect(settings.enableBackgroundGuard) {
        if (settings.enableBackgroundGuard) GuardService.start(context) else GuardService.stop(context)
    }
    CompositionLocalProvider(LocalReduceMotion provides settings.reduceMotion) {
        Box(Modifier.fillMaxSize().background(AppSurfaces.surface(dark))) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Box(Modifier.weight(1f)) {
                    when (section) {
                        0 -> CompositionLocalProvider(LocalMapGlass provides glass) {
                            BoxWithConstraints(Modifier.fillMaxSize()) {
                                MapScreen(
                                    userLat = location.latitude, userLon = location.longitude, dark = dark,
                                    basemap = basemapById(settings.basemapId), tileLoader = tileLoader,
                                    focusEvent = selectedEvent, cameraRequest = cameraRequest,
                                    // 只有活跃预警或用户显式焦点才画波前圆；history 回退的最近事件只画 X。
                                    waveEligible = mapFocus != null || activeWarning != null,
                                    warningActive = activeWarning != null,
                                    topOcclusion = topOcclusion, modifier = Modifier.fillMaxSize(),
                                    onWavesStarted = { wavesDoneId = null },
                                    onWavesFinished = { wavesDoneId = selectedEvent?.identity },
                                )
                                // One measured, scrollable stack: no fixed offsets that collide at large font scales.
                                Column(Modifier.align(Alignment.TopStart).padding(12.dp).widthIn(max = 420.dp).fillMaxWidth()
                                    .heightIn(max = maxHeight * 0.52f)
                                    .onSizeChanged { topOcclusion = with(density) { it.height.toDp() } + 24.dp }
                                    .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    MapStatusBar(statusText(sourceInfo.status), statusLevel(sourceInfo.status),
                                        if (location.hasLocation) location.name else "定位未知", dark,
                                        onOpenList = { section = 1 }, onOpenSettings = { section = 2 })
                                    // 预警默认关闭，但必须让新装用户第一眼就看到这件事，
                                    // 否则「装了却不报警」会被当成故障。
                                    if (!settings.enableWarnings && !settings.warningOnboardingDismissed) {
                                        WarningOnboardingCard(
                                            dark = dark,
                                            onEnable = {
                                                container.settings.update { it.copy(enableWarnings = true) }
                                            },
                                            onDismiss = {
                                                container.settings.update { it.copy(warningOnboardingDismissed = true) }
                                            },
                                        )
                                    }
                                    val hud = selectedEvent?.takeIf { it.identity != wavesDoneId }
                                    if (hud == null) {
                                        Row(Modifier.fillMaxWidth().mapGlass(dark).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            LucideIcon(AppIcon.Info, AppSurfaces.outline(dark))
                                            Label("暂无活动预警或目录事件；不代表安全", AppSurfaces.onSurface(dark), modifier = Modifier.weight(1f))
                                        }
                                    } else {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            QuakeHudCard(hud, dark, standard = settings.intensityStandard,
                                                isActive = activeWarnings.any { it.identity == hud.identity })
                                            if (hudCount > 1) {
                                                Row(Modifier.fillMaxWidth().mapGlass(dark).padding(horizontal = 12.dp, vertical = 4.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    AppIconButton(AppIcon.ChevronLeft, "上一个事件", dark,
                                                        onClick = { container.repository.previousWarning() })
                                                    Label("${hudIndex + 1} / $hudCount", AppSurfaces.outline(dark), 12.sp, modifier = Modifier.weight(1f))
                                                    AppIconButton(AppIcon.ChevronRight, "下一个事件", dark,
                                                        onClick = { container.repository.nextWarning() })
                                                }
                                            }
                                        }
                                    }
                                }
                                Column(Modifier.align(Alignment.BottomStart).padding(12.dp).fillMaxWidth(0.68f).widthIn(max = 360.dp)
                                    .heightIn(max = maxHeight * 0.3f).verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (selectedEvent != null) MapLegend(dark)
                                    NtpClockLabel(clockInfo, dark)
                                }
                            }
                        }
                        1 -> EventListScreen(eventList, dark, location.hasLocation,
                            { container.repository.locateEpicenter(it.identity); section = 0 },
                            standard = settings.intensityStandard,
                            isMapFocused = { container.repository.isMapFocused(it) },
                            onToggleFocus = { container.repository.toggleMapFocus(it) },
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
                            onExit = { section = 0 })
                    }
                }
                AppNavigation(section, dark) { section = it }
            }
            if (overlayVisible) {
                WarningOverlay(activeWarning, countdown, { container.collapseAlert() }, dark = dark,
                    reduceMotion = settings.reduceMotion, standard = settings.intensityStandard,
                    onMute = { container.muteAlert() }, onStop = { container.stopAlert() })
            }
        }
    }
}

@Composable
private fun AppNavigation(selected: Int, dark: Boolean, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().background(AppSurfaces.surfaceContainer(dark)).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("地图" to AppIcon.Map, "列表" to AppIcon.List, "设置" to AppIcon.Settings).forEachIndexed { index, (label, icon) ->
            val active = selected == index
            val color = if (active) AppSurfaces.accent(dark) else AppSurfaces.outline(dark)
            Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                .background(if (active) AppSurfaces.accentContainer(dark) else AppSurfaces.surfaceContainer(dark))
                .selectable(active, role = Role.Tab, onClick = { onSelect(index) }).heightIn(min = 64.dp).padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                LucideIcon(icon, color)
                Label(label, color, 12.sp, bold = active)
            }
        }
    }
}

private fun statusText(status: com.aloys23.komiraquake.model.ConnectionStatus): String = when (status) {
    com.aloys23.komiraquake.model.ConnectionStatus.CONNECTED -> "源在线"
    com.aloys23.komiraquake.model.ConnectionStatus.CONNECTING -> "连接中"
    com.aloys23.komiraquake.model.ConnectionStatus.ERROR -> "连接异常"
    com.aloys23.komiraquake.model.ConnectionStatus.DISCONNECTED -> "未连接"
}
private fun statusLevel(status: com.aloys23.komiraquake.model.ConnectionStatus): WarningLevel = when (status) {
    com.aloys23.komiraquake.model.ConnectionStatus.CONNECTED -> WarningLevel.NORMAL
    com.aloys23.komiraquake.model.ConnectionStatus.ERROR -> WarningLevel.WARNING
    else -> WarningLevel.WATCH
}
