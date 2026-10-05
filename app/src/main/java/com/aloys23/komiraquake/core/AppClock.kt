package com.aloys23.komiraquake.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 校时信息快照，供设置页展示。《NATIVE_PORT_SPEC》 §13.1。 */
data class ClockInfo(
    val state: ClockState = ClockState.LOCAL,
    val offsetMs: Long = 0L,
    val lastSyncAtMs: Long = 0L,
    val delayMs: Long = -1L,
    val sourceLabel: String = "",
    val enabled: Boolean = true,
)

/**
 * 应用级时钟单例：所有地震时间语义（倒计时、走时圆反解、去重窗口、新鲜度过滤）
 * 统一走 [now]。实现见 [ClockCore]，此处只做单例包装与状态广播。《NATIVE_PORT_SPEC》 §13。
 */
object AppClock {
    private val core = ClockCore()

    private val _info = MutableStateFlow(ClockInfo())
    val info: StateFlow<ClockInfo> = _info.asStateFlow()

    private var enabled = true

    /** 校正后的当前时刻；关闭校时或从未同步时为本地墙钟。 */
    fun now(): Long = if (enabled) core.now() else System.currentTimeMillis()

    /** 单调耗时（毫秒），不受系统时间影响。 */
    fun elapsedMs(): Long = core.elapsedMs()

    fun isEnabled(): Boolean = enabled

    /** 由设置驱动：关闭时不发起校时且立即回退本地墙钟。 */
    @Synchronized
    fun enable(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (!value) core.reset()
        publish()
    }

    /** 应用一次采样。[offsetMs] = serverTime − localWall。 */
    @Synchronized
    fun applyOffset(offsetMs: Long, delayMs: Long, sourceLabel: String) {
        if (!enabled) return
        core.applyOffset(offsetMs, delayMs, sourceLabel)
        publish()
    }

    /** 重新广播当前状态（用于 stale 判定随时间推移变化）。 */
    @Synchronized
    fun refresh() = publish()

    @Synchronized
    private fun publish() {
        _info.value = ClockInfo(
            state = if (enabled) core.state() else ClockState.LOCAL,
            offsetMs = core.currentOffsetMs(),
            lastSyncAtMs = core.lastSyncAtMs,
            delayMs = core.lastDelayMs,
            sourceLabel = core.sourceLabel,
            enabled = enabled,
        )
    }

    /** 仅供单测：重置为「未同步 + 启用」的初始状态。 */
    fun resetForTest() {
        core.reset()
        synchronized(this) {
            enabled = true
            publish()
        }
    }
}
