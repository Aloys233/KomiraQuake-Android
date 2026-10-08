package com.aloys23.komiraquake.ui.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aloys23.komiraquake.data.prefs.Settings
import com.aloys23.komiraquake.data.prefs.ThemeMode
import com.aloys23.komiraquake.ui.components.Label
import com.aloys23.komiraquake.ui.map.Basemaps
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val THEME_MODES = listOf(
    ThemeMode.SYSTEM to "跟随系统",
    ThemeMode.LIGHT to "浅色",
    ThemeMode.DARK to "深色",
)

/** 「外观」分区：主题模式、背景模糊、减弱动效与地图底图。 */
@Composable
internal fun AppearanceSection(
    settings: Settings,
    dark: Boolean,
    onUpdate: ((Settings) -> Settings) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SmallTitle("主题")
        Card(insideMargin = PaddingValues(12.dp)) {
            TabRowWithContour(
                tabs = THEME_MODES.map { it.second },
                selectedTabIndex = THEME_MODES.indexOfFirst { it.first == settings.themeMode }.coerceAtLeast(0),
                onTabSelected = { index -> onUpdate { it.copy(themeMode = THEME_MODES[index].first) } },
            )
            Spacer(Modifier.height(6.dp))
            Label(
                "「跟随系统」随手机的深色模式设置自动切换。",
                MiuixTheme.colorScheme.onSurfaceSecondary, 11.sp,
            )
        }

        Spacer(Modifier.height(14.dp))
        SmallTitle("显示")
        Card {
            SwitchPreference(
                title = "背景模糊",
                summary = "模糊地图浮层与悬浮底栏背后的内容；关闭或不支持时使用实色表面。",
                checked = settings.backgroundBlur,
                onCheckedChange = { checked -> onUpdate { it.copy(backgroundBlur = checked) } },
            )
            SwitchPreference(
                title = "减弱动态效果",
                summary = "停用装饰过渡。真实波前、预计倒计时和数据更新不受影响。",
                checked = settings.reduceMotion,
                onCheckedChange = { checked -> onUpdate { it.copy(reduceMotion = checked) } },
            )
        }

        Spacer(Modifier.height(14.dp))
        SmallTitle("地图底图")
        Card(insideMargin = PaddingValues(12.dp)) {
            TabRowWithContour(
                tabs = Basemaps.map { it.name },
                selectedTabIndex = Basemaps.indexOfFirst { it.id == settings.basemapId }.coerceAtLeast(0),
                onTabSelected = { index -> onUpdate { it.copy(basemapId = Basemaps[index].id) } },
            )
        }
    }
}
