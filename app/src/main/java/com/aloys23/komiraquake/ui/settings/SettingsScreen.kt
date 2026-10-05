package com.aloys23.komiraquake.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aloys23.komiraquake.core.ClockInfo
import com.aloys23.komiraquake.core.ClockState
import com.aloys23.komiraquake.core.IntensityStandard
import com.aloys23.komiraquake.data.prefs.Settings
import com.aloys23.komiraquake.data.prefs.ThemeMode
import com.aloys23.komiraquake.model.DataSourceInfo
import com.aloys23.komiraquake.model.WarningLevel
import com.aloys23.komiraquake.service.LocationState
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import com.aloys23.komiraquake.ui.components.*
import com.aloys23.komiraquake.ui.map.Basemaps
import com.aloys23.komiraquake.ui.theme.AppSurfaces
import com.aloys23.komiraquake.ui.theme.SeismicColors
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TextField

/** 设置页。《NATIVE_PORT_SPEC》 §7 / §11。 */
@Composable
fun SettingsScreen(
    settings: Settings,
    location: LocationState,
    sourceInfos: List<DataSourceInfo>,
    clockInfo: ClockInfo,
    dark: Boolean,
    onUpdate: ((Settings) -> Settings) -> Unit,
    onRequestLocation: () -> Unit,
    onSetManualLocation: (Double, Double) -> Unit,
    onSampleSpeech: () -> Unit,
    onRefreshClock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(AppSurfaces.surface(dark)), contentAlignment = Alignment.TopCenter) {
    Column(
        modifier = Modifier.widthIn(max = 760.dp).fillMaxSize()
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        PageHeader("设置", "定位、预警与显示偏好", AppIcon.Settings, dark)
        SectionTitle("定位与基准点", dark)
        Card(dark) {
            val sourceDesc = when (location.source) {
                com.aloys23.komiraquake.service.LocationSource.NATIVE -> "设备定位 (GPS · 精确)"
                com.aloys23.komiraquake.service.LocationSource.IP_FALLBACK -> "IP 定位 · 粗略估算 (可能存在数十公里偏差)"
                com.aloys23.komiraquake.service.LocationSource.MANUAL -> "手动定位 · 自定义坐标"
                else -> "未设置定位"
            }
            Label("当前位置：${location.name}", AppSurfaces.onSurface(dark), size = 14.sp, bold = true)
            Spacer(Modifier.height(2.dp))
            Label(sourceDesc, AppSurfaces.outline(dark), size = 11.sp)
            Spacer(Modifier.height(10.dp))
            AppButton("重新获取 IP 定位", dark, onRequestLocation, icon = AppIcon.Locate)

            Spacer(Modifier.height(12.dp))
            Label("快捷预设城市 (点击一键设为基准点)", AppSurfaces.onSurface(dark), size = 12.sp, bold = true)
            Spacer(Modifier.height(6.dp))

            val presetCities = listOf(
                "成都" to (30.6586 to 104.0648),
                "重庆" to (29.5630 to 106.5516),
                "昆明" to (25.0453 to 102.7097),
                "大理" to (25.6065 to 100.2676),
                "西安" to (34.3416 to 108.9398),
                "兰州" to (36.0611 to 103.8343),
                "西宁" to (36.6232 to 101.7789),
                "乌鲁木齐" to (43.8256 to 87.6168),
                "拉萨" to (29.6525 to 91.1721),
                "北京" to (39.9042 to 116.4074),
                "上海" to (31.2304 to 121.4737),
                "广州" to (23.1291 to 113.2644),
                "深圳" to (22.5431 to 114.0579),
                "台北" to (25.0330 to 121.5654),
                "花莲" to (23.9871 to 121.6016),
            )

            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                items(presetCities.size) { i ->
                    val (city, coords) = presetCities[i]
                    val isCurrent = location.name.contains(city)
                    StandardChip(city, isCurrent, dark) {
                        onSetManualLocation(coords.first, coords.second)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            var latText by remember(location.latitude) { mutableStateOf(location.latitude?.toString() ?: "") }
            var lonText by remember(location.longitude) { mutableStateOf(location.longitude?.toString() ?: "") }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = latText,
                    onValueChange = { latText = it },
                    label = "纬度 (°N)",
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = lonText,
                    onValueChange = { lonText = it },
                    label = "经度 (°E)",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
            val latitude = latText.toDoubleOrNull()
            val longitude = lonText.toDoubleOrNull()
            AppButton("应用精确经纬度", dark, onClick = {
                if (latitude != null && longitude != null) onSetManualLocation(latitude, longitude)
            }, icon = AppIcon.Check, primary = true,
                enabled = latitude != null && longitude != null && latitude in -90.0..90.0 && longitude in -180.0..180.0)
            Label("WGS84 · 纬度 −90～90，经度 −180～180", AppSurfaces.outline(dark), 12.sp,
                modifier = Modifier.padding(top = 8.dp))
        }

        Spacer(Modifier.height(14.dp))
        SectionTitle("预警", dark)
        Card(dark) {
            SwitchRow("地震预警", settings.enableWarnings, dark) { checked ->
                onUpdate { it.copy(enableWarnings = checked) }
            }
            Label(
                "开启后按本地烈度过滤决定是否提醒；关闭时地震事件仅展示，不产生声音、语音、震动或全屏预警。",
                AppSurfaces.outline(dark), 12.sp,
            )
            Spacer(Modifier.height(12.dp))
            Label("烈度标准", AppSurfaces.onSurface(dark), size = 13.sp)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StandardChip("中国烈度 (CSIS)", settings.intensityStandard == IntensityStandard.CSIS, dark) {
                    onUpdate { it.copy(intensityStandard = IntensityStandard.CSIS) }
                }
                StandardChip("日本震度 (JMA)", settings.intensityStandard == IntensityStandard.JMA, dark) {
                    onUpdate { it.copy(intensityStandard = IntensityStandard.JMA) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Label(
                if (settings.localIntensityFilter <= 0.0) "本地烈度过滤：关闭"
                else "本地烈度过滤：%.1f 度".format(settings.localIntensityFilter),
                AppSurfaces.onSurface(dark), size = 13.sp,
            )
            Slider(
                value = settings.localIntensityFilter.toFloat(),
                onValueChange = { v -> onUpdate { it.copy(localIntensityFilter = v.toDouble()) } },
                valueRange = 0f..8f,
                steps = 15,
            )
            Label("仅当本地预估烈度达到该值时提醒；0 表示不作筛选。", AppSurfaces.outline(dark), 12.sp)
        }

        Spacer(Modifier.height(14.dp))
        SectionTitle("音效与语音", dark)
        Card(dark) {
            SwitchRow("防灾警报音效", settings.enableSoundAlert, dark) { checked ->
                onUpdate { it.copy(enableSoundAlert = checked) }
            }
            SwitchRow("全局静音", settings.isMuted, dark) { checked -> onUpdate { it.copy(isMuted = checked) } }
            Spacer(Modifier.height(6.dp))
            Label("音量：%.2f".format(settings.alertVolume), AppSurfaces.onSurface(dark), size = 13.sp)
            Slider(
                value = settings.alertVolume.toFloat(),
                onValueChange = { v -> onUpdate { it.copy(alertVolume = v.toDouble()) } },
                valueRange = 0f..1f,
                steps = 9,
            )
            SwitchRow("语音播报 (TTS)", settings.enableSpeech, dark) { checked ->
                onUpdate { it.copy(enableSpeech = checked) }
            }
            SwitchRow("语音报倒计时", settings.speakCountdown, dark) { checked ->
                onUpdate { it.copy(speakCountdown = checked) }
            }
            SwitchRow("播报更新报", settings.speakUpdates, dark) { checked ->
                onUpdate { it.copy(speakUpdates = checked) }
            }
            Spacer(Modifier.height(6.dp))
            AppButton("试听", dark, onSampleSpeech, icon = AppIcon.Play)
        }

        Spacer(Modifier.height(14.dp))
        SectionTitle("外观与提醒", dark)
        Card(dark) {
            Label("主题模式", AppSurfaces.onSurface(dark), size = 13.sp)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ThemeMode.SYSTEM to "跟随系统", ThemeMode.LIGHT to "浅色", ThemeMode.DARK to "深色").forEach { (mode, title) ->
                    StandardChip(title, settings.themeMode == mode, dark) {
                        onUpdate { it.copy(themeMode = mode) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            AppSwitchRow("背景模糊", settings.backgroundBlur, dark,
                onChange = { checked -> onUpdate { it.copy(backgroundBlur = checked) } },
                summary = "仅模糊地图浮层背后的内容；关闭或不支持时使用实色表面。")
            SwitchRow("减弱动态效果", settings.reduceMotion, dark) { checked ->
                onUpdate { it.copy(reduceMotion = checked) }
            }
        }

        Spacer(Modifier.height(14.dp))
        PermissionSection(
            settings = settings,
            dark = dark,
            onUpdate = onUpdate,
        )

        Spacer(Modifier.height(14.dp))
        SectionTitle("地图底图", dark)
        Card(dark) {
            Basemaps.forEach { map ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StandardChip(map.name, settings.basemapId == map.id, dark) {
                        onUpdate { it.copy(basemapId = map.id) }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        SectionTitle("数据源", dark)
        Card(dark) {
            sourceInfos.forEachIndexed { index, info ->
                if (index > 0) Spacer(Modifier.height(12.dp))
                SwitchRow("${info.name} 数据源", settings.enabled(info.id), dark) { checked ->
                    onUpdate {
                        it.copy(enabledSources = if (checked) it.enabledSources + info.id else it.enabledSources - info.id)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Label(
                    "连接：${info.statusLabel}" + (info.latencyMs?.let { " · 延迟 $it ms" } ?: ""),
                    AppSurfaces.onSurface(dark), size = 12.sp, bold = true,
                )
                Label(directoryStatusText(info), AppSurfaces.outline(dark), size = 11.sp)
                Label(info.description, AppSurfaces.outline(dark), size = 11.sp)
            }
        }

        Spacer(Modifier.height(14.dp))
        SectionTitle("时间校准", dark)
        Card(dark) {
            SwitchRow("网络校时 (SNTP)", settings.enableNtpSync, dark) { checked ->
                onUpdate { it.copy(enableNtpSync = checked) }
            }
            Spacer(Modifier.height(8.dp))
            Label(clockStatusText(clockInfo), AppSurfaces.outline(dark), size = 12.sp)
            Label(clockDetailText(clockInfo), AppSurfaces.outline(dark), size = 11.sp)
            Spacer(Modifier.height(10.dp))
            var ntpText by remember(settings.customNtpServer) { mutableStateOf(settings.customNtpServer) }
            TextField(
                value = ntpText,
                onValueChange = { ntpText = it },
                label = "自定义 NTP 服务器（留空用默认）",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            AppButton("应用并重新校时", dark, onClick = {
                onUpdate { it.copy(customNtpServer = ntpText.trim()) }
                onRefreshClock()
            }, icon = AppIcon.Check)
            Spacer(Modifier.height(6.dp))
            Label(
                "默认顺序：ntp.aliyun.com / ntp1.aliyun.com / ntp.tencent.com / pool.ntp.org / time.apple.com。自定义主机将优先尝试。",
                AppSurfaces.outline(dark), 11.sp,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
    }
}

private fun Settings.enabled(sourceId: String): Boolean = enabledSources.contains(sourceId)

/** 目录（HTTP 列表）链路一行文案：状态 + 延迟 + 错误。 */
private fun directoryStatusText(info: DataSourceInfo): String {
    val base = when (info.directoryStatus) {
        com.aloys23.komiraquake.model.ConnectionStatus.CONNECTED -> "目录：刷新正常"
        com.aloys23.komiraquake.model.ConnectionStatus.CONNECTING -> "目录：刷新中"
        com.aloys23.komiraquake.model.ConnectionStatus.ERROR -> "目录：刷新失败"
        com.aloys23.komiraquake.model.ConnectionStatus.DISCONNECTED -> "目录：未刷新"
    }
    val latency = info.directoryLatencyMs?.let { " · $it ms" } ?: ""
    val error = info.directoryError?.let { " · $it" } ?: ""
    return base + latency + error
}

/** 校时状态一行文案。《NATIVE_PORT_SPEC》 §13.4。 */
private fun clockStatusText(info: ClockInfo): String = when {
    !info.enabled -> "已关闭 · 使用系统本地时钟"
    info.state == ClockState.SYNCED -> "已校准 · 系统时钟偏差 %+d ms".format(info.offsetMs)
    info.state == ClockState.STALE -> "校准过期 · 沿用上次偏差 %+d ms".format(info.offsetMs)
    else -> "尚未完成网络校时 · 使用系统本地时钟"
}

private fun clockDetailText(info: ClockInfo): String = when {
    !info.enabled -> "开启后以网络时间作为倒计时与走时反解的基准"
    info.sourceLabel.isEmpty() -> "等待 SNTP / HTTP 授时"
    else -> "%s · 往返 %d ms".format(info.sourceLabel, info.delayMs)
}

@Composable
internal fun SectionTitle(text: String, dark: Boolean) = SectionHeader(text, dark)

@Composable
internal fun Card(dark: Boolean, content: @Composable () -> Unit) {
    AppCard(dark) { content() }
}

@Composable
internal fun SwitchRow(title: String, checked: Boolean, dark: Boolean, onChange: (Boolean) -> Unit) {
    AppSwitchRow(title, checked, dark, onChange)
}

@Composable
private fun StandardChip(text: String, selected: Boolean, dark: Boolean, onClick: () -> Unit) {
    AppChip(text, selected, dark, onClick)
}
