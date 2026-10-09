package com.aloys23.komiraquake.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aloys23.komiraquake.data.prefs.Settings
import com.aloys23.komiraquake.model.WarningLevel
import com.aloys23.komiraquake.service.RootHardening
import com.aloys23.komiraquake.service.SystemPermissions
import com.aloys23.komiraquake.service.WarningNotifier
import com.aloys23.komiraquake.ui.components.AppIcon
import com.aloys23.komiraquake.ui.components.Label
import com.aloys23.komiraquake.ui.components.LucideIcon
import com.aloys23.komiraquake.ui.theme.LocalAppDark
import com.aloys23.komiraquake.ui.theme.warningColor
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
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
        SmallTitle("权限与后台运行")
        Card {
            PermissionRow(
                title = "通知权限",
                summary = "预警通知与全屏弹出的前提",
                granted = notifications,
                onOpen = { context.startActivitySafe(SystemPermissions.appNotificationSettings(context)) },
            )
            PermissionRow(
                title = "全屏通知直弹",
                summary = "Android 14+ 需单独授权；锁屏亮屏直弹依赖它",
                granted = fullScreen,
                onOpen = SystemPermissions.fullScreenIntentSettings(context)?.let { intent ->
                    { context.startActivitySafe(intent) }
                },
            )
            PermissionRow(
                title = "通知策略访问（勿扰绕过）",
                summary = "授权后预警可临时越过勿扰",
                granted = policy,
                onOpen = { context.startActivitySafe(SystemPermissions.policyAccessSettings()) },
            )
            PermissionRow(
                title = "「地震预警」渠道允许打扰",
                summary = "渠道级兜底：允许该渠道在勿扰下出声",
                onOpen = {
                    context.startActivitySafe(
                        SystemPermissions.warningChannelSettings(context, WarningNotifier.CHANNEL_ID),
                    )
                },
            )
            PermissionRow(
                title = "忽略电池优化",
                summary = "避免 Doze 冻结后台网络",
                granted = battery,
                onOpen = {
                    context.startActivitySafe(SystemPermissions.requestIgnoreBatteryOptimizations(context))
                },
            )
            PermissionRow(
                title = "厂商自启动 / 后台弹出",
                summary = "MIUI / HyperOS 等需手动允许自启与后台弹出界面",
                onOpen = { context.startActivitySafe(SystemPermissions.autoStartSettings(context)) },
            )
            if (root) {
                PermissionRow(
                    title = "Root 一键加固",
                    summary = "su 加入电池白名单 / 逃逸 App Standby / 预授权限",
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
            Label(rootLog.orEmpty(), MiuixTheme.colorScheme.onSurfaceSecondary, size = 11.sp)
        }

        Spacer(Modifier.height(14.dp))
        SmallTitle("后台运行")
        Card {
            SwitchPreference(
                title = "后台保活服务",
                checked = settings.enableBackgroundGuard,
                onCheckedChange = { checked -> onUpdate { it.copy(enableBackgroundGuard = checked) } },
            )
            Label(
                "常驻前台服务以维持 WS 实时预警链路；可随时关闭",
                MiuixTheme.colorScheme.onSurfaceSecondary,
                size = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(6.dp))
            SwitchPreference(
                title = "预警时绕过勿扰",
                checked = settings.enableDndBypass,
                onCheckedChange = { checked -> onUpdate { it.copy(enableDndBypass = checked) } },
            )
            Label(
                "需先授予「通知策略访问」；预警结束自动恢复原勿扰状态",
                MiuixTheme.colorScheme.onSurfaceSecondary,
                size = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    summary: String,
    granted: Boolean? = null,
    enabled: Boolean = true,
    onOpen: (() -> Unit)? = null,
) {
    val clickable = enabled && onOpen != null
    val statusColor = warningColor(
        if (granted == false) WarningLevel.WARNING else WarningLevel.NORMAL,
        LocalAppDark.current,
    )
    BasicComponent(
        title = title,
        summary = summary,
        onClickLabel = "打开$title 设置",
        role = Role.Button,
        enabled = enabled,
        onClick = if (clickable) onOpen else null,
        endActions = {
            val tint = if (enabled) statusColor else MiuixTheme.colorScheme.disabledOnSurface
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LucideIcon(when (granted) {
                    true -> AppIcon.CircleCheck
                    false -> AppIcon.CircleAlert
                    null -> AppIcon.ExternalLink
                }, tint)
                Label(when (granted) { true -> "已开启"; false -> "未开启"; null -> "去设置" }, tint, size = 12.sp)
            }
        },
    )
}

private fun Context.startActivitySafe(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
