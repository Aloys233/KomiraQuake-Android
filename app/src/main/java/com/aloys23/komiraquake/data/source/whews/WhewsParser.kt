package com.aloys23.komiraquake.data.source.whews

import com.aloys23.komiraquake.core.IntensityStandard
import com.aloys23.komiraquake.data.source.EewParser
import com.aloys23.komiraquake.data.source.SourceEventKind
import com.aloys23.komiraquake.model.EarthquakeEvent
import org.json.JSONObject

/** Whews 业务帧解析（`/ws/all` 的 `Data` 对象）。字段约定见 https://api.2v8.cn/docs。 */
object WhewsParser {

    private fun firstDouble(obj: JSONObject, vararg keys: String): Double? {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val v = obj.opt(key)
            when (v) {
                is Number -> if (v.toDouble().isFinite()) return v.toDouble()
                is String -> v.toDoubleOrNull()?.let { if (it.isFinite()) return it }
                else -> Unit
            }
        }
        return null
    }

    private fun firstString(obj: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val s = obj.optString(key, "")
            if (s.isNotEmpty() && s != "null") return s
        }
        return null
    }

    private fun firstInt(obj: JSONObject, vararg keys: String): Int? =
        firstDouble(obj, *keys)?.toInt()

    /** 按频道时区解释无时区墙钟：JMA 系为 UTC+9，其余为 UTC+8。 */
    private fun parseWallClock(raw: String, tz: WhewsTimeZone): Long? = when (tz) {
        WhewsTimeZone.JST -> EewParser.parseJstTime(raw)
        WhewsTimeZone.UTC8 -> EewParser.parseUtc8Time(raw)
    }

    /**
     * cenc 的 id 形如 `CD.20260819132221.000`；若上游带 `_M`（正式）/ `_A`（自动）后缀，
     * 去掉后缀即与 Wolfx cenc_eqlist 的 EventID 对齐。
     */
    private fun normalizeUpstreamId(id: String): String =
        if (id.endsWith("_M") || id.endsWith("_A")) id.dropLast(2) else id

    /** 单个业务记录（`Data` 对象）→ 事件。必填字段缺失返回 null。 */
    fun parseRecord(
        channel: WhewsChannel,
        data: JSONObject,
        user: Pair<Double, Double>?,
        standard: IntensityStandard,
    ): EarthquakeEvent? {
        val rawId = firstString(data, "id", "ID", "EventID") ?: return null
        val latitude = firstDouble(data, "latitude", "Latitude") ?: return null
        val longitude = firstDouble(data, "longitude", "Longitude") ?: return null
        val origin = firstString(data, "shockTime", "originTime")
            ?.let { parseWallClock(it, channel.tz) } ?: return null
        if (origin <= 0) return null

        val magnitude = firstDouble(data, "magnitude", "Magnitude") ?: 0.0
        val depth = firstDouble(data, "depth", "Depth") ?: EewParser.DEFAULT_DEPTH
        val location = firstString(data, "placeName", "place", "location")
            ?.takeIf { it.isNotBlank() } ?: EewParser.UNKNOWN_LOCATION
        // 报次：EEW 用 updates；情报多为 1。
        val reportNum = firstInt(data, "updates", "number", "serial") ?: 1

        // 烈度：EEW 用 epiIntensity（数值或 JMA 文本），情报用 maxIntensity / intensity。
        val (intensityText, intensityRaw) = EewParser.parseMaxIntensity(data, standard)

        // Whews 用 cancel / final（不带 is 前缀），与 Wolfx 的 isCancel / isFinal 不同。
        val canceled = data.optBoolean("cancel", false) || data.optBoolean("canceled", false)
        val isFinal = channel.kind == SourceEventKind.DIRECTORY ||
            data.optBoolean("final", false) || data.optBoolean("isFinal", false)

        val base = if (channel.eventNs.isEmpty()) normalizeUpstreamId(rawId) else rawId
        val eventId = if (channel.eventNs.isEmpty()) base else "${channel.eventNs}:$base"

        val event = EarthquakeEvent(
            id = "${channel.source}_$base",
            eventId = eventId,
            magnitude = magnitude,
            latitude = latitude,
            longitude = longitude,
            depth = depth,
            location = location,
            timestamp = origin,
            source = WhewsProtocol.titleFor(channel),
            sourceProvider = WhewsProtocol.PROVIDER,
            sourceAgency = channel.agency,
            maxIntensityText = intensityText,
            maxIntensityRaw = intensityRaw,
            reportNum = reportNum,
            isFinal = isFinal,
            isCanceled = canceled,
            distanceKm = EewParser.UNKNOWN_DISTANCE,
            estimatedIntensity = "--",
            sourceUpdatedAt = firstString(data, "createTime", "updateTime")
                ?.let { parseWallClock(it, channel.tz) },
        )
        return EewParser.recalculate(event, user, standard, channel.kind == SourceEventKind.DIRECTORY)
    }
}