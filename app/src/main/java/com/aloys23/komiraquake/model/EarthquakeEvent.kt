package com.aloys23.komiraquake.model

import com.aloys23.komiraquake.core.AppClock

/**
 * 地震事件。字段与 《NATIVE_PORT_SPEC》 §1.1 一一对应。
 * 时间统一使用 epoch 毫秒（LocalDateTime 在跨端契约中不便于比较，故两端统一为 Instant）。
 */
data class EarthquakeEvent(
    val id: String,
    /** 数据源原始事件 ID（跨报次/跨链路稳定，用于同一地震的合并）。 */
    val eventId: String = "",
    val magnitude: Double,
    val latitude: Double,
    val longitude: Double,
    val depth: Double,
    val location: String,
    val timestamp: Long,
    val source: String,
    /** 数据源提供方，如 "Wolfx"。 */
    val sourceProvider: String = "",
    /** 报数机构缩写，如 "CENC"、"JMA"。UI 以 `<provider>·<agency>` 标明来源。 */
    val sourceAgency: String = "",
    val distanceKm: Double,
    val estimatedIntensity: String,
    val rawIntensity: Double = 0.0,
    val maxIntensityRaw: Double = 0.0,
    val maxIntensityText: String = "",
    val pWaveArrival: Long? = null,
    val sWaveArrival: Long? = null,
    val warningLevel: WarningLevel = WarningLevel.NORMAL,
    val reportNum: Int = 1,
    val isFinal: Boolean = false,
    val isCanceled: Boolean = false,
    val sourceUpdatedAt: Long? = null,
    /** 仅用于列表：与实时预警合并后标记该条仍在实时告警中。 */
    val isActive: Boolean = false,
) {
    /** Stable across reports, but never conflates agencies or providers. */
    val identity: String get() = "${sourceProvider.ifEmpty { source }}|${sourceAgency}|${eventId.ifEmpty { id }}"
    /** 来源标注 `<数据源提供方>·<报数机构>`（如 `Wolfx·CENC`）；无提供方时回退展示名。 */
    val sourceTag: String
        get() = when {
            sourceProvider.isEmpty() -> source
            sourceAgency.isEmpty() -> sourceProvider
            else -> "$sourceProvider·$sourceAgency"
        }

    fun remainingSeconds(now: Long = AppClock.now()): Int {
        val s = sWaveArrival ?: return -1
        val diff = ((s - now) / 1000.0).toInt()
        return if (diff > 0) diff else 0
    }

    fun remainingPSeconds(now: Long = AppClock.now()): Int {
        val p = pWaveArrival ?: return -1
        val diff = ((p - now) / 1000.0).toInt()
        return if (diff > 0) diff else 0
    }

    fun isPWaveArrived(now: Long = AppClock.now()): Boolean = pWaveArrival != null && remainingPSeconds(now) == 0

    fun isSWaveArrived(now: Long = AppClock.now()): Boolean = sWaveArrival != null && remainingSeconds(now) == 0
}
