package com.aloys23.komiraquake.ui.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aloys23.komiraquake.core.ClockInfo
import com.aloys23.komiraquake.core.ClockState
import com.aloys23.komiraquake.data.prefs.Settings
import com.aloys23.komiraquake.ui.components.AppButton
import com.aloys23.komiraquake.ui.components.AppCard
import com.aloys23.komiraquake.ui.components.AppIcon
import com.aloys23.komiraquake.ui.components.AppSwitchRow
import com.aloys23.komiraquake.ui.components.Label
import com.aloys23.komiraquake.ui.components.SectionHeader
import com.aloys23.komiraquake.ui.settings.PermissionSection
import com.aloys23.komiraquake.ui.theme.AppSurfaces
import top.yukonga.miuix.kmp.basic.TextField

/** 「系统」分区：权限与后台运行（安卓专属）、网络校时。 */
@Composable
internal fun SystemSection(
    settings: Settings,
    clockInfo: ClockInfo,
    dark: Boolean,
    onUpdate: ((Settings) -> Settings) -> Unit,
    onRefreshClock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        PermissionSection(settings = settings, dark = dark, onUpdate = onUpdate)

        Spacer(Modifier.height(14.dp))
        SectionHeader("时间校准", dark)
        AppCard(dark) {
            AppSwitchRow(
                "网络校时 (SNTP)", settings.enableNtpSync, dark,
                onChange = { checked -> onUpdate { it.copy(enableNtpSync = checked) } },
                summary = "以网络时间为倒计时与走时反解的基准；SNTP 失败时回退 HTTP 授时。",
            )
            Spacer(Modifier.height(8.dp))
            Label(clockStatusText(clockInfo), AppSurfaces.outline(dark), 12.sp)
            Label(clockDetailText(clockInfo), AppSurfaces.outline(dark), 11.sp)
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var ntpText by remember(settings.customNtpServer) {
                    mutableStateOf(settings.customNtpServer)
                }
                TextField(
                    value = ntpText,
                    onValueChange = { ntpText = it },
                    label = "自定义 NTP 服务器（留空用默认）",
                    modifier = Modifier.fillMaxWidth(),
                )
                AppButton(
                    "应用并重新校时", dark,
                    onClick = {
                        onUpdate { it.copy(customNtpServer = ntpText.trim()) }
                        onRefreshClock()
                    },
                    icon = AppIcon.Check,
                    enabled = ntpText.trim() != settings.customNtpServer,
                )
            }
            Spacer(Modifier.height(6.dp))
            Label(
                "默认顺序：ntp.aliyun.com / ntp1.aliyun.com / ntp.tencent.com / pool.ntp.org / time.apple.com。自定义主机将优先尝试。",
                AppSurfaces.outline(dark), 11.sp,
            )
        }

        Spacer(Modifier.height(14.dp))
        SectionHeader("开发者", dark)
        AppCard(dark) {
            AppSwitchRow(
                "开发者模式", settings.developerMode, dark,
                onChange = { checked -> onUpdate { it.copy(developerMode = checked) } },
                summary = "显出「数据源」页的模拟数据源卡片，用于在没有真实地震时演练告警链路。",
            )
            Spacer(Modifier.height(6.dp))
            Label(
                "模拟源需另填一个 WebSocket 地址（模拟器用 ws://10.0.2.2:8080/ws，真机用电脑局域网 IP），" +
                    "并从「数据源」页启用它。关闭开发者模式即停止连接、不再建立任何连接。",
                AppSurfaces.outline(dark), 11.sp,
            )
            Spacer(Modifier.height(4.dp))
            Label(
                "注意：地震预警总开关默认关闭，要测试声音、震动与全屏预警需单独打开。" +
                    "明文 ws:// 仅 debug 包可用，release 包需 wss://。",
                AppSurfaces.outline(dark), 11.sp,
            )
        }
    }
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
