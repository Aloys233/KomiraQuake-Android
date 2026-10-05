package com.aloys23.komiraquake.service

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/** 系统特殊权限的状态探测与跳转入口。《NATIVE_PORT_SPEC》 §15。 */
object SystemPermissions {

    fun hasLocationPermission(context: Context): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    fun notificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Android 14+ 的「全屏通知」授权；低版本恒为可用。 */
    fun canUseFullScreenIntent(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return runCatching {
            context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true
        }.getOrDefault(false)
    }

    fun hasPolicyAccess(context: Context): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true
    }.getOrDefault(false)

    fun ignoresBatteryOptimizations(context: Context): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)

    fun appNotificationSettings(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun warningChannelSettings(context: Context, channelId: String): Intent =
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)

    fun policyAccessSettings(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

    fun fullScreenIntentSettings(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
            .setData(Uri.parse("package:${context.packageName}"))
    }

    fun requestIgnoreBatteryOptimizations(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))

    fun appDetailsSettings(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))

    /**
     * 厂商自启动 / 后台弹出界面管理页：按已知组件名逐一尝试，均不存在则回退应用详情页。
     * 这些页面非公开 API，属尽力而为。
     */
    fun autoStartSettings(context: Context): Intent {
        for ((pkg, cls) in OEM_AUTOSTART) {
            val intent = Intent().setComponent(ComponentName(pkg, cls))
            if (context.packageManager.resolveActivity(intent, 0) != null) return intent
        }
        return appDetailsSettings(context)
    }

    private val OEM_AUTOSTART = listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.meizu.safe" to "com.meizu.safe.security.SHOW_APPSEC",
    )
}
