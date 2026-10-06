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
    // 跨聚合商的同一份 JMA EEW 报文：identity 不含 provider，二者落进同一会话；
    // 同报次现任优先（不抖动），更高报次由另一路接管（互为备份）。
    @Test fun crossSourceLiveReportsMergeIntoOneSession() {
        val reducer = EventLifecycle({ clock })
        val wolfx = event().copy(
            sourceProvider = "Wolfx", sourceAgency = "JMA",
            eventId = "jma_eew:20261005223109", reportNum = 4, magnitude = 4.6,
        )
        val pancakes = wolfx.copy(sourceProvider = "Pancakes", magnitude = 4.7)
        assertEquals(wolfx.identity, pancakes.identity)

        assertEquals(EventGateDecision.PASS, reducer.accept(wolfx))
        // 同报次的另一聚合商：现任优先 → DUPLICATE，不覆盖、不新增。
        assertEquals(EventGateDecision.DUPLICATE, reducer.accept(pancakes))
        assertEquals(1, reducer.events.size)
        assertEquals(4.6, reducer.events.single().magnitude, 0.0)
        // 更高报次由另一路接管。
        val newer = pancakes.copy(reportNum = 5, magnitude = 4.8)
        assertEquals(EventGateDecision.PASS, reducer.accept(newer))
        assertEquals(1, reducer.events.size)
        assertEquals(4.8, reducer.events.single().magnitude, 0.0)
        assertEquals("Pancakes", reducer.events.single().sourceProvider)
        // 任一路取消 → 合并键终止，另一路后续报文被挡住、不复活。
        reducer.accept(newer.copy(isCanceled = true))
        assertTrue(reducer.events.isEmpty())
        assertEquals(EventGateDecision.STALE, reducer.accept(wolfx.copy(reportNum = 6)))
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

    /**
     * 发震时刻超出现在 +60s 的帧判过期丢弃。这条守卫是模拟源能安全工作的前提：
     * 服务端把发震时刻钳到 +55s 就是为了留余量，否则「未来到时」的提醒会挂起。
     * 桌面端 `EarthquakeEvent::expired()` 已补齐同一规则。
     */
    @Test fun futureOriginIsRejected() {
        val reducer = EventLifecycle({ clock })
        val base = event()
        assertEquals(EventGateDecision.PASS, reducer.accept(base))
        // +60s 之内的未来时刻仍放行。
        val soon = base.copy(eventId = "B", id = "B", reportNum = 1, timestamp = clock + 55_000L)
        assertEquals(EventGateDecision.PASS, reducer.accept(soon))
        // 超 +60s：判过期，且不进入活动事件。
        val future = base.copy(eventId = "C", id = "C", reportNum = 1, timestamp = clock + 5 * 60_000L)
        assertEquals(EventGateDecision.STALE, reducer.accept(future))
        assertFalse(reducer.events.any { it.identity == future.identity })
    }

    /**
     * 机构 SIM 的模拟报文与真实报文必须并存：合并键是 `sourceAgency|eventId`，
     * 撞键会让模拟数据静默覆盖真实预警。
     */
    @Test fun simulatedAgencyNeverCollidesWithRealSources() {
        val reducer = EventLifecycle({ clock })
        val cenc = event().copy(sourceAgency = "CENC", eventId = "CD.1", id = "wolfx_CD.1")
        val sim = event().copy(
            sourceAgency = "SIM", sourceProvider = "Simulated",
            eventId = "sim-1-a", id = "sim_sim-1-a",
        )
        assertNotEquals(cenc.identity, sim.identity)
        assertEquals(EventGateDecision.PASS, reducer.accept(cenc))
        assertEquals(EventGateDecision.PASS, reducer.accept(sim))
        assertEquals(2, reducer.events.size)
        // 反向对照：同机构同 id 确实合并（证明差异来自机构而非巧合）。
        assertEquals(
            cenc.identity,
            cenc.copy(sourceProvider = "Pancakes", id = "pancakes_CD.1").identity,
        )
    }

    /** 同 id 重放落在墓碑窗口内被压制：模拟源「新一轮」铸新 id 正是为此。 */
    @Test fun reusedSimulatedEventIdIsSuppressedByTombstone() {
        val reducer = EventLifecycle({ clock })
        val sim = event().copy(
            sourceAgency = "SIM", sourceProvider = "Simulated",
            eventId = "sim-1-a", id = "sim_sim-1-a", isCanceled = true,
        )
        // 取消报即写入终态墓碑。
        reducer.accept(sim)
        assertTrue(reducer.events.isEmpty())
        // 同一 id 换一报再来：不得重开已结束的提醒。
        val replay = sim.copy(isCanceled = false, reportNum = 2)
        assertEquals(EventGateDecision.STALE, reducer.accept(replay))
        assertTrue(reducer.events.isEmpty())
        // 换新 id 则照常生效。
        val fresh = sim.copy(isCanceled = false, eventId = "sim-2-b", id = "sim_sim-2-b")
        assertEquals(EventGateDecision.PASS, reducer.accept(fresh))
        assertEquals(1, reducer.events.size)
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
        // 不同 eventId 仍是独立身份，不被 tombstone 影响。
        assertEquals(EventGateDecision.PASS, recovered.accept(stopped.copy(eventId = "stop-other")))
        // 仅换聚合商（agency + eventId 相同）是同一身份：仍被 tombstone 挡住（跨源合并语义）。
        assertEquals(EventGateDecision.STALE, recovered.accept(stopped.copy(sourceProvider = "Pancakes")))
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
