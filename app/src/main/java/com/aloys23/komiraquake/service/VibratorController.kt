package com.aloys23.komiraquake.service

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.aloys23.komiraquake.model.WarningLevel

/**
 * 地震预警震动控制器。
 * 在 WARNING / CRITICAL 预警触发时输出强烈的节奏蜂鸣震动，防止用户在静音或嘈杂环境中遗漏预警。
 */
class VibratorController(private val context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private var isVibrating = false
    private var activeLevel: WarningLevel? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { stop() }

    /**
     * 触发预警震动。
     * WARNING: 节奏双连震；CRITICAL: 强烈长震波。
     */
    @Synchronized
    fun startAlert(level: WarningLevel) {
        if (!level.isAlert) { stop(); return }
        if (isVibrating && activeLevel == level) return
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        stop()

        isVibrating = true
        activeLevel = level
        handler.postDelayed(timeout, 120_000L)
        val timings = if (level == WarningLevel.CRITICAL) {
            longArrayOf(0, 800, 200, 800, 200, 800, 400)
        } else {
            longArrayOf(0, 400, 200, 400, 600)
        }
        val amplitudes = if (level == WarningLevel.CRITICAL) {
            intArrayOf(0, 255, 0, 255, 0, 255, 0)
        } else {
            intArrayOf(0, 200, 0, 200, 0)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createWaveform(timings, amplitudes, 0) // 0 表示从索引 0 循环
            vib.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(timings, 0)
        }
    }

    /** 停止震动 */
    @Synchronized
    fun stop() {
        handler.removeCallbacks(timeout)
        activeLevel = null
        isVibrating = false
        runCatching { vibrator?.cancel() }
    }
}
