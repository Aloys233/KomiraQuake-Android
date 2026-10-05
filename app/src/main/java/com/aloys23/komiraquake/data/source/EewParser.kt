package com.aloys23.komiraquake.data.source

import com.aloys23.komiraquake.core.AppClock
import com.aloys23.komiraquake.core.IntensityCalculator
import com.aloys23.komiraquake.core.IntensityStandard
import com.aloys23.komiraquake.core.QuakeCalculator
import com.aloys23.komiraquake.core.TravelTimeService
import com.aloys23.komiraquake.model.EarthquakeEvent
import com.aloys23.komiraquake.model.WarningLevel
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * EEW 字段解析（字段并集）。《NATIVE_PORT_SPEC》 §2.3 / §2.4。
 */
object EewParser {
    const val DEFAULT_DEPTH = 10.0
    const val UNKNOWN_LOCATION = "未知震源"

    /** 无定位时距离未知的哨兵值。 */
    const val UNKNOWN_DISTANCE = -1.0

    private val JMA_TEXT_RAW = mapOf(
        "5弱" to 5.0,
        "5-" to 5.0,
        "5強" to 5.5,
        "5强" to 5.5,
        "5+" to 5.5,
        "6弱" to 6.0,
        "6-" to 6.0,
        "6強" to 6.5,
        "6强" to 6.5,
        "6+" to 6.5,
        "7" to 7.0,
    )

    /**
     * 数据源报的最大烈度：MaxIntensity（EEW）→ epiIntensity → maxIntensity（Pancakes/JMA 速报）→ intensity。
     * 返回 (展示文本, 数值)。
     */
    internal fun parseMaxIntensity(obj: JSONObject, standard: IntensityStandard): Pair<String, Double> {
        for (key in listOf("MaxIntensity", "epiIntensity", "maxIntensity", "intensity")) {
            if (!obj.has(key) || obj.isNull(key)) continue
            when (val v = obj.opt(key)) {
                is Number -> {
                    val d = v.toDouble()
                    if (d.isFinite() && d > 0.0) {
                        val text = if (standard == IntensityStandard.JMA) {
                            "%.1f".format(d)
                        } else {
                            IntensityCalculator.formatCsis(d)
                        }
                        return text to d
                    }
                }
                is String -> {
                    val s = v.trim()
                    if (s.isEmpty() || s == "null" || s == "-") continue
                    val d = s.toDoubleOrNull()
                    return if (d != null) {
                        val text = if (standard == IntensityStandard.JMA) s else IntensityCalculator.formatCsis(d)
                        text to d
                    } else {
                        s to (JMA_TEXT_RAW[s] ?: 0.0)
                    }
                }
            }
        }
        return "" to 0.0
    }

    fun parse(
        obj: JSONObject,
        user: Pair<Double, Double>?,
        standard: IntensityStandard,
        sourceTitle: String,
        idPrefix: String,
        now: Long = AppClock.now(),
    ): EarthquakeEvent? {
        if (obj.optBoolean("isTraining", false)) return null

        val magnitudeUnknown = obj.optBoolean("magnitudeUnknown", false)
        val magnitude = firstDouble(
            obj,
            listOf("Magnitude", "Magunitude"),
        ) ?: if (magnitudeUnknown) null else firstDouble(obj, listOf("magnitude"))
        val latitude = firstDouble(obj, listOf("Latitude", "latitude"))
        val longitude = firstDouble(obj, listOf("Longitude", "longitude"))
        if (magnitude == null || latitude == null || longitude == null) return null

        val depth = firstDouble(obj, listOf("Depth", "depth")) ?: DEFAULT_DEPTH
        val location = firstString(
            obj,
            listOf("HypoCenter", "Hypocenter", "location", "placeName"),
        )?.takeIf { it.isNotBlank() } ?: UNKNOWN_LOCATION

        val rawId = firstString(obj, listOf("EventID", "ID", "id", "eventId")) ?: now.toString()
        val id = idPrefix + rawId
        val reportNum = firstInt(obj, listOf("ReportNum", "Serial", "serial", "number", "updates")) ?: 1
        val isFinal = obj.optBoolean("isFinal", false)
        val isCanceled = obj.optBoolean("isCancel", obj.optBoolean("isCanceled", false))
        val originTime = firstString(
            obj,
            listOf("OriginTime", "originTime", "shockTime", "time"),
        )?.let { parseTime(it) } ?: now

        val (maxText, maxRaw) = parseMaxIntensity(obj, standard)
        return build(
            id, rawId, magnitude, latitude, longitude, depth, location, originTime,
            sourceTitle, user, standard, maxText, maxRaw, reportNum, isFinal, isCanceled,
        ).copy(sourceUpdatedAt = firstString(obj, listOf("ReportTime", "reportTime", "UpdateTime", "updateTime"))?.let(::parseTime))
    }

    /** CENC 目录条目的合成事件（永不产生 warning/critical）。 */
    fun parseCencDirectory(
        obj: JSONObject,
        user: Pair<Double, Double>?,
        standard: IntensityStandard,
        now: Long = AppClock.now(),
    ): EarthquakeEvent? {
        val magnitude = firstDouble(obj, listOf("magnitude", "Magnitude")) ?: return null
        val latitude = firstDouble(obj, listOf("latitude", "Latitude")) ?: return null
        val longitude = firstDouble(obj, listOf("longitude", "Longitude")) ?: return null
        val depth = firstDouble(obj, listOf("depth", "Depth")) ?: DEFAULT_DEPTH
        val location = firstString(obj, listOf("location", "placeName"))?.takeIf { it.isNotBlank() }
            ?: UNKNOWN_LOCATION
        val origin = firstString(obj, listOf("time", "originTime"))?.let { parseTime(it) } ?: now
        val reviewed = firstString(obj, listOf("type")) == "reviewed"
        val sourceTitle = if (reviewed) "CENC 正式测定" else "CENC 自动测定"
        // 优先用数据源自带的 EventID（跨报次稳定，且可与 WS 预警链路对齐）
        val rawEventId = firstString(obj, listOf("EventID", "id")) ?: ""
        val id = if (rawEventId.isEmpty()) {
            "cenc_${origin}_${"%.2f".format(Locale.US, latitude)}"
        } else {
            "cenc_$rawEventId"
        }

        val (maxText, maxRaw) = parseMaxIntensity(obj, standard)
        val event = build(
            id, rawEventId, magnitude, latitude, longitude, depth, location, origin,
            sourceTitle, user, standard, maxText, maxRaw, reportNum = 1, isFinal = true, isCanceled = false,
        )
        // 目录永不产生 warning/critical：若有则降级为 watch。
        val level = event.warningLevel
        return event.copy(
            warningLevel = if (level == WarningLevel.WARNING || level == WarningLevel.CRITICAL) {
                WarningLevel.WATCH
            } else {
                level
            },
        )
    }

    /** Recompute local values without passing through network deduplication. */
    fun recalculate(event: EarthquakeEvent, user: Pair<Double, Double>?, standard: IntensityStandard,
                    directory: Boolean = false): EarthquakeEvent {
        val derived = build(event.id, event.eventId, event.magnitude, event.latitude, event.longitude,
            event.depth, event.location, event.timestamp, event.source, user, standard,
            event.maxIntensityText, event.maxIntensityRaw, event.reportNum, event.isFinal, event.isCanceled)
        return event.copy(distanceKm = derived.distanceKm, rawIntensity = derived.rawIntensity,
            estimatedIntensity = derived.estimatedIntensity, pWaveArrival = derived.pWaveArrival,
            sWaveArrival = derived.sWaveArrival, warningLevel = if (directory && derived.warningLevel.isAlert)
                WarningLevel.WATCH else derived.warningLevel)
    }

    private fun build(
        id: String,
        eventId: String,
        magnitude: Double,
        latitude: Double,
        longitude: Double,
        depth: Double,
        location: String,
        originTime: Long,
        sourceTitle: String,
        user: Pair<Double, Double>?,
        standard: IntensityStandard,
        maxIntensityText: String,
        maxIntensityRaw: Double,
        reportNum: Int,
        isFinal: Boolean,
        isCanceled: Boolean,
    ): EarthquakeEvent {
        if (user == null) {
            // 无定位：距离/烈度/走时未知，仅按震级判定等级
            return EarthquakeEvent(
                id = id,
                eventId = eventId,
                magnitude = magnitude,
                latitude = latitude,
                longitude = longitude,
                depth = depth,
                location = location,
                timestamp = originTime,
                source = sourceTitle,
                distanceKm = UNKNOWN_DISTANCE,
                estimatedIntensity = "--",
                rawIntensity = 0.0,
                maxIntensityRaw = maxIntensityRaw,
                maxIntensityText = maxIntensityText,
                pWaveArrival = null,
                sWaveArrival = null,
                warningLevel = deriveLevelByMagnitude(magnitude, isCanceled),
                reportNum = reportNum,
                isFinal = isFinal,
                isCanceled = isCanceled,
            )
        }
        val distance = QuakeCalculator.haversineDistance(user.first, user.second, latitude, longitude)
        val (pTravel, sTravel) = TravelTimeService.estimateTravelTimes(depth, distance)
        val rawIntensity = IntensityCalculator.rawCsis(magnitude, distance, depth)
        val estimated = when (standard) {
            IntensityStandard.CSIS -> IntensityCalculator.formatCsis(rawIntensity)
            IntensityStandard.JMA -> IntensityCalculator.formatJma(magnitude, distance, depth)
        }
        val level = deriveLevel(rawIntensity, magnitude, isCanceled)
        return EarthquakeEvent(
            id = id,
            eventId = eventId,
            magnitude = magnitude,
            latitude = latitude,
            longitude = longitude,
            depth = depth,
            location = location,
            timestamp = originTime,
            source = sourceTitle,
            distanceKm = distance,
            estimatedIntensity = estimated,
            rawIntensity = rawIntensity,
            maxIntensityRaw = maxIntensityRaw,
            maxIntensityText = maxIntensityText,
            pWaveArrival = originTime + (pTravel * 1000).toLong(),
            sWaveArrival = originTime + (sTravel * 1000).toLong(),
            warningLevel = level,
            reportNum = reportNum,
            isFinal = isFinal,
            isCanceled = isCanceled,
        )
    }

    fun deriveLevel(rawIntensity: Double, magnitude: Double, isCanceled: Boolean): WarningLevel {
        if (isCanceled) return WarningLevel.NORMAL
        return when {
            rawIntensity >= 5.0 || magnitude >= 6.5 -> WarningLevel.CRITICAL
            rawIntensity >= 3.0 || magnitude >= 4.5 -> WarningLevel.WARNING
            rawIntensity >= 1.5 || magnitude >= 3.0 -> WarningLevel.WATCH
            else -> WarningLevel.NORMAL
        }
    }

    /** 无定位（烈度未知）时仅按震级判定。 */
    fun deriveLevelByMagnitude(magnitude: Double, isCanceled: Boolean): WarningLevel {
        if (isCanceled) return WarningLevel.NORMAL
        return when {
            magnitude >= 6.5 -> WarningLevel.CRITICAL
            magnitude >= 4.5 -> WarningLevel.WARNING
            magnitude >= 3.0 -> WarningLevel.WATCH
            else -> WarningLevel.NORMAL
        }
    }

    private fun firstDouble(obj: JSONObject, keys: List<String>): Double? {
        for (k in keys) {
            if (obj.has(k) && !obj.isNull(k)) {
                val v = obj.opt(k)
                val d = when (v) {
                    is Number -> v.toDouble()
                    is String -> v.toDoubleOrNull()
                    else -> null
                }
                if (d != null && d.isFinite()) return d
            }
        }
        return null
    }

    private fun firstInt(obj: JSONObject, keys: List<String>): Int? {
        val d = firstDouble(obj, keys) ?: return null
        return d.toInt()
    }

    private fun firstString(obj: JSONObject, keys: List<String>): String? {
        for (k in keys) {
            if (obj.has(k) && !obj.isNull(k)) {
                val s = obj.optString(k, "")
                if (s.isNotEmpty() && s != "null") return s
            }
        }
        return null
    }

    private val timeFormats = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss.SSS",
    )

    fun parseTime(raw: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.all { it.isDigit() }) {
            val v = trimmed.toLongOrNull() ?: return null
            return if (v < 100_000_000_000L) v * 1000 else v
        }
        // ISO-8601：可带 Z / 时区偏移 / 小数秒。Pancakes 的时间统一为此格式。
        try {
            return java.time.Instant.parse(trimmed).toEpochMilli()
        } catch (_: Exception) { }
        try {
            return java.time.OffsetDateTime.parse(trimmed).toInstant().toEpochMilli()
        } catch (_: Exception) { }
        try {
            return java.time.LocalDateTime.parse(trimmed)
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: Exception) { }
        for (pattern in timeFormats) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                fmt.isLenient = false
                val d: Date = fmt.parse(trimmed) ?: continue
                return d.time
            } catch (_: Exception) {
                // 尝试下一个格式
            }
        }
        return null
    }
}
