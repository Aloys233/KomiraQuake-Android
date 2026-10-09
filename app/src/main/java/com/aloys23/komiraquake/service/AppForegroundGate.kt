package com.aloys23.komiraquake.service

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.aloys23.komiraquake.core.ForegroundGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * 基于 [Application.ActivityLifecycleCallbacks] 的前后台状态。
 *
 * 用已启动（started）Activity 计数而非单个 Activity 的生命周期：预警期 `WarningActivity`
 * 可能在 `MainActivity` 之上，任一 Activity 可见即视为前台。计数归零才转后台。
 */
class AppForegroundGate : ForegroundGate, Application.ActivityLifecycleCallbacks {

    private var started = 0
    private val _foreground = MutableStateFlow(true)
    override val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    override fun onActivityStarted(activity: Activity) {
        started++
        _foreground.value = true
    }

    override fun onActivityStopped(activity: Activity) {
        if (started > 0) started--
        if (started == 0) _foreground.value = false
    }

    override suspend fun awaitForeground(): Boolean {
        if (_foreground.value) return false
        _foreground.first { it }
        return true
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
