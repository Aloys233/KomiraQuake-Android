package com.aloys23.komiraquake.data.source.jian

import com.aloys23.komiraquake.core.IntensityStandard
import com.aloys23.komiraquake.data.source.EewParser
import com.aloys23.komiraquake.data.source.SourceEventKind
import com.aloys23.komiraquake.model.EarthquakeEvent
import org.json.JSONObject

/** Jian 业务帧解析。字段约定见 https://api.sismotide.top/api/ §5/§6。 */
object JianParser {

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

    /** 发震时刻：数字按 epoch 毫秒，字符串走 EewParser.parseTime（ISO 带偏移可正确解析）。 */
    private fun originTimeMs(obj: JSONObject): Long {
        if (!obj.has("originTime") || obj.isNull("originTime")) return 0
        return when (val v = obj.opt("originTime")) {
            is Number -> if (v.toDouble() > 0) v.toLong() else 0
            is String -> EewParser.parseTime(v) ?: 0
            else -> 0
        }
    }

    /** JMA 震度文本（"1"/"5-"/"5+"/"7"）→ 原始数值。 */
    private fun jmaTextToRaw(s: String): Double = when (s) {
        "5弱", "5-" -> 5.0
        "5強", "5强", "5+" -> 5.5
        "6弱", "6-" -> 6.0
        "6強", "6强", "6+" -> 6.5
        "7" -> 7.0
        else -> s.toDoubleOrNull() ?: 0.0
    }

    /**
     * cenc 的 id 形如 `CD.20260819132221.000_M`（`_M` 正式 / `_A` 自动）；
     * 去掉后缀即与 Wolfx cenc_eqlist 的 EventID 对齐。
     */
    private fun normalizeUpstreamId(id: String): String =
        if (id.endsWith("_M") || id.endsWith("_A")) id.dropLast(2) else id

    /** 单个业务记录（Data 对象）→ 事件。必填字段缺失返回 null。 */
    fun parseRecord(
        channel: JianChannel,
        data: JSONObject,
        user: Pair<Double, Double>?,
        standard: IntensityStandard,
    ): EarthquakeEvent? {
        val latitude = firstDouble(data, "latitude", "Latitude") ?: return null
        val longitude = firstDouble(data, "longitude", "Longitude") ?: return null
        val rawId = firstString(data, "id", "ID", "EventID") ?: return null
        val origin = originTimeMs(data)
        if (origin <= 0) return null

        val magnitude = firstDouble(data, "magnitude", "Magnitude") ?: 0.0
        val depth = firstDouble(data, "depth", "Depth") ?: EewParser.DEFAULT_DEPTH
        val location = firstString(data, "placeName", "place", "location")
            ?.takeIf { it.isNotBlank() } ?: EewParser.UNKNOWN_LOCATION
        // 报次：EEW 用 number/serial；速报多为 1。
        val reportNum = firstInt(data, "number", "serial", "Serial") ?: 1

        // 最大烈度：JMA 震度文本或 CWA/CENC 数值。
        var intensityText = ""
        var intensityRaw = 0.0
        if (data.has("intensity") && !data.isNull("intensity")) {
            val v = data.opt("intensity")
            if (v is Number) {
                intensityRaw = v.toDouble()
                intensityText = v.toLong().toString()
            } else {
                intensityText = v?.toString()?.trim().orEmpty()
                intensityRaw = jmaTextToRaw(intensityText)
            }
        } else {
            firstDouble(data, "maxIntensity")?.let {
                intensityRaw = it
                intensityText = it.toLong().toString()
            }
        }

        var canceled = data.optBoolean("isCancel", false)
        if (!canceled) {
            val infoType = data.optString("infoType", "")
            val infoTypeName = data.optString("infoTypeName", "")
            canceled = infoType.contains("取消") || infoTypeName.contains("取消")
        }

        val base = if (channel.eventNs.isEmpty()) normalizeUpstreamId(rawId) else rawId
        val eventId = if (channel.eventNs.isEmpty()) base else "${channel.eventNs}:$base"

        val event = EarthquakeEvent(
            id = "${channel.type}_$base",
            eventId = eventId,
            magnitude = magnitude,
            latitude = latitude,
            longitude = longitude,
            depth = depth,
            location = location,
            timestamp = origin,
            source = channel.type,
            sourceProvider = JianProtocol.PROVIDER,
            sourceAgency = channel.agency,
            maxIntensityText = intensityText,
            maxIntensityRaw = intensityRaw,
            reportNum = reportNum,
            isFinal = channel.kind == SourceEventKind.DIRECTORY || data.optBoolean("isFinal", false),
            isCanceled = canceled,
            distanceKm = EewParser.UNKNOWN_DISTANCE,
            estimatedIntensity = "--",
        )
        return EewParser.recalculate(event, user, standard, channel.kind == SourceEventKind.DIRECTORY)
    }

    /** 认证响应 {ok:true, token:"…"} → token；否则 null。 */
    fun parseAuthToken(body: String): String? = try {
        val obj = JSONObject(body)
        if (obj.optBoolean("ok", false)) obj.optString("token").ifEmpty { null } else null
    } catch (_: Exception) {
        null
    }
}
