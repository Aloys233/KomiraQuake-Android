package com.aloys23.komiraquake.data.source.pancakes

import com.aloys23.komiraquake.core.IntensityStandard
import com.aloys23.komiraquake.data.source.EewParser
import com.aloys23.komiraquake.data.source.SourceEventKind
import com.aloys23.komiraquake.model.EarthquakeEvent
import org.json.JSONObject

data class PancakesParsed(val event: EarthquakeEvent, val kind: SourceEventKind, val source: String)

/**
 * PancakesAPI 地震事件解析。
 *
 * 聚合通道外层统一为 `{source, type, action, timestampMs, payload}`；payload 结构与各子源
 * 的 HTTP 列表项字段一致（gq 为 GlobalQuake 报文、usgs 为 ComCat 归一化结果、
 * jma_eew 沿用 JMA 緊急地震速報字段、jma_eqlist 为 JMA 观测电文）。
 *
 * 名称空间：`eventId` 以 `<source>:` 前缀区分，保证 gq/usgs/jma_eew/jma_eqlist 之间
 * 即使上游 id 相同（如 JMA 的 EEW 与速报共用 EventID）也不会在合并/去重时互相污染。
 */
object PancakesParser {

    fun parseRealtime(
        envelope: JSONObject,
        user: Pair<Double, Double>?,
        standard: IntensityStandard,
        now: Long,
    ): PancakesParsed? {
        val source = envelope.optString("source", "")
        if (source !in PancakesProtocol.QUAKE_SOURCES) return null
        if (envelope.optString("type", "") != "earthquake") return null
        val action = envelope.optString("action", "")
        val payload = envelope.optJSONObject("payload") ?: return null
        val envelopeTime = envelope.optLong("timestampMs", now)
        return when (source) {
            PancakesProtocol.SOURCE_GQ -> parseGq(action, payload, envelopeTime, user, standard)
            PancakesProtocol.SOURCE_USGS -> parseUsgs(action, payload, envelopeTime, user, standard)
            PancakesProtocol.SOURCE_JMA_EEW -> parseJmaEew(payload, envelopeTime, user, standard)
            PancakesProtocol.SOURCE_JMA_EQLIST -> parseJmaEqlist(action, payload, envelopeTime, user, standard)
            else -> null
        }
    }

    /** HTTP 列表项（GET /api/v1/alert/quake/{source}）→ 目录条目。 */
    fun parseListItem(
        item: JSONObject,
        user: Pair<Double, Double>?,
        standard: IntensityStandard,
        now: Long,
    ): EarthquakeEvent? {
        val source = item.optString("source", "")
        if (source !in PancakesProtocol.QUAKE_SOURCES) return null
        val rawId = firstString(item, "eventId") ?: return null
        val lat = firstDouble(item, "latitude") ?: return null
        val lon = firstDouble(item, "longitude") ?: return null
        val magnitude = firstDouble(item, "magnitude") ?: 0.0
        val depth = firstDouble(item, "depthKm") ?: EewParser.DEFAULT_DEPTH
        val location = firstString(item, "place") ?: EewParser.UNKNOWN_LOCATION
        val origin = EewParser.parseTime(firstString(item, "originTime") ?: "") ?: return null
        val status = firstString(item, "status") ?: "active"
        val updated = firstLong(item, "revision")
            ?: EewParser.parseTime(firstString(item, "updatedAt") ?: "")
        val (maxText, maxRaw) = EewParser.parseMaxIntensity(item, standard)
        return build(
            source = source,
            rawEventId = rawId,
            prefix = prefixFor(source),
            magnitude = magnitude,
            latitude = lat,
            longitude = lon,
            depth = depth,
            location = location,
            originTime = origin,
            maxText = maxText,
            maxRaw = maxRaw,
            reportNum = 1,
            isFinal = status == "archived",
            isCanceled = status == "cancelled",
            sourceUpdatedAt = updated,
            user = user,
            standard = standard,
            kind = SourceEventKind.DIRECTORY,
        ).event
    }

    // ---------------------------------------------------------------- 子源

    private fun parseGq(
        action: String,
        payload: JSONObject,
        envelopeTime: Long,
        user: Pair<Double, Double>?,
        standard: IntensityStandard,
    ): PancakesParsed? {
        val rawId = firstString(payload, "id") ?: return null
        if (action == "cancelled") {
            // 取消报文只带 id：构造一个终止事件，靠 identity 命中并结束生命周期。
            return build(
                source = PancakesProtocol.SOURCE_GQ,
                rawEventId = rawId,
                prefix = PREFIX_GQ,
                magnitude = 0.0,
                latitude = 0.0,
                longitude = 0.0,
                depth = EewParser.DEFAULT_DEPTH,
                location = "已取消",
                originTime = envelopeTime,
                maxText = "",
                maxRaw = 0.0,
                reportNum = PancakesProtocol.CANCEL_REPORT_NUM,
                isFinal = false,
                isCanceled = true,
                sourceUpdatedAt = envelopeTime,
                user = user,
                standard = standard,
                kind = SourceEventKind.LIVE,
            )
        }
        val latitude = firstDouble(payload, "latitude") ?: return null
        val longitude = firstDouble(payload, "longitude") ?: return null
        val magnitude = firstDouble(payload, "magnitude") ?: 0.0
        val depth = firstDouble(payload, "depth") ?: EewParser.DEFAULT_DEPTH
        val location = firstString(payload, "region") ?: EewParser.UNKNOWN_LOCATION
        val origin = firstLong(payload, "originTimeMs") ?: envelopeTime
        val revision = firstInt(payload, "revisionId") ?: 1
        val (maxText, maxRaw) = EewParser.parseMaxIntensity(payload, standard)
        return build(
            source = PancakesProtocol.SOURCE_GQ,
            rawEventId = rawId,
            prefix = PREFIX_GQ,
            magnitude = magnitude,
            latitude = latitude,
            longitude = longitude,
            depth = depth,
            location = location,
            originTime = origin,
            maxText = maxText,
            maxRaw = maxRaw,
            reportNum = revision + 1,
            isFinal = action == "archived",
            isCanceled = false,
            sourceUpdatedAt = firstLong(payload, "lastUpdateMs"),
            user = user,
            standard = standard,
            kind = SourceEventKind.LIVE,
        )
    }

    private fun parseUsgs(
        action: String,
        payload: JSONObject,
        envelopeTime: Long,
        user: Pair<Double, Double>?,
        standard: IntensityStandard,
    ): PancakesParsed? {
        // USGS 只有 update；其它动作不参与。
        if (action.isNotEmpty() && action != "update") return null
        val rawId = firstString(payload, "eventId") ?: return null
        val latitude = firstDouble(payload, "latitude") ?: return null
        val longitude = firstDouble(payload, "longitude") ?: return null
        val magnitude = firstDouble(payload, "magnitude") ?: 0.0
        val depth = firstDouble(payload, "depth") ?: EewParser.DEFAULT_DEPTH
        val location = firstString(payload, "placeName") ?: EewParser.UNKNOWN_LOCATION
        val origin = firstLong(payload, "originTimeMs") ?: envelopeTime
        val infoType = firstString(payload, "infoType") ?: ""
        return build(
            source = PancakesProtocol.SOURCE_USGS,
            rawEventId = rawId,
            prefix = PREFIX_USGS,
            magnitude = magnitude,
            latitude = latitude,
            longitude = longitude,
            depth = depth,
            location = location,
            originTime = origin,
            maxText = "",
            maxRaw = 0.0,
            // 上游无递增报数：同报数不同内容会走 CORRECTION，仍能就地更新；
            // 更新但不重复告警（生命周期按 identity 判定 newEvent）。
            reportNum = 1,
            isFinal = infoType == "Reviewed",
            isCanceled = false,
            sourceUpdatedAt = firstLong(payload, "updatedTimeMs"),
            user = user,
            standard = standard,
            kind = SourceEventKind.LIVE,
        )
    }

    private fun parseJmaEew(
        payload: JSONObject,
        envelopeTime: Long,
        user: Pair<Double, Double>?,
        standard: IntensityStandard,
    ): PancakesParsed? {
        if (payload.optBoolean("isTraining", false)) return null
        val rawId = firstString(payload, "EventID") ?: firstString(payload, "id") ?: return null
        val magnitude = firstDouble(payload, "Magunitude") ?: firstDouble(payload, "Magnitude") ?: return null
        val latitude = firstDouble(payload, "Latitude") ?: return null
        val longitude = firstDouble(payload, "Longitude") ?: return null
        val depth = firstDouble(payload, "Depth") ?: EewParser.DEFAULT_DEPTH
        val location = firstString(payload, "Hypocenter") ?: EewParser.UNKNOWN_LOCATION
        val origin = EewParser.parseTime(firstString(payload, "OriginTime") ?: "") ?: envelopeTime
        val serial = firstInt(payload, "Serial") ?: 1
        val isCanceled = payload.optBoolean("isCancel", false) || payload.optBoolean("isCanceled", false)
        val (maxText, maxRaw) = EewParser.parseMaxIntensity(payload, standard)
        return build(
            source = PancakesProtocol.SOURCE_JMA_EEW,
            rawEventId = rawId,
            prefix = PREFIX_JMA_EEW,
            magnitude = magnitude,
            latitude = latitude,
            longitude = longitude,
            depth = depth,
            location = location,
            originTime = origin,
            maxText = maxText,
            maxRaw = maxRaw,
            reportNum = serial,
            isFinal = payload.optBoolean("isFinal", false),
            isCanceled = isCanceled,
            sourceUpdatedAt = EewParser.parseTime(firstString(payload, "AnnouncedTime") ?: ""),
            user = user,
            standard = standard,
            kind = SourceEventKind.LIVE,
        )
    }

    private fun parseJmaEqlist(
        action: String,
        payload: JSONObject,
        envelopeTime: Long,
        user: Pair<Double, Double>?,
        standard: IntensityStandard,
    ): PancakesParsed? {
        val rawId = firstString(payload, "eventId") ?: firstString(payload, "EventID") ?: return null
        val magnitude = firstDouble(payload, "magnitude") ?: 0.0
        val latitude = firstDouble(payload, "latitude") ?: return null
        val longitude = firstDouble(payload, "longitude") ?: return null
        val depth = firstDouble(payload, "depth") ?: EewParser.DEFAULT_DEPTH
        val location = firstString(payload, "placeName") ?: EewParser.UNKNOWN_LOCATION
        val origin = EewParser.parseTime(firstString(payload, "originTime") ?: "") ?: envelopeTime
        val serial = firstInt(payload, "serial") ?: 1
        val infoType = firstString(payload, "infoType") ?: ""
        val status = firstString(payload, "status") ?: ""
        val isCanceled = action == "cancelled" || infoType.contains("取消") || status.contains("取消")
        val reportTime = EewParser.parseTime(
            firstString(payload, "reportTime") ?: firstString(payload, "announcedTime") ?: "",
        )
        val (maxText, maxRaw) = EewParser.parseMaxIntensity(payload, standard)
        return build(
            source = PancakesProtocol.SOURCE_JMA_EQLIST,
            rawEventId = rawId,
            prefix = PREFIX_JMA_EQLIST,
            magnitude = magnitude,
            latitude = latitude,
            longitude = longitude,
            depth = depth,
            location = location,
            originTime = origin,
            maxText = maxText,
            maxRaw = maxRaw,
            reportNum = serial,
            isFinal = true,
            isCanceled = isCanceled,
            sourceUpdatedAt = reportTime,
            user = user,
            standard = standard,
            kind = SourceEventKind.DIRECTORY,
        )
    }

    // ---------------------------------------------------------------- 组装

    private fun build(
        source: String,
        rawEventId: String,
        prefix: String,
        magnitude: Double,
        latitude: Double,
        longitude: Double,
        depth: Double,
        location: String,
        originTime: Long,
        maxText: String,
        maxRaw: Double,
        reportNum: Int,
        isFinal: Boolean,
        isCanceled: Boolean,
        sourceUpdatedAt: Long?,
        user: Pair<Double, Double>?,
        standard: IntensityStandard,
        kind: SourceEventKind,
    ): PancakesParsed {
        val event = EarthquakeEvent(
            id = prefix + rawEventId,
            eventId = "$source:$rawEventId",
            magnitude = magnitude,
            latitude = latitude,
            longitude = longitude,
            depth = depth,
            location = location,
            timestamp = originTime,
            source = PancakesProtocol.titleFor(source),
            sourceProvider = PancakesProtocol.PROVIDER,
            sourceAgency = PancakesProtocol.agencyFor(source),
            distanceKm = EewParser.UNKNOWN_DISTANCE,
            estimatedIntensity = "--",
            rawIntensity = 0.0,
            maxIntensityRaw = maxRaw,
            maxIntensityText = maxText,
            reportNum = reportNum,
            isFinal = isFinal,
            isCanceled = isCanceled,
            sourceUpdatedAt = sourceUpdatedAt,
        )
        val recomputed = EewParser.recalculate(event, user, standard, kind == SourceEventKind.DIRECTORY)
        return PancakesParsed(recomputed, kind, source)
    }

    // ---------------------------------------------------------------- 工具

    private const val PREFIX_GQ = "pancakes_gq_"
    private const val PREFIX_USGS = "pancakes_usgs_"
    private const val PREFIX_JMA_EEW = "pancakes_jmaeew_"
    private const val PREFIX_JMA_EQLIST = "pancakes_jmaeqlist_"

    private fun prefixFor(source: String): String = when (source) {
        PancakesProtocol.SOURCE_GQ -> PREFIX_GQ
        PancakesProtocol.SOURCE_USGS -> PREFIX_USGS
        PancakesProtocol.SOURCE_JMA_EEW -> PREFIX_JMA_EEW
        PancakesProtocol.SOURCE_JMA_EQLIST -> PREFIX_JMA_EQLIST
        else -> "pancakes_"
    }

    private fun firstDouble(obj: JSONObject, key: String): Double? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val v = obj.opt(key)
        val d = when (v) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }
        return if (d != null && d.isFinite()) d else null
    }

    private fun firstInt(obj: JSONObject, key: String): Int? = firstDouble(obj, key)?.toInt()

    private fun firstLong(obj: JSONObject, key: String): Long? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val v = obj.opt(key)
        return when (v) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull()
            else -> null
        }
    }

    private fun firstString(obj: JSONObject, key: String): String? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val s = obj.optString(key, "")
        return if (s.isNotEmpty() && s != "null") s else null
    }
}
