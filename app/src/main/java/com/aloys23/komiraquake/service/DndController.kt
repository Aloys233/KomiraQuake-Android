package com.aloys23.komiraquake.service

import android.app.NotificationManager
import android.content.Context
import com.aloys23.komiraquake.data.prefs.SettingsStore
import com.aloys23.komiraquake.model.EarthquakeEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 勿扰（DND）绕过：持有通知策略访问权限时，预警期间把全局中断过滤器临时设为「全部」，
 * 结束后恢复原过滤器；另有超时兜底，避免进程异常导致勿扰长期失效。《NATIVE_PORT_SPEC》 §15。
 */
class DndController(
    private val context: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private var savedFilter: Int? = null
    private var timeoutJob: Job? = null
    private var timedOut = false

    private val manager: NotificationManager?
        get() = context.getSystemService(NotificationManager::class.java)

    fun hasAccess(): Boolean =
        runCatching { manager?.isNotificationPolicyAccessGranted == true }.getOrDefault(false)

    /** 仅对真实预警（非取消报、WARNING/CRITICAL）且设置开启、未静音时绕过。 */
    fun shouldEngage(event: EarthquakeEvent): Boolean =
        AlertPolicy.evaluate(event, settings.current).dnd

    @Synchronized
    fun engage() {
        if (timedOut || !hasAccess()) return
        val nm = manager ?: return
        if (savedFilter != null) return // Updates never extend the bounded ownership window.
        val previous = runCatching { nm.currentInterruptionFilter }.getOrNull() ?: return
        if (previous == NotificationManager.INTERRUPTION_FILTER_ALL ||
            previous == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) return
        val changed = runCatching {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
        }.getOrDefault(false)
        if (!changed) return
        savedFilter = previous
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(RELEASE_AFTER_MS)
            synchronized(this@DndController) {
                release()
                timedOut = true // Block ticker reacquisition until an explicit no-alert release.
            }
        }
    }

    @Synchronized
    fun release() {
        timedOut = false
        timeoutJob?.cancel()
        timeoutJob = null
        val previous = savedFilter ?: return
        savedFilter = null
        runCatching {
            val nm = manager ?: return@runCatching
            // Do not overwrite a filter the user changed while this alert was active.
            if (nm.isNotificationPolicyAccessGranted &&
                nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
                nm.setInterruptionFilter(previous)
            }
        }
    }

    companion object {
        /** 5 分钟兜底：预警链路异常时也不让全局勿扰长期失效。 */
        private const val RELEASE_AFTER_MS = 5L * 60L * 1000L
    }
}
