package com.aloys23.komiraquake.data.gate

import com.aloys23.komiraquake.model.EarthquakeEvent

/** Small deterministic live-event reducer. Final reports remain live until the bounded expiry. */
class EventLifecycle(private val now: () -> Long, private val maxLifetimeMs: Long = 30 * 60_000L,
                     private val afterArrivalMs: Long = 60_000L,
                     private val unknownArrivalMs: Long = 5 * 60_000L,
                     restoredTerminals: Map<String, Long> = emptyMap(),
                     private val persistTerminal: (String, Long) -> Unit = { _, _ -> }) {
    private val gate = EventGate()
    private val live = LinkedHashMap<String, EarthquakeEvent>()
    private val ended = LinkedHashMap(restoredTerminals.filterValues { it > now() })
    val events: List<EarthquakeEvent> get() = live.values.toList()

    fun accept(event: EarthquakeEvent): EventGateDecision {
        expire()
        if (event.identity in ended) return EventGateDecision.STALE
        if (expired(event)) {
            stop(event.identity)
            return EventGateDecision.STALE
        }
        val decision = gate.admit(event, now())
        if (decision == EventGateDecision.DUPLICATE || decision == EventGateDecision.STALE) return decision
        if (event.isCanceled) stop(event.identity) else live[event.identity] = event
        return decision
    }

    fun stop(identity: String) {
        // Write before publishing terminal state, so process death cannot lose the tombstone.
        // One hour covers the entire live replay window, including corrected arrival times.
        val expiresAt = maxOf(ended[identity] ?: 0, now() + maxLifetimeMs * 2)
        persistTerminal(identity, expiresAt)
        ended[identity] = expiresAt
        live.remove(identity)
    }

    fun recalculate(transform: (EarthquakeEvent) -> EarthquakeEvent) {
        live.replaceAll { _, event -> transform(event) }
        expire()
    }

    fun expire(): List<EarthquakeEvent> {
        val expired = live.values.filter(::expired)
        expired.forEach { stop(it.identity) }
        // Replay origin-age checks remain authoritative after tombstones age out.
        ended.entries.removeAll { now() >= it.value }
        return expired
    }

    private fun expired(event: EarthquakeEvent): Boolean {
        val time = now()
        if (event.timestamp <= 0 || event.timestamp > time + 60_000L) return true
        val deadline = minOf(event.timestamp + maxLifetimeMs,
            event.sWaveArrival?.plus(afterArrivalMs) ?: (event.timestamp + unknownArrivalMs))
        return time >= deadline
    }
}
