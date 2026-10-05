package com.aloys23.komiraquake.data.gate

import com.aloys23.komiraquake.core.AppClock
import com.aloys23.komiraquake.model.EarthquakeEvent

enum class EventGateDecision { PASS, CORRECTION, DUPLICATE, STALE }

/**
 * 事件去重与报数管理。《NATIVE_PORT_SPEC》 §5。
 * 不做跨机构合并：key = source|id。
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
