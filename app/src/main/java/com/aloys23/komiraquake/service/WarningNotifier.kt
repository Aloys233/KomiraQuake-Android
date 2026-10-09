package com.aloys23.komiraquake.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationManagerCompat
import com.aloys23.komiraquake.model.EarthquakeEvent
import com.aloys23.komiraquake.ui.warning.WarningActivity

/**
 * 视觉预警通知：全屏意图（锁屏强弹）由应用进程直接发布，不再占用第二个前台服务。
 * 进程存活由 [GuardService] 锚定，通知发布无需 FGS；声音、震动与勿扰全部由告警策略负责。
 *
 * 与旧实现（独立前台服务）的差异：不再有异步 service 启动，发布即同步生效，故不需要事件身份
 * 防竞态（每条事件仍带唯一 data URI，避免迟到的 A 通知复用成 B 的 PendingIntent）。
 */
object WarningNotifier {

    const val CHANNEL_ID = "komira_warning_visual_v2"
    const val NOTIFICATION_ID = 1001

    fun show(context: Context, event: EarthquakeEvent?) {
        if (event == null || event.isCanceled) {
            dismiss(context)
            return
        }
        if (!SystemPermissions.notificationsEnabled(context)) return
        ensureChannel(context)
        val fullScreenIntent = Intent(context, WarningActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // Unique data prevents a late A notification from turning into B's PendingIntent.
            data = Uri.parse("komiraquake://warning/${Uri.encode(event.identity)}")
            putExtra(WarningActivity.EXTRA_FROM_NOTIFICATION, true)
            putExtra(WarningActivity.EXTRA_EVENT_ID, event.identity)
        }
        val pending = PendingIntent.getActivity(
            context, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val location = event.location
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(if (location.isNotEmpty()) "$location 地震预警" else "地震预警")
            .setContentText("M ${"%.1f".format(event.magnitude)} · 预估烈度 ${event.estimatedIntensity}")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
        if (SystemPermissions.canUseFullScreenIntent(context)) builder.setFullScreenIntent(pending, true)
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        }
    }

    fun dismiss(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // Versioned channel: legacy sound/vibration cannot be changed programmatically.
        // Do not mutate/delete the legacy channel or override user settings on either channel.
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(com.aloys23.komiraquake.R.string.warning_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(com.aloys23.komiraquake.R.string.warning_channel_desc)
            setShowBadge(true)
            setSound(null, null)
            enableVibration(false)
            setBypassDnd(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }
}
