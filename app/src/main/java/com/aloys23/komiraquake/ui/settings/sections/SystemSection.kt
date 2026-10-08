package com.aloys23.komiraquake.ui.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import com.aloys23.komiraquake.ui.components.AppIcon
import com.aloys23.komiraquake.ui.components.Label
import com.aloys23.komiraquake.ui.components.LucideIcon
import com.aloys23.komiraquake.ui.settings.PermissionSection
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
        SmallTitle("时间校准")
        Card {
            SwitchPreference(
                title = "网络校时 (SNTP)",
                summary = "以网络时间为倒计时与走时反解的基准；SNTP 失败时回退 HTTP 授时。",
                checked = settings.enableNtpSync,
                onCheckedChange = { checked -> onUpdate { it.copy(enableNtpSync = checked) } },
            )
            Spacer(Modifier.height(8.dp))
            Label(clockStatusText(clockInfo), MiuixTheme.colorScheme.onSurfaceSecondary, 12.sp)
            Label(clockDetailText(clockInfo), MiuixTheme.colorScheme.onSurfaceSecondary, 11.sp)
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
                Button(
                    onClick = {
                        onUpdate { it.copy(customNtpServer = ntpText.trim()) }
                        onRefreshClock()
                    },
                    enabled = ntpText.trim() != settings.customNtpServer,
                ) {
                    LucideIcon(AppIcon.Check, MiuixTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("应用并重新校时", style = MiuixTheme.textStyles.button)
                }
            }
            Spacer(Modifier.height(6.dp))
            Label(
                "默认顺序：ntp.aliyun.com / ntp1.aliyun.com / ntp.tencent.com / pool.ntp.org / time.apple.com。自定义主机将优先尝试。",
                MiuixTheme.colorScheme.onSurfaceSecondary, 11.sp,
            )
        }

        Spacer(Modifier.height(14.dp))
        SmallTitle("开发者")
        Card {
            SwitchPreference(
                title = "开发者模式",
                summary = "显出「数据源」页的模拟数据源卡片，用于在没有真实地震时演练告警链路。",
                checked = settings.developerMode,
                onCheckedChange = { checked -> onUpdate { it.copy(developerMode = checked) } },
            )
            Spacer(Modifier.height(6.dp))
            Label(
                "模拟源需另填一个 WebSocket 地址（模拟器用 ws://10.0.2.2:8080/ws，真机用电脑局域网 IP），" +
                    "并从「数据源」页启用它。关闭开发者模式即停止连接、不再建立任何连接。",
                MiuixTheme.colorScheme.onSurfaceSecondary, 11.sp,
            )
            Spacer(Modifier.height(4.dp))
            Label(
                "注意：地震预警总开关默认关闭，要测试声音、震动与全屏预警需单独打开。" +
                    "明文 ws:// 仅 debug 包可用，release 包需 wss://。",
                MiuixTheme.colorScheme.onSurfaceSecondary, 11.sp,
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
