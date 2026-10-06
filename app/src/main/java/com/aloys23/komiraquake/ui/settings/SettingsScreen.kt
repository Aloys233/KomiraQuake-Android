package com.aloys23.komiraquake.ui.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aloys23.komiraquake.core.ClockInfo
import com.aloys23.komiraquake.data.prefs.Settings
import com.aloys23.komiraquake.model.DataSourceInfo
import com.aloys23.komiraquake.service.LocationState
import com.aloys23.komiraquake.ui.components.AppButton
import com.aloys23.komiraquake.ui.components.AppCard
import com.aloys23.komiraquake.ui.components.AppIcon
import com.aloys23.komiraquake.ui.components.AppTabRow
import com.aloys23.komiraquake.ui.components.Label
import com.aloys23.komiraquake.ui.components.LucideIcon
import com.aloys23.komiraquake.ui.components.PageHeader
import com.aloys23.komiraquake.ui.settings.sections.AppearanceSection
import com.aloys23.komiraquake.ui.settings.sections.LocationSection
import com.aloys23.komiraquake.ui.settings.sections.SourceSection
import com.aloys23.komiraquake.ui.settings.sections.SystemSection
import com.aloys23.komiraquake.ui.settings.sections.WarningSection
import com.aloys23.komiraquake.ui.theme.AppSurfaces

private val SETTINGS_TABS = listOf("预警提醒", "定位", "外观", "数据源", "系统")

/**
 * 设置页。按分类 Tab 组织，避免所有设置挤在一页里上下翻找。
 * 《NATIVE_PORT_SPEC》 §7 / §11。
 */
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
    onRefreshClock: () -> Unit,
    onLoginJian: (String) -> Unit,
    onSaveWhewsToken: (String) -> Unit,
    onSaveSimulatedUrl: (String) -> Unit,
    onResetDefaults: () -> Unit,
    /** 系统返回键的最终出口：当前 Tab 不是第一个时先回到第一个 Tab，否则退出设置页。 */
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmReset by remember { mutableStateOf(false) }

    BackHandler(enabled = true) {
        if (tab != 0) tab = 0 else onExit()
    }

    Box(
        modifier.fillMaxSize().background(AppSurfaces.surface(dark)),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxSize(),
        ) {
            PageHeader("设置", "定位、预警与显示偏好", AppIcon.Settings, dark)
            AppTabRow(
                tabs = SETTINGS_TABS, selectedIndex = tab, onSelect = { tab = it }, dark = dark,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                AppButton("恢复默认", dark, { confirmReset = true }, icon = AppIcon.Refresh)
            }

            Column(
                Modifier.widthIn(max = 760.dp).fillMaxSize()
                    .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            ) {
                Spacer(Modifier.height(8.dp))
                when (SETTINGS_TABS[tab]) {
                    "预警提醒" -> WarningSection(
                        settings = settings, dark = dark, onUpdate = onUpdate,
                    )
                    "定位" -> LocationSection(
                        location = location, dark = dark,
                        onRequestLocation = onRequestLocation,
                        onSetManualLocation = onSetManualLocation,
                    )
                    "外观" -> AppearanceSection(settings = settings, dark = dark, onUpdate = onUpdate)
                    "数据源" -> SourceSection(
                        settings = settings, sourceInfos = sourceInfos, dark = dark,
                        onUpdate = onUpdate, onLoginJian = onLoginJian,
                        onSaveWhewsToken = onSaveWhewsToken,
                        onSaveSimulatedUrl = onSaveSimulatedUrl,
                    )
                    else -> SystemSection(
                        settings = settings, clockInfo = clockInfo, dark = dark,
                        onUpdate = onUpdate, onRefreshClock = onRefreshClock,
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (confirmReset) {
        // 项目未引入 material3，且全项目控件都基于 AppSurfaces 自建，这里手搓弹层保持一致。
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.48f))
                .clickable(role = Role.Button, onClickLabel = "关闭") { confirmReset = false },
            contentAlignment = Alignment.Center,
        ) {
            AppCard(dark, Modifier.widthIn(max = 420.dp).padding(24.dp).clickable(enabled = false) {}) {
                Label("恢复默认设置？", AppSurfaces.onSurface(dark), 18.sp, bold = true)
                Spacer(Modifier.height(8.dp))
                Label(
                    "外观、地图、预警和声音等偏好都将重置为初始值。此操作无法撤销。",
                    AppSurfaces.outline(dark), 13.sp,
                )
                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AppButton("取消", dark, { confirmReset = false }, Modifier.weight(1f))
                    AppButton("恢复默认", dark, {
                        onResetDefaults(); confirmReset = false
                    }, Modifier.weight(1f), icon = AppIcon.Refresh, primary = true)
                }
            }
        }
    }
}
