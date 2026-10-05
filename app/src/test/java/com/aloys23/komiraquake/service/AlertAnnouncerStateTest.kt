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
}
