package com.aloys23.komiraquake.core

import com.aloys23.komiraquake.data.gate.EventGateDecision
import com.aloys23.komiraquake.data.gate.EventLifecycle
import com.aloys23.komiraquake.data.source.EewParser
import com.aloys23.komiraquake.model.EarthquakeEvent
import org.junit.Assert.*
import org.junit.Test

class EventLifecycleTest {
    private var clock = 1_000_000L
    private fun event(id: String = "A") = EarthquakeEvent(id = id, eventId = id,
        magnitude = 5.0, latitude = 30.0, longitude = 120.0, depth = 10.0,
        location = "test", timestamp = clock - 1000, source = "agency", sourceProvider = "Wolfx",
        sourceAgency = "CENC", distanceKm = -1.0, estimatedIntensity = "--")

    @Test fun fullReportSequenceKeepsFinalAliveAndCancellationTerminal() {
        val reducer = EventLifecycle({ clock })
        val first = event()
        assertEquals(EventGateDecision.PASS, reducer.accept(first))
        assertEquals(EventGateDecision.DUPLICATE, reducer.accept(first))
        val correction = first.copy(magnitude = 5.2, maxIntensityRaw = 7.0, sourceUpdatedAt = clock)
        assertEquals(EventGateDecision.CORRECTION, reducer.accept(correction))
        assertEquals(5.2, reducer.events.single().magnitude, 0.0)
        val final = correction.copy(reportNum = 2, isFinal = true)
        assertEquals(EventGateDecision.PASS, reducer.accept(final))
        assertEquals(1, reducer.events.size)
        assertEquals(EventGateDecision.CORRECTION, reducer.accept(final.copy(isCanceled = true)))
        assertTrue(reducer.events.isEmpty())
        assertEquals(EventGateDecision.STALE, reducer.accept(final.copy(reportNum = 3)))
    }
    @Test fun cancellationAndStopAreIsolatedByAgencyAndIdentity() {
        val reducer = EventLifecycle({ clock })
        val a = event(); val b = event().copy(sourceAgency = "JMA")
        reducer.accept(a); reducer.accept(b)
        reducer.accept(a.copy(isCanceled = true))
        assertEquals(listOf(b), reducer.events)
        reducer.stop(b.identity)
        assertEquals(EventGateDecision.STALE, reducer.accept(b.copy(reportNum = 10)))
        assertEquals(EventGateDecision.PASS, reducer.accept(event("C")))
    }
    @Test fun expirationDoesNotDependOnUiAndReplayCannotResurrect() {
        val reducer = EventLifecycle({ clock })
        val a = event().copy(sWaveArrival = clock + 5000)
        reducer.accept(a)
        clock += 5000
        assertTrue(reducer.expire().isEmpty())
        clock += 59_999
        assertTrue(reducer.expire().isEmpty())
        clock += 1
        assertEquals(listOf(a), reducer.expire())
        assertEquals(EventGateDecision.STALE, reducer.accept(a.copy(reportNum = 3)))
        clock += 2_000_000
        reducer.expire()
        assertEquals(EventGateDecision.STALE, reducer.accept(a.copy(reportNum = 4)))
    }
    @Test fun noLocationHasUnknownArrivalAndBoundedLifetime() {
        val reducer = EventLifecycle({ clock })
        val a = event()
        assertEquals(-1, a.remainingSeconds(clock))
        assertFalse(a.isSWaveArrived(clock))
        reducer.accept(a)
        clock = a.timestamp + 5 * 60_000L - 1
        assertTrue(reducer.expire().isEmpty())
        clock += 1
        reducer.expire()
        assertTrue(reducer.events.isEmpty())
    }
    @Test fun hardLimitBoundsEvenFarFutureArrival() {
        val reducer = EventLifecycle({ clock })
        val a = event().copy(sWaveArrival = clock + 60 * 60_000L)
        reducer.accept(a)
        clock = a.timestamp + 30 * 60_000L - 1
        assertTrue(reducer.expire().isEmpty())
        clock++
        assertEquals(listOf(a), reducer.expire())
    }
    @Test fun warningRequiresMasterSwitchAndLocalIntensityOnly() {
        // 预警总开关默认关闭：任何事件都不提醒。
        val off = com.aloys23.komiraquake.data.prefs.Settings()
        val felt = event().copy(distanceKm = 0.0, rawIntensity = 5.0)
        assertFalse(com.aloys23.komiraquake.service.AlertPolicy.evaluate(felt, off).eligible)
        // 开关打开后不再有震级过滤：小震级但本地烈度达标也提醒；取消报不提醒。
        val on = com.aloys23.komiraquake.data.prefs.Settings(enableWarnings = true)
        assertTrue(com.aloys23.komiraquake.service.AlertPolicy.evaluate(
            felt.copy(magnitude = 1.0), on).eligible)
        assertFalse(com.aloys23.komiraquake.service.AlertPolicy.evaluate(
            felt.copy(isCanceled = true), on).eligible)
    }
    @Test fun terminalPersistenceRestoresStopCancelAndExpiryWithoutAffectingOtherIdentities() {
        val disk = linkedMapOf<String, Long>()
        fun restart() = EventLifecycle({ clock }, restoredTerminals = disk.toMap(),
            persistTerminal = { identity, expiry -> disk[identity] = expiry })
        val stopped = event("stop")
        val canceled = event("cancel")
        val expired = event("expired").copy(sWaveArrival = clock)
        val first = restart()
        listOf(stopped, canceled, expired).forEach { first.accept(it) }
        first.stop(stopped.identity)
        first.accept(canceled.copy(isCanceled = true))
        clock += 60_000
        first.expire()
        assertEquals(3, disk.size)
        val recovered = restart()
        for (old in listOf(stopped, canceled, expired)) {
            assertTrue(disk.getValue(old.identity) >= clock + 30 * 60_000L)
            // A corrected arrival and higher report must not resurrect a terminal event.
            assertEquals(EventGateDecision.STALE, recovered.accept(old.copy(reportNum = 99,
                sWaveArrival = clock + 60_000)))
        }
        assertEquals(EventGateDecision.PASS, recovered.accept(stopped.copy(sourceAgency = "JMA")))
        assertEquals(EventGateDecision.PASS, recovered.accept(stopped.copy(sourceProvider = "Other")))
    }

    @Test fun restoredTombstonesExpireButOldOriginRemainsRejected() {
        val old = event()
        val reducer = EventLifecycle({ clock }, restoredTerminals = mapOf(old.identity to clock - 1))
        assertEquals(EventGateDecision.PASS, reducer.accept(old))
        clock += 31 * 60_000L
        val restarted = EventLifecycle({ clock })
        assertEquals(EventGateDecision.STALE, restarted.accept(old))
    }

    @Test fun localIntensityFilterGatesAndUnknownLocationPasses() {
        val settings = com.aloys23.komiraquake.data.prefs.Settings(
            enableWarnings = true, localIntensityFilter = 5.0)
        val located = event().copy(distanceKm = 0.0, magnitude = 6.0, rawIntensity = 2.0)
        // 震级再大，本地烈度未达过滤阈值 → 不提醒。
        assertFalse(com.aloys23.komiraquake.service.AlertPolicy.evaluate(located, settings).eligible)
        assertTrue(com.aloys23.komiraquake.service.AlertPolicy.evaluate(
            located.copy(rawIntensity = 6.0), settings).eligible)
        // 无定位无法判定本地烈度：放行。
        assertTrue(com.aloys23.komiraquake.service.AlertPolicy.evaluate(
            located.copy(distanceKm = -1.0, rawIntensity = 0.0), settings).eligible)
    }

    @Test fun localRecalculationBypassesGateAndHandlesLossAndMovement() {
        val reducer = EventLifecycle({ clock })
        val a = event()
        reducer.accept(a)
        reducer.recalculate { EewParser.recalculate(it, 30.1 to 120.1, IntensityStandard.CSIS) }
        val near = reducer.events.single()
        assertTrue(near.distanceKm > 0)
        assertNotNull(near.sWaveArrival)
        reducer.recalculate { EewParser.recalculate(it, 32.0 to 123.0, IntensityStandard.JMA) }
        assertTrue(reducer.events.single().distanceKm > near.distanceKm)
        reducer.recalculate { EewParser.recalculate(it, null, IntensityStandard.JMA) }
        assertNull(reducer.events.single().sWaveArrival)
        assertEquals(EventGateDecision.DUPLICATE, reducer.accept(a))
    }
}
