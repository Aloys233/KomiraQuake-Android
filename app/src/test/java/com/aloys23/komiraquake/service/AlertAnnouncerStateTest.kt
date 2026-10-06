package com.aloys23.komiraquake.service

import org.junit.Assert.*
import org.junit.Test

class AlertAnnouncerStateTest {
    @Test fun firstReportCapturedBeforeIssuedAndCorrectionsAreSilent() {
        val state = AlertAnnouncer.EventState()
        assertTrue(state.acceptReport(1, false)!!.first)
        assertTrue(state.issued)
        assertNull(state.acceptReport(1, false))
        assertFalse(state.acceptReport(2, false)!!.first)
        assertNull(state.acceptReport(1, false))
        assertNull(state.acceptReport(2, true))
        assertTrue(state.final)
        assertNull(state.acceptReport(2, true))
    }

    @Test fun updatesDoNotResetArrivalCountdownOrMuteMarkers() {
        val state = AlertAnnouncer.EventState()
        state.acceptReport(1, false)
        state.arrived = true
        state.intense = true
        state.muted = true
        state.stopped = true
        state.countdowns.add(10)
        state.acceptReport(2, false)
        state.acceptReport(2, true)
        assertTrue(state.arrived)
        assertTrue(state.intense)
        assertTrue(state.muted)
        assertTrue(state.stopped)
        assertFalse(state.countdowns.add(10))
    }

    @Test fun higherReportFinalCanAnnounceButTickerAndSameReportFinalStaySilent() {
        val state = AlertAnnouncer.EventState()
        state.acceptReport(1, false)
        assertTrue(state.acceptReport(2, true)!!.newFinal)
        repeat(10) { assertNull(state.acceptReport(2, true)) }
        val corrected = AlertAnnouncer.EventState()
        corrected.acceptReport(1, false)
        assertNull(corrected.acceptReport(1, true))
        assertTrue(corrected.final)
        repeat(10) { assertNull(corrected.acceptReport(1, true)) }
    }

    @Test fun eventMarkersAreIndependent() {
        val a = AlertAnnouncer.EventState()
        val b = AlertAnnouncer.EventState()
        a.acceptReport(5, true)
        a.arrived = true
        assertTrue(b.acceptReport(1, false)!!.first)
        assertFalse(b.arrived)
        assertFalse(b.final)
    }

    /**
     * 抵达播报只取决于「本事件是否播过倒计时」，不取决于当前 warningLevel：
     * 晚到报次把等级降级后，已经开始的倒计时必须在到时收尾。
     */
    @Test fun arrivalPairsWithCountdownEvenAfterLevelDowngrade() {
        val state = AlertAnnouncer.EventState()
        state.acceptReport(1, false)
        state.countdowns.add(10)
        assertTrue("countdown happened before arrival", state.countdowns.contains(10))
        state.arrived = true
        assertTrue("arrival still announced after level downgrade", state.arrived)
    }

    @Test fun arrivalIsIndependentPerEvent() {
        val a = AlertAnnouncer.EventState()
        val b = AlertAnnouncer.EventState()
        a.countdowns.add(3)
        b.countdowns.add(3)
        a.arrived = true
        assertTrue(a.arrived)
        assertFalse("event B arrival is independent of A", b.arrived)
    }
}
