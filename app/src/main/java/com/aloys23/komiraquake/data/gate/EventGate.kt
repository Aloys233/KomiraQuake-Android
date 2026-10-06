package com.aloys23.komiraquake.data.gate

import com.aloys23.komiraquake.core.AppClock
import com.aloys23.komiraquake.model.EarthquakeEvent

enum class EventGateDecision { PASS, CORRECTION, DUPLICATE, STALE }

/**
 * 事件去重与报数管理。《NATIVE_PORT_SPEC》 §5。
 * key = 报数机构 + 频道化 eventId（[EarthquakeEvent.identity]，不含聚合商）：同一份上游报文
 * 经 Wolfx 与 Pancakes 两路送达时落进同一条目，互为备份而不重复。
 */
class EventGate(
    private val maxAgeMs: Long = 60L * 60L * 1000L,
    private val maxEntries: Int = 1024,
) {
    private class Entry(var event: EarthquakeEvent, var seenAt: Long)

    private val entries = HashMap<String, Entry>()

    @Synchronized
    fun admit(event: EarthquakeEvent, now: Long = AppClock.now()): EventGateDecision {
        prune(now)
        val key = keyOf(event)
        val prev = entries[key]
        if (prev == null) {
            entries[key] = Entry(event, now)
            return EventGateDecision.PASS
        }
        prev.seenAt = now
        if (event.reportNum < prev.event.reportNum) return EventGateDecision.STALE
        if (event.reportNum == prev.event.reportNum) {
            if (sameBody(event, prev.event)) return EventGateDecision.DUPLICATE
            // 跨聚合商同报次：现任优先（先到者胜）。两路转发的同一报文若字段有细微差异，
            // 会随各自轮询反复互相覆盖而抖动；只有更高报次才接管。同源修正仍生效。
            if (event.sourceProvider != prev.event.sourceProvider) return EventGateDecision.DUPLICATE
            prev.event = event
            return EventGateDecision.CORRECTION
        }
        prev.event = event
        return EventGateDecision.PASS
    }

    @Synchronized
    fun clear() = entries.clear()

    private fun prune(now: Long) {
        if (entries.isEmpty()) return
        val it = entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value.seenAt > maxAgeMs) it.remove()
        }
        if (entries.size > maxEntries) {
            val excess = entries.size - maxEntries
            entries.entries
                .sortedBy { it.value.seenAt }
                .take(excess)
                .forEach { entries.remove(it.key) }
        }
    }

    private fun sameBody(a: EarthquakeEvent, b: EarthquakeEvent): Boolean =
        a.magnitude == b.magnitude &&
            a.latitude == b.latitude &&
            a.longitude == b.longitude &&
            a.depth == b.depth &&
            a.location == b.location &&
            a.isFinal == b.isFinal &&
            a.isCanceled == b.isCanceled &&
            a.timestamp == b.timestamp &&
            a.sourceUpdatedAt == b.sourceUpdatedAt &&
            a.maxIntensityRaw == b.maxIntensityRaw &&
            a.maxIntensityText == b.maxIntensityText

    private fun keyOf(e: EarthquakeEvent): String = e.identity
}
