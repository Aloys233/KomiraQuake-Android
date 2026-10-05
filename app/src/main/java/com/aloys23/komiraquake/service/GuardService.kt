package com.aloys23.komiraquake.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.aloys23.komiraquake.MainActivity
import com.aloys23.komiraquake.R

/**
 * 常驻前台服务：作为进程存活锚点，配合电池优化白名单维持 WS 实时预警链路。
 *
 * 类型取 `specialUse`——Android 15+ 禁止从 BOOT_COMPLETED 启动 dataSync 类型 FGS，
 * `specialUse` 属允许项。《NATIVE_PORT_SPEC》 §15。
 */
class GuardService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startForegroundCompat()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 被最近任务划掉后，用一次性 1s 闹钟把守护服务拉回；不申请精确闹钟权限，属尽力而为。
        val restart = PendingIntent.getService(
            this,
            RESTART_REQUEST_CODE,
            Intent(this, GuardService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        getSystemService(AlarmManager::class.java)
            ?.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + RESTART_DELAY_MS, restart)
        super.onTaskRemoved(rootIntent)
    }

    private fun startForegroundCompat() {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.guard_channel_name))
            .setContentText(getString(R.string.guard_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(contentIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.guard_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.guard_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "komira_guard"
        const val NOTIFICATION_ID = 1000
        private const val RESTART_REQUEST_CODE = 2
        private const val RESTART_DELAY_MS = 1000L

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, GuardService::class.java))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GuardService::class.java))
        }
    }
}
