package com.aloys23.komiraquake.ui.settings.sections

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aloys23.komiraquake.data.prefs.Settings
import com.aloys23.komiraquake.model.ConnectionStatus
import com.aloys23.komiraquake.model.DataSourceInfo
import com.aloys23.komiraquake.model.SourceIds
import com.aloys23.komiraquake.model.WarningLevel
import com.aloys23.komiraquake.ui.components.AppButton
import com.aloys23.komiraquake.ui.components.AppCard
import com.aloys23.komiraquake.ui.components.AppIcon
import com.aloys23.komiraquake.ui.components.Label
import com.aloys23.komiraquake.ui.components.LucideIcon
import com.aloys23.komiraquake.ui.components.SectionHeader
import com.aloys23.komiraquake.ui.theme.AppSurfaces
import com.aloys23.komiraquake.ui.theme.SeismicColors
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TextField

/**
 * 「数据源」分区：每个数据源一张独立卡片。
 *
 * 卡片化而非单张大卡内的列表行，是因为各源的可用性差异很大——有的常年在线、有的默认关闭
 * 且需要凭据，用户需要一眼看出「哪个源能用、哪个源缺配置」。一源一卡后，卡片本身就是分组边界：
 * 开关、连接状态、目录刷新、需要凭据时的密钥管理都收在同一张卡里。
 */
@Composable
internal fun SourceSection(
    settings: Settings,
    sourceInfos: List<DataSourceInfo>,
    dark: Boolean,
    onUpdate: ((Settings) -> Settings) -> Unit,
    onLoginJian: (String) -> Unit,
    onSaveWhewsToken: (String) -> Unit,
    onSaveSimulatedUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SectionHeader("数据源", dark)
        Label(
            "各数据源平级、互为备份；关闭某个源后应用会改用其余源。",
            AppSurfaces.outline(dark), 12.sp,
        )
        Spacer(Modifier.height(12.dp))
        sourceInfos.forEach { info ->
            // 模拟源仅开发自测：开发者模式关闭时整张卡片不出现。
            if (info.id == SourceIds.SIMULATED && !settings.developerMode) return@forEach
            SourceCard(
                info = info,
                enabled = settings.enabled(info.id),
                settings = settings,
                dark = dark,
                onToggle = { checked ->
                    onUpdate {
                        it.copy(
                            disabledSources = if (checked) it.disabledSources - info.id
                            else it.disabledSources + info.id,
                        )
                    }
                },
                onLoginJian = onLoginJian,
                onSaveWhewsToken = onSaveWhewsToken,
                onSaveSimulatedUrl = onSaveSimulatedUrl,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * 单个数据源卡片：标题行（源名 + 开关）→ 连接状态 → 目录刷新 → 可展开说明。
 * Jian / Whews 需要凭据，密钥管理区块直接长在自己那张卡里，不再单列在分区底部。
 */
@Composable
private fun SourceCard(
    info: DataSourceInfo,
    enabled: Boolean,
    settings: Settings,
    dark: Boolean,
    onToggle: (Boolean) -> Unit,
    onLoginJian: (String) -> Unit,
    onSaveWhewsToken: (String) -> Unit,
    onSaveSimulatedUrl: (String) -> Unit,
) {
    var expanded by rememberSaveable(info.id) { mutableStateOf(false) }
    AppCard(dark) {
        // 标题行：源名 + 开关。开关自己占 48dp 命中区，文字不抢点击。
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label(
                info.name,
                if (enabled) AppSurfaces.onSurface(dark) else AppSurfaces.disabled(dark),
                15.sp, bold = true,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = enabled, onCheckedChange = onToggle,
                modifier = Modifier.semantics { contentDescription = info.name + " 数据源" },
            )
        }
        Spacer(Modifier.height(10.dp))
        // 第一行：连接状态点 + 状态 + 延迟
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(info.status, dark)
            Spacer(Modifier.size(6.dp))
            Label(
                info.statusLabel + (info.latencyMs?.let { " · $it ms" } ?: ""),
                if (enabled) AppSurfaces.onSurface(dark) else AppSurfaces.disabled(dark),
                12.sp, bold = true,
            )
        }
        Spacer(Modifier.height(4.dp))
        // 第二行：目录刷新，点按展开完整说明
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(info.directoryStatus, dark)
            Spacer(Modifier.size(6.dp))
            Label(
                directoryStatusText(info),
                if (enabled) AppSurfaces.outline(dark) else AppSurfaces.disabled(dark),
                11.sp,
                modifier = Modifier.weight(1f),
            )
            LucideIcon(
                if (expanded) AppIcon.ChevronDown else AppIcon.ChevronRight,
                AppSurfaces.outline(dark),
            )
        }
        AnimatedVisibility(expanded) {
            Label(
                info.description,
                AppSurfaces.outline(dark), 11.sp,
                modifier = Modifier.padding(top = 6.dp, start = 14.dp),
            )
        }

        // Jian 需登录：填登录密钥（邮件获取）换刷新令牌。未配置时始终显示，
        // 配置后仅在启用 Jian 时显示。该源默认关闭，未填凭据不会建立任何连接。
        if (info.id == SourceIds.JIAN &&
            (settings.jianRefreshToken.isBlank() || settings.enabled(SourceIds.JIAN))
        ) {
            CardDivider(dark)
            Label("密钥管理", AppSurfaces.onSurface(dark), 13.sp, bold = true)
            Spacer(Modifier.height(4.dp))
            var jianKey by remember { mutableStateOf("") }
            TextField(
                value = jianKey,
                onValueChange = { jianKey = it },
                label = "粘贴登录密钥 lk_…",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            AppButton(
                "登录", dark,
                onClick = { onLoginJian(jianKey.trim()); jianKey = "" },
                icon = AppIcon.Check, enabled = jianKey.isNotBlank(),
            )
            Spacer(Modifier.height(6.dp))
            Label(
                "登录成功后刷新令牌保存在本机，访问令牌由应用自动续取。设备有连接数上限，请避免频繁重连。",
                AppSurfaces.outline(dark), 11.sp,
            )
        }

        // Whews 需令牌：直接在 auth.beecld.com 申请 wat_…，粘贴即生效（无需换票）。
        // 未配置时始终显示，配置后仅在启用 Whews 时显示。该源默认关闭。
        if (info.id == SourceIds.WHEWS &&
            (settings.whewsToken.isBlank() || settings.enabled(SourceIds.WHEWS))
        ) {
            CardDivider(dark)
            Label("密钥管理", AppSurfaces.onSurface(dark), 13.sp, bold = true)
            Spacer(Modifier.height(4.dp))
            var whewsToken by remember { mutableStateOf("") }
            TextField(
                value = whewsToken,
                onValueChange = { whewsToken = it },
                label = "粘贴令牌 wat_…",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            AppButton(
                "保存", dark,
                onClick = { onSaveWhewsToken(whewsToken.trim()); whewsToken = "" },
                icon = AppIcon.Check, enabled = whewsToken.isNotBlank(),
            )
            Spacer(Modifier.height(6.dp))
            Label(
                "令牌在 auth.beecld.com 个人中心申请，保存后本机直连，不经第三方。" +
                    "单令牌最多 20 条并发连接，本应用只用 1 条聚合连接。",
                AppSurfaces.outline(dark), 11.sp,
            )
        }

        // 模拟源（仅开发自测）：填自建服务端的地址。留空即视为未配置，不会连接。
        if (info.id == SourceIds.SIMULATED) {
            CardDivider(dark)
            Label("模拟源地址", AppSurfaces.onSurface(dark), 13.sp, bold = true)
            Spacer(Modifier.height(4.dp))
            // 以已持久化的值为种子：外部改动（如恢复默认）后重新同步。
            var simUrl by remember(settings.simulatedUrl) { mutableStateOf(settings.simulatedUrl) }
            TextField(
                value = simUrl,
                onValueChange = { simUrl = it },
                label = "如 ws://10.0.2.2:8080/ws",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            AppButton(
                "保存", dark,
                onClick = { onSaveSimulatedUrl(simUrl.trim()) },
                icon = AppIcon.Check, enabled = simUrl.trim() != settings.simulatedUrl,
            )
            Spacer(Modifier.height(6.dp))
            Label(
                "报文机构固定为 SIM，不会与真实地震合并。同一地址重放会被终态墓碑压制，" +
                    "请在服务端控制台用「新一轮」换 id。",
                AppSurfaces.outline(dark), 11.sp,
            )
        }
    }
}

/** 卡片内的分区细线：把「状态」与「密钥管理」在同一张卡里分开。 */
@Composable
private fun CardDivider(dark: Boolean) {
    Spacer(Modifier.height(12.dp))
    Box(
        Modifier.fillMaxWidth().height(1.dp)
            .background(AppSurfaces.outlineVariant(dark).copy(alpha = 0.6f)),
    )
    Spacer(Modifier.height(12.dp))
}

/** 连接状态点：用 severity 配色，一眼区分在线/连接中/断开/异常。 */
@Composable
private fun StatusDot(status: ConnectionStatus, dark: Boolean) {
    val level = when (status) {
        ConnectionStatus.CONNECTED -> WarningLevel.NORMAL
        ConnectionStatus.CONNECTING -> WarningLevel.WATCH
        ConnectionStatus.DISCONNECTED -> WarningLevel.WARNING
        ConnectionStatus.ERROR -> WarningLevel.CRITICAL
    }
    Box(
        Modifier.size(8.dp).clip(CircleShape).background(SeismicColors.severity(level, dark)),
    )
}

private fun directoryStatusText(info: DataSourceInfo): String {
    val base = when (info.directoryStatus) {
        ConnectionStatus.CONNECTED -> "目录：刷新正常"
        ConnectionStatus.CONNECTING -> "目录：刷新中"
        ConnectionStatus.ERROR -> "目录：刷新失败"
        ConnectionStatus.DISCONNECTED -> "目录：未刷新"
    }
    val latency = info.directoryLatencyMs?.let { " · $it ms" } ?: ""
    val error = info.directoryError?.let { " · $it" } ?: ""
    return base + latency + error
}
