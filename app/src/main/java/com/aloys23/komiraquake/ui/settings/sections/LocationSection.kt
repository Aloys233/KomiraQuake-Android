package com.aloys23.komiraquake.ui.settings.sections

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aloys23.komiraquake.service.LocationSource
import com.aloys23.komiraquake.service.LocationState
import com.aloys23.komiraquake.ui.components.AppIcon
import com.aloys23.komiraquake.ui.components.Label
import com.aloys23.komiraquake.ui.components.LucideIcon
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「定位」分区。
 *
 * 定位链路是 GPS 优先、IP 兜底（见 LocationService.requestCurrentPosition），
 * 因此主按钮是「重新定位」而不是「重新获取 IP 定位」——旧文案与实际行为不符。
 * IP 定位降级为兜底说明，不再占据主操作位。
 * 手动坐标输入收在折叠区：正常使用不需要它，但保留以免丢失该能力。
 */
@Composable
internal fun LocationSection(
    location: LocationState,
    dark: Boolean,
    onRequestLocation: () -> Unit,
    onSetManualLocation: (Double, Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SmallTitle("当前位置")
        Card(insideMargin = PaddingValues(16.dp)) {
            val (sourceTitle, sourceNote, precise) = when (location.source) {
                LocationSource.NATIVE ->
                    Triple("设备定位 (GPS)", "精度取决于系统定位结果", true)
                LocationSource.IP_FALLBACK ->
                    Triple("IP 定位", "粗略估算，可能存在数十公里偏差", false)
                LocationSource.MANUAL ->
                    Triple("手动坐标", "使用下方设置的经纬度", true)
                else -> Triple("未设置定位", "本地烈度与距离无法估算", false)
            }
            Label(
                location.name,
                if (precise) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceSecondary,
                size = 16.sp, bold = true,
            )
            Spacer(Modifier.height(2.dp))
            Label(sourceTitle, MiuixTheme.colorScheme.onSurface, 13.sp, bold = true)
            Spacer(Modifier.height(2.dp))
            Label(sourceNote, MiuixTheme.colorScheme.onSurfaceSecondary, 12.sp)
            val lat = location.latitude
            val lon = location.longitude
            if (lat != null && lon != null) {
                Spacer(Modifier.height(6.dp))
                Label("%.4f°N  %.4f°E".format(lat, lon), MiuixTheme.colorScheme.onSurfaceSecondary, 12.sp)
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRequestLocation, colors = ButtonDefaults.buttonColorsPrimary()) {
                LucideIcon(AppIcon.Locate, MiuixTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(8.dp))
                Text("重新定位", style = MiuixTheme.textStyles.button)
            }
            Spacer(Modifier.height(6.dp))
            Label(
                "优先使用系统 GPS 定位；无权限或超时后才回退到 IP 定位。",
                MiuixTheme.colorScheme.onSurfaceSecondary, 11.sp,
            )
        }

        Spacer(Modifier.height(14.dp))
        SmallTitle("手动坐标")
        Card(insideMargin = PaddingValues(16.dp)) {
            var expanded by rememberSaveable { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Label(
                    "手动输入经纬度",
                    MiuixTheme.colorScheme.onSurface, 14.sp,
                    modifier = Modifier.weight(1f),
                )
                LucideIcon(
                    if (expanded) AppIcon.ChevronDown else AppIcon.ChevronRight,
                    MiuixTheme.colorScheme.onSurfaceSecondary,
                )
            }
            AnimatedVisibility(expanded) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    var latText by remember(location.latitude) {
                        mutableStateOf(location.latitude?.toString() ?: "")
                    }
                    var lonText by remember(location.longitude) {
                        mutableStateOf(location.longitude?.toString() ?: "")
                    }
                    TextField(
                        value = latText,
                        onValueChange = { latText = it },
                        label = "纬度 (°N)",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = lonText,
                        onValueChange = { lonText = it },
                        label = "经度 (°E)",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val latitude = latText.toDoubleOrNull()
                    val longitude = lonText.toDoubleOrNull()
                    val valid = latitude != null && longitude != null &&
                        latitude in -90.0..90.0 && longitude in -180.0..180.0
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { onSetManualLocation(latitude!!, longitude!!) },
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        enabled = valid,
                    ) {
                        LucideIcon(AppIcon.Check, MiuixTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                        Text("应用精确经纬度", style = MiuixTheme.textStyles.button)
                    }
                    Spacer(Modifier.height(6.dp))
                    Label(
                        "WGS84 · 纬度 −90～90，经度 −180～180",
                        MiuixTheme.colorScheme.onSurfaceSecondary, 11.sp,
                    )
                }
            }
        }
    }
}
