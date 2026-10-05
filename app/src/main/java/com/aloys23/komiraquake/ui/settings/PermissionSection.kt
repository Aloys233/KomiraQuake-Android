package com.aloys23.komiraquake.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aloys23.komiraquake.data.prefs.Settings
import com.aloys23.komiraquake.model.WarningLevel
import com.aloys23.komiraquake.service.RootHardening
import com.aloys23.komiraquake.service.SystemPermissions
import com.aloys23.komiraquake.service.WarningService
import com.aloys23.komiraquake.ui.components.Label
import com.aloys23.komiraquake.ui.components.AppIcon
import com.aloys23.komiraquake.ui.components.LucideIcon
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import com.aloys23.komiraquake.ui.theme.AppSurfaces
import com.aloys23.komiraquake.ui.theme.SeismicColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「权限与后台运行」指引区块：逐项展示系统特殊权限状态并可跳转对应设置页，
 * 另含保活 / 勿扰绕过开关与可选的 Root 一键加固。《NATIVE_PORT_SPEC》 §15。
 */
@Composable
internal fun PermissionSection(
    settings: Settings,
    dark: Boolean,
    onUpdate: ((Settings) -> Settings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // 从系统设置页返回（ON_RESUME）时重新探测权限状态。
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifications = remember(refresh) { SystemPermissions.notificationsEnabled(context) }
    val fullScreen = remember(refresh) { SystemPermissions.canUseFullScreenIntent(context) }
    val policy = remember(refresh) { SystemPermissions.hasPolicyAccess(context) }
    val battery = remember(refresh) { SystemPermissions.ignoresBatteryOptimizations(context) }
    val root = remember(refresh) { RootHardening.isRootAvailable() }

    var rootLog by remember { mutableStateOf<String?>(null) }
    var rootBusy by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        SectionTitle("权限与后台运行", dark)
        Card(dark) {
            PermissionRow(
                title = "通知权限",
                summary = "预警通知与全屏弹出的前提",
                dark = dark,
                granted = notifications,
                onOpen = { context.startActivitySafe(SystemPermissions.appNotificationSettings(context)) },
            )
            PermissionRow(
                title = "全屏通知直弹",
                summary = "Android 14+ 需单独授权；锁屏亮屏直弹依赖它",
                dark = dark,
                granted = fullScreen,
                onOpen = SystemPermissions.fullScreenIntentSettings(context)?.let { intent ->
                    { context.startActivitySafe(intent) }
                },
            )
            PermissionRow(
                title = "通知策略访问（勿扰绕过）",
                summary = "授权后预警可临时越过勿扰",
                dark = dark,
                granted = policy,
                onOpen = { context.startActivitySafe(SystemPermissions.policyAccessSettings()) },
            )
            PermissionRow(
                title = "「地震预警」渠道允许打扰",
                summary = "渠道级兜底：允许该渠道在勿扰下出声",
                dark = dark,
                onOpen = {
                    context.startActivitySafe(
                        SystemPermissions.warningChannelSettings(context, WarningService.CHANNEL_ID),
                    )
                },
            )
            PermissionRow(
                title = "忽略电池优化",
                summary = "避免 Doze 冻结后台网络",
                dark = dark,
                granted = battery,
                onOpen = {
                    context.startActivitySafe(SystemPermissions.requestIgnoreBatteryOptimizations(context))
                },
            )
            PermissionRow(
                title = "厂商自启动 / 后台弹出",
                summary = "MIUI / HyperOS 等需手动允许自启与后台弹出界面",
                dark = dark,
                onOpen = { context.startActivitySafe(SystemPermissions.autoStartSettings(context)) },
            )
            if (root) {
                PermissionRow(
                    title = "Root 一键加固",
                    summary = "su 加入电池白名单 / 逃逸 App Standby / 预授权限",
                    dark = dark,
                    enabled = !rootBusy,
                    onOpen = {
                        scope.launch {
                            rootBusy = true
                            rootLog = "执行中…"
                            rootLog = withContext(Dispatchers.IO) { RootHardening.apply(context) }
                            rootBusy = false
                            refresh++
                        }
                    },
                )
            }
        }

        if (rootLog != null) {
            Spacer(Modifier.height(6.dp))
            Label(rootLog.orEmpty(), AppSurfaces.outline(dark), size = 11.sp)
        }

        Spacer(Modifier.height(14.dp))
        SectionTitle("后台运行", dark)
        Card(dark) {
            SwitchRow("后台保活服务", settings.enableBackgroundGuard, dark) { checked ->
                onUpdate { it.copy(enableBackgroundGuard = checked) }
            }
            Label(
                "常驻前台服务以维持 WS 实时预警链路；可随时关闭",
                AppSurfaces.outline(dark),
                size = 11.sp,
            )
            Spacer(Modifier.height(6.dp))
            SwitchRow("预警时绕过勿扰", settings.enableDndBypass, dark) { checked ->
                onUpdate { it.copy(enableDndBypass = checked) }
            }
            Label(
                "需先授予「通知策略访问」；预警结束自动恢复原勿扰状态",
                AppSurfaces.outline(dark),
                size = 11.sp,
            )
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    summary: String,
    dark: Boolean,
    granted: Boolean? = null,
    enabled: Boolean = true,
    onOpen: (() -> Unit)? = null,
) {
    val clickable = enabled && onOpen != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = clickable, role = Role.Button, onClickLabel = "打开$title 设置") { onOpen?.invoke() }
            .heightIn(min = 64.dp).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Label(title, AppSurfaces.onSurface(dark), size = 13.sp)
            Spacer(Modifier.height(2.dp))
            Label(summary, AppSurfaces.outline(dark), size = 11.sp)
        }
        Spacer(Modifier.width(8.dp))
        val statusColor = SeismicColors.severity(
            if (granted == false) WarningLevel.WARNING else WarningLevel.NORMAL,
            dark,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LucideIcon(when (granted) {
                true -> AppIcon.CircleCheck
                false -> AppIcon.CircleAlert
                null -> AppIcon.ExternalLink
            }, if (enabled) statusColor else AppSurfaces.disabled(dark))
            Label(when (granted) { true -> "已开启"; false -> "未开启"; null -> "去设置" },
                if (enabled) statusColor else AppSurfaces.disabled(dark), size = 12.sp)
        }
    }
}

private fun Context.startActivitySafe(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
