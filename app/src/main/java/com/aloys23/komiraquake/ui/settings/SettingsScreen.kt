package com.aloys23.komiraquake.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class SettingsCategory(
    val title: String,
    val caption: String,
    val icon: AppIcon,
)

private val SETTINGS_CATEGORIES = listOf(
    SettingsCategory("预警提醒", "重要提醒", AppIcon.Bell),
    SettingsCategory("定位", "我的位置", AppIcon.MapPin),
    SettingsCategory("外观", "显示界面", AppIcon.Sun),
    SettingsCategory("数据源", "连接来源", AppIcon.Radio),
    SettingsCategory("系统", "应用行为", AppIcon.Sliders),
)

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
            SettingsCategoryStrip(
                selectedIndex = tab,
                onSelected = { tab = it },
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
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        if (settings.reduceMotion) {
                            EnterTransition.None togetherWith ExitTransition.None
                        } else {
                            val direction = if (targetState > initialState) 1 else -1
                            (fadeIn() + slideInHorizontally { it / 4 * direction }) togetherWith
                                (fadeOut() + slideOutHorizontally { -it / 4 * direction })
                        }
                    },
                    label = "settings-section-transition",
                ) { selectedTab ->
                    when (selectedTab) {
                        0 -> WarningSection(
                            settings = settings, dark = dark, onUpdate = onUpdate,
                        )
                        1 -> LocationSection(
                            location = location, dark = dark,
                            onRequestLocation = onRequestLocation,
                            onSetManualLocation = onSetManualLocation,
                        )
                        2 -> AppearanceSection(settings = settings, dark = dark, onUpdate = onUpdate)
                        3 -> SourceSection(
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

/**
 * 设置分类导航：窄屏横向滚动，宽屏仍保持紧凑；每项同时展示「是什么」和「管什么」。
 * 使用 selectable 保留 Tab 语义，让 TalkBack 能识别当前选中项。
 */
@Composable
private fun SettingsCategoryStrip(
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SETTINGS_CATEGORIES.forEachIndexed { index, category ->
            val selected = index == selectedIndex
            val container by animateColorAsState(
                targetValue = if (selected) {
                    MiuixTheme.colorScheme.primary.copy(alpha = 0.13f)
                } else {
                    MiuixTheme.colorScheme.surfaceContainer
                },
                label = "settings-category-container",
            )
            val outline by animateColorAsState(
                targetValue = if (selected) {
                    MiuixTheme.colorScheme.primary.copy(alpha = 0.65f)
                } else {
                    MiuixTheme.colorScheme.outline.copy(alpha = 0.28f)
                },
                label = "settings-category-outline",
            )
            val iconContainer = if (selected) {
                MiuixTheme.colorScheme.primary
            } else {
                MiuixTheme.colorScheme.onSurfaceSecondary.copy(alpha = 0.12f)
            }
            Box(
                modifier = Modifier
                    .width(112.dp)
                    .height(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(container)
                    .border(1.dp, outline, RoundedCornerShape(16.dp))
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        onClick = { onSelected(index) },
                    )
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(iconContainer, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        LucideIcon(
                            category.icon,
                            if (selected) MiuixTheme.colorScheme.onPrimary
                            else MiuixTheme.colorScheme.onSurfaceSecondary,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                    Column {
                        Label(
                            category.title,
                            if (selected) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onSurface,
                            size = 13.sp,
                            bold = selected,
                            maxLines = 1,
                        )
                        Label(
                            category.caption,
                            MiuixTheme.colorScheme.onSurfaceSecondary,
                            size = 10.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
