package com.aloys23.komiraquake.ui.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aloys23.komiraquake.data.prefs.Settings
import com.aloys23.komiraquake.data.prefs.ThemeMode
import com.aloys23.komiraquake.ui.components.AppCard
import com.aloys23.komiraquake.ui.components.AppChip
import com.aloys23.komiraquake.ui.components.AppSwitchRow
import com.aloys23.komiraquake.ui.components.Label
import com.aloys23.komiraquake.ui.components.SectionHeader
import com.aloys23.komiraquake.ui.map.Basemaps
import com.aloys23.komiraquake.ui.theme.AppSurfaces

/** 「外观」分区：主题模式、背景模糊、减弱动效与地图底图。 */
@Composable
internal fun AppearanceSection(
    settings: Settings,
    dark: Boolean,
    onUpdate: ((Settings) -> Settings) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SectionHeader("主题", dark)
        AppCard(dark) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    ThemeMode.SYSTEM to "跟随系统",
                    ThemeMode.LIGHT to "浅色",
                    ThemeMode.DARK to "深色",
                ).forEach { (mode, title) ->
                    AppChip(title, settings.themeMode == mode, dark,
                        onClick = { onUpdate { it.copy(themeMode = mode) } })
                }
            }
            Spacer(Modifier.height(6.dp))
            Label(
                "「跟随系统」随手机的深色模式设置自动切换。",
                AppSurfaces.outline(dark), 11.sp,
            )
        }

        Spacer(Modifier.height(14.dp))
        SectionHeader("显示", dark)
        AppCard(dark) {
            AppSwitchRow(
                "背景模糊", settings.backgroundBlur, dark,
                onChange = { checked -> onUpdate { it.copy(backgroundBlur = checked) } },
                summary = "仅模糊地图浮层背后的内容；关闭或不支持时使用实色表面。",
            )
            AppSwitchRow(
                "减弱动态效果", settings.reduceMotion, dark,
                onChange = { checked -> onUpdate { it.copy(reduceMotion = checked) } },
                summary = "停用装饰过渡。真实波前、预计倒计时和数据更新不受影响。",
            )
        }

        Spacer(Modifier.height(14.dp))
        SectionHeader("地图底图", dark)
        AppCard(dark) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Basemaps.forEach { map ->
                    AppChip(map.name, settings.basemapId == map.id, dark,
                        onClick = { onUpdate { it.copy(basemapId = map.id) } })
                }
            }
        }
    }
}
