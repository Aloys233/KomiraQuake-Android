package com.aloys23.komiraquake.data.source.simulated

import com.aloys23.komiraquake.core.AppClock
import com.aloys23.komiraquake.core.IntensityStandard
import com.aloys23.komiraquake.data.source.EewParser
import com.aloys23.komiraquake.data.source.SourceEventKind
import com.aloys23.komiraquake.model.EarthquakeEvent
import org.json.JSONObject

/**
 * 模拟源报文解析（协议 `sim-eew/1`，见《NATIVE_PORT_SPEC》 §2.7）。
 *
 * 服务端只发场景可表达的字段；距离、烈度、到时、预警等级一律由
 * [EewParser.recalculate] 依用户定位与烈度制式本地派生。
 */
object SimulatedParser {

    private fun double(obj: JSONObject, key: String, fallback: Double): Double {
        if (!obj.has(key) || obj.isNull(key)) return fallback
        return when (val v = obj.opt(key)) {
            is Number -> if (v.toDouble().isFinite()) v.toDouble() else fallback
            is String -> v.toDoubleOrNull()?.takeIf { it.isFinite() } ?: fallback
            null -> fallback
            else -> fallback
        }
    }

    private fun epochMs(obj: JSONObject, key: String): Long {
        if (!obj.has(key) || obj.isNull(key)) return 0
        // 契约要求 originTime 是 epoch 毫秒数字。不用 parseTime：那支会按
        // <1e11 的阈值把秒当毫秒放大，秒与毫秒在此语义含糊、不应依赖。
        return when (val v = obj.opt(key)) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: 0L
            null -> 0L
            else -> 0L
        }
    }
    /**
     * 解析一帧 `report` / `directory`。必填项缺失返回 null。
     *
     * 必填：`eventId`、`originTime`、`latitude`、`longitude`。
     */
    fun parseReport(
        frame: JSONObject,
        kind: SourceEventKind,
        user: Pair<Double, Double>?,
        standard: IntensityStandard,
    ): EarthquakeEvent? {
        val eventId = frame.optString("eventId", "")
        if (eventId.isEmpty()) return null
        // 震中缺省为 0/0 会被当成几内亚湾的合法坐标，故用 has() 判定必填。
        if (!frame.has("latitude") || !frame.has("longitude")) return null

        val origin = epochMs(frame, "originTime")
        if (origin <= 0) return null

        val location = frame.optString("location", "")
            .ifBlank { SimulatedProtocol.UNKNOWN_LOCATION }

        val event = EarthquakeEvent(
            // id 带 sim_ 前缀，eventId 已在 sim- 命名空间内；两者都不模仿真实报文形状。
            id = "sim_$eventId",
            eventId = eventId,
            magnitude = double(frame, "magnitude", 0.0),
            latitude = double(frame, "latitude", 0.0),
            longitude = double(frame, "longitude", 0.0),
            depth = double(frame, "depth", SimulatedProtocol.DEFAULT_DEPTH),
            location = location,
            timestamp = origin,
            source = SimulatedProtocol.titleFor(kind),
            sourceProvider = SimulatedProtocol.PROVIDER,
            sourceAgency = SimulatedProtocol.AGENCY,
            // 烈度文本原文透传：JMA 式写法（如 5弱）经 parseMaxIntensity 会被重新格式化而破坏。
            maxIntensityText = frame.optString("maxIntensityText", ""),
            maxIntensityRaw = double(frame, "maxIntensity", 0.0),
            reportNum = frame.optInt("reportNum", 1),
            isFinal = frame.optBoolean("isFinal", false),
            isCanceled = frame.optBoolean("isCanceled", false),
            distanceKm = EewParser.UNKNOWN_DISTANCE,
            estimatedIntensity = "--",
            // 收帧时刻：目录去重以它决胜（见 QuakeRepository.refreshEventList），
            // 缺省会令目录条目任意胜出。服务端不发送该字段。
            sourceUpdatedAt = AppClock.now(),
        )
        return EewParser.recalculate(event, user, standard, kind == SourceEventKind.DIRECTORY)
    }
}
