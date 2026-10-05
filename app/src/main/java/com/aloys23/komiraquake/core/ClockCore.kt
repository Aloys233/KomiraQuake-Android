package com.aloys23.komiraquake.core

import kotlin.math.abs

/** 校时状态。《NATIVE_PORT_SPEC》 §13.1 / §13.4。 */
enum class ClockState { LOCAL, SYNCED, STALE }

/**
 * 校时核心（纯逻辑，无 Android 依赖，可 JVM 单测）。
 *
 * 时间以**单调时钟锚定**：校准成功时记下 `(monoAtSync, wallAtSync, offsetMs)`，之后
 * `now() = wallAtSync + offsetMs + (monoNow − monoAtSync)`。
 * 因此系统时间在两次校准之间被改动不会影响 `now()`。《NATIVE_PORT_SPEC》 §13.4。
 */
class ClockCore(
    private val mono: () -> Long = { System.nanoTime() / 1_000_000L },
    private val wall: () -> Long = { System.currentTimeMillis() },
    private val staleAfterMs: Long = STALE_AFTER_MS,
) {
    private var synced = false
    private var monoAtSync = 0L
    private var wallAtSync = 0L
    private var offsetMs = 0L

    /** 最近一次成功校准的墙钟时刻（仅展示用）。 */
    var lastSyncAtMs: Long = 0L
        private set

    /** 最近样本的往返时延（ms）；未校准时 -1。 */
    var lastDelayMs: Long = -1L
        private set

    var sourceLabel: String = ""
        private set

    /** 单调耗时（毫秒）。用于量测往返/冷却，不用于绝对时刻。 */
    fun elapsedMs(): Long = mono()

    /** 校正后的当前时刻；从未成功同步时回退本地墙钟。 */
    fun now(): Long {
        if (!synced) return wall()
        return wallAtSync + offsetMs + (mono() - monoAtSync)
    }

    /** 当前生效的 offset；未校准为 0。 */
    fun currentOffsetMs(): Long = if (synced) offsetMs else 0L

    fun state(): ClockState = when {
        !synced -> ClockState.LOCAL
        // 用单调时钟判过期：系统墙钟被改动不会影响 stale 判定。
        mono() - monoAtSync > staleAfterMs -> ClockState.STALE
        else -> ClockState.SYNCED
    }

    /**
     * 应用一次采样。[offsetMs] = serverTime − localWall。
     *
     * 与当前 offset 相差 < [NOISE_MS] 视为噪声、忽略（避免无谓跳变）。
     * @return 是否实际改动了 offset
     */
    fun applyOffset(offsetMs: Long, delayMs: Long, sourceLabel: String): Boolean {
        val changed = !synced || abs(offsetMs - this.offsetMs) >= NOISE_MS
        // 噪声样本不改变 offset（避免无谓跳变），但仍重新锚定：漂移被吸收、stale 计时重置。
        if (changed) this.offsetMs = offsetMs
        this.lastDelayMs = delayMs
        this.sourceLabel = sourceLabel
        this.synced = true
        this.monoAtSync = mono()
        this.wallAtSync = wall()
        this.lastSyncAtMs = wallAtSync
        return changed
    }

    fun reset() {
        synced = false
        monoAtSync = 0L
        wallAtSync = 0L
        offsetMs = 0L
        lastSyncAtMs = 0L
        lastDelayMs = -1L
        sourceLabel = ""
    }

    companion object {
        /** 小于此差值的新 offset 视为噪声。 */
        const val NOISE_MS = 50L

        /** 距上次成功校准超过此值即视为 `stale`。 */
        const val STALE_AFTER_MS = 30L * 60L * 1000L
    }
}
