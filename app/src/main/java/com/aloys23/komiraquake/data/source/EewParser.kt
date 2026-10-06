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
        originTimeIsJst: Boolean = false,
        eventNamespace: String = "",
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
        )?.let { if (originTimeIsJst) parseJstTime(it) else parseTime(it) } ?: now

        val (maxText, maxRaw) = parseMaxIntensity(obj, standard)
        // 频道化 eventId：跨聚合商对齐合并键（见 EarthquakeEvent.identity）。
        val eventId = if (eventNamespace.isEmpty()) rawId else "$eventNamespace:$rawId"
        return build(
            id, eventId, magnitude, latitude, longitude, depth, location, originTime,
            sourceTitle, user, standard, maxText, maxRaw, reportNum, isFinal, isCanceled,
        ).copy(
            sourceUpdatedAt = firstString(obj, listOf("ReportTime", "reportTime", "UpdateTime", "updateTime"))
                ?.let { if (originTimeIsJst) parseJstTime(it) else parseTime(it) },
        )
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
        val sourceTitle = "中国地震台网 地震信息"
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

    /** Wolfx jma_eqlist（JMA 地震情报）条目 → 目录事件。
     *  字段：EventID / time_full / location / magnitude / shindo / depth("10km") / latitude / longitude。 */
    fun parseJmaDirectory(
        obj: JSONObject,
        user: Pair<Double, Double>?,
        standard: IntensityStandard,
    ): EarthquakeEvent? {
        val latitude = firstDouble(obj, listOf("latitude", "Latitude")) ?: return null
        val longitude = firstDouble(obj, listOf("longitude", "Longitude")) ?: return null
        val magnitude = firstDouble(obj, listOf("magnitude", "Magnitude")) ?: 0.0
        val depth = parseDepthKm(firstString(obj, listOf("depth", "Depth"))) ?: DEFAULT_DEPTH
        val location = firstString(obj, listOf("location", "placeName"))?.takeIf { it.isNotBlank() }
            ?: UNKNOWN_LOCATION
        // time_full 含秒，time 只到分钟；两者都是 JST 墙钟。解析失败则丢弃，不退回 now。
        val timeRaw = firstString(obj, listOf("time_full", "time")) ?: return null
        val origin = parseJstTime(timeRaw) ?: return null
        val rawEventId = firstString(obj, listOf("EventID", "id"))
        val id = if (rawEventId.isNullOrEmpty()) {
            "wolfx_jmaeqlist_${origin}_${"%.2f".format(Locale.US, latitude)}"
        } else {
            "wolfx_jmaeqlist_$rawEventId"
        }
        // 与 Pancakes 的 jma_eqlist 共用事件命名空间，源内去重/合并口径一致。
        val eventId = if (rawEventId.isNullOrEmpty()) "jma_eqlist:$origin" else "jma_eqlist:$rawEventId"
        val (shindoText, shindoRaw) = parseShindo(firstString(obj, listOf("shindo", "Shindo")))
        val event = build(
            id, eventId, magnitude, latitude, longitude, depth, location, origin,
            "JMA 地震情报", user, standard, shindoText, shindoRaw,
            reportNum = 1, isFinal = true, isCanceled = false,
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

    /** "10km" → 10.0（去掉单位后缀）。 */
    private fun parseDepthKm(raw: String?): Double? {
        val s = raw?.trim() ?: return null
        if (s.isEmpty()) return null
        val end = s.indexOfFirst { !(it.isDigit() || it == '.' || it == '-') }
        val numeric = if (end < 0) s else s.substring(0, end)
        return numeric.toDoubleOrNull()
    }

    /** JMA 震度文本（"1"/"5-"/"5+"/"7"）→ (展示文本, 原始数值)。JMA 源固定按 JMA 展示，不随用户标准转换。 */
    private fun parseShindo(raw: String?): Pair<String, Double> {
        val s = raw?.trim() ?: return "" to 0.0
        if (s.isEmpty() || s == "null" || s == "-") return "" to 0.0
        val d = s.toDoubleOrNull()
        return s to (if (d != null) d else (JMA_TEXT_RAW[s] ?: 0.0))
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

    private val jstTimeFormats = listOf(
        "yyyy/MM/dd HH:mm:ss",
        "yyyy/MM/dd HH:mm",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
    )

    /**
     * 解析无时区的中国标准时间（UTC+8）墙钟，如 Whews 的 cenc / cea / usgs 等端点。
     * "2026-08-13 08:47:00" → 00:47:00Z。与 [parseTime] 的区别仅在时区假设：
     * parseTime 按系统本地时区解释，设备不在 UTC+8 时会把墙钟整体偏移。
     */
    fun parseUtc8Time(raw: String): Long? = parseFixedOffsetTime(raw, 8)

    /** 解析无时区的日本标准时间（JST, UTC+9）墙钟，如 Wolfx 的 jma_eqlist / jma_eew。
     * "2026/10/06 13:47:00" → 04:47:00Z。与 [parseTime] 的区别仅在时区假设：
     * parseTime 按系统本地时区解释，在 UTC+8 机器上会把 JST 墙钟整体晚算 1 小时。
     */
    fun parseJstTime(raw: String): Long? = parseFixedOffsetTime(raw, 9)

    /**
     * 把无时区墙钟按固定偏移解释为 epoch 毫秒。带时区/纯数字的输入交给 [parseTime]
     * 处理（本身语义明确，无需再套偏移）。
     */
    private fun parseFixedOffsetTime(raw: String, offsetHours: Int): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.all { it.isDigit() }) return parseTime(trimmed)
        for (pattern in jstTimeFormats) {
            try {
                val fmt = java.time.format.DateTimeFormatter.ofPattern(pattern, Locale.US)
                val ldt = java.time.LocalDateTime.parse(trimmed, fmt)
                return ldt.toInstant(java.time.ZoneOffset.ofHours(offsetHours)).toEpochMilli()
            } catch (_: Exception) {
                // 尝试下一个格式
            }
        }
        return null
    }
}
