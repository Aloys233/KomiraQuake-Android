package com.aloys23.komiraquake.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.aloys23.komiraquake.model.EarthquakeEvent
import com.aloys23.komiraquake.ui.warning.WarningActivity

/** Visual-only notification: all sound, vibration and DND are owned by alert policy. */
class WarningService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val eventId = intent?.getStringExtra(EXTRA_EVENT_ID)
        if (eventId == null || eventId != requestedEventId) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val location = intent.getStringExtra(EXTRA_LOCATION).orEmpty()
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        ensureChannel()
        val fullScreenIntent = Intent(this, WarningActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // Unique data prevents a late A notification from turning into B's PendingIntent.
            data = android.net.Uri.parse("komiraquake://warning/${android.net.Uri.encode(eventId)}")
            putExtra(WarningActivity.EXTRA_FROM_NOTIFICATION, true)
            putExtra(WarningActivity.EXTRA_EVENT_ID, eventId)
        }
        val pending = PendingIntent.getActivity(
            this, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(if (location.isNotEmpty()) "$location 地震预警" else "地震预警")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
        if (SystemPermissions.canUseFullScreenIntent(this)) builder.setFullScreenIntent(pending, true)
        val notification = builder.build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, FOREGROUND_TYPE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { stopSelfResult(startId) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // Versioned channel: legacy sound/vibration cannot be changed programmatically.
        // Do not mutate/delete the legacy channel or override user settings on either channel.
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(com.aloys23.komiraquake.R.string.warning_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(com.aloys23.komiraquake.R.string.warning_channel_desc)
            setShowBadge(true)
            setSound(null, null)
            enableVibration(false)
            setBypassDnd(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "komira_warning_visual_v2"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_LOCATION = "location"
        const val EXTRA_TEXT = "text"
        const val EXTRA_EVENT_ID = "event_identity"
        @Volatile private var requestedEventId: String? = null
        private const val FOREGROUND_TYPE = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

        fun start(context: Context, event: EarthquakeEvent?) {
            if (event == null || event.isCanceled) { stop(context); return }
            if (!SystemPermissions.notificationsEnabled(context)) return
            requestedEventId = event.identity
            val intent = Intent(context, WarningService::class.java).apply {
                putExtra(EXTRA_EVENT_ID, event.identity)
                putExtra(EXTRA_LOCATION, event.location)
                putExtra(EXTRA_TEXT, "M ${"%.1f".format(event.magnitude)} · 预估烈度 ${event.estimatedIntensity}")
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            }
        }

        fun stop(context: Context) {
            requestedEventId = null
            context.stopService(Intent(context, WarningService::class.java))
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }
}
