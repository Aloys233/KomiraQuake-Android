package com.aloys23.komiraquake.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aloys23.komiraquake.core.ClockInfo
import com.aloys23.komiraquake.data.prefs.Settings
import com.aloys23.komiraquake.model.DataSourceInfo
import com.aloys23.komiraquake.service.LocationState
import com.aloys23.komiraquake.ui.components.AppIcon
import com.aloys23.komiraquake.ui.components.Label
import com.aloys23.komiraquake.ui.components.LucideIcon
import com.aloys23.komiraquake.ui.settings.sections.AppearanceSection
import com.aloys23.komiraquake.ui.settings.sections.LocationSection
import com.aloys23.komiraquake.ui.settings.sections.SourceSection
import com.aloys23.komiraquake.ui.settings.sections.SystemSection
import com.aloys23.komiraquake.ui.settings.sections.WarningSection
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    /** 是否为当前可见页：常驻 Pager 中不可见时不能拦截返回键。 */
    active: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmReset by remember { mutableStateOf(false) }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    BackHandler(enabled = active) {
        if (tab != 0) tab = 0 else onExit()
    }

    Box(
        modifier.fillMaxSize().background(MiuixTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxSize().statusBarsPadding(),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                LucideIcon(AppIcon.Settings, MiuixTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f)) {
                    Label("设置", MiuixTheme.colorScheme.onSurface, 26.sp, bold = true,
                        modifier = Modifier.semantics { heading() })
                    Label("定位、预警与显示偏好", MiuixTheme.colorScheme.onSurfaceSecondary, 13.sp)
                }
            }
            TabRow(
                tabs = SETTINGS_TABS, selectedTabIndex = tab, onTabSelected = { tab = it },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = { confirmReset = true }) {
                    LucideIcon(AppIcon.Refresh, MiuixTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("恢复默认", style = MiuixTheme.textStyles.button)
                }
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
                Spacer(Modifier.height(96.dp + navBottom))
            }
        }
    }

    if (confirmReset) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.48f))
                .clickable(role = Role.Button, onClickLabel = "关闭") { confirmReset = false },
            contentAlignment = Alignment.Center,
        ) {
            Card(
                Modifier.widthIn(max = 420.dp).padding(24.dp).clickable(enabled = false) {},
                insideMargin = PaddingValues(16.dp),
            ) {
                Label("恢复默认设置？", MiuixTheme.colorScheme.onSurface, 18.sp, bold = true)
                Spacer(Modifier.height(8.dp))
                Label(
                    "外观、地图、预警和声音等偏好都将重置为初始值。此操作无法撤销。",
                    MiuixTheme.colorScheme.onSurfaceSecondary, 13.sp,
                )
                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(onClick = { confirmReset = false }, modifier = Modifier.weight(1f)) {
                        Text("取消", style = MiuixTheme.textStyles.button)
                    }
                    Button(
                        onClick = { onResetDefaults(); confirmReset = false },
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        modifier = Modifier.weight(1f),
                    ) {
                        LucideIcon(AppIcon.Refresh, MiuixTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text("恢复默认", style = MiuixTheme.textStyles.button)
                    }
                }
            }
        }
    }
}
