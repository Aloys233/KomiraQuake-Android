package com.aloys23.komiraquake.service

import com.aloys23.komiraquake.data.prefs.Settings
import com.aloys23.komiraquake.model.EarthquakeEvent
import com.aloys23.komiraquake.model.WarningLevel
import org.junit.Assert.*
import org.junit.Test

class AlertPolicyTest {
    private val event = EarthquakeEvent(
        id = "a", magnitude = 5.0, latitude = 0.0, longitude = 0.0, depth = 10.0,
        location = "Test", timestamp = 0L, source = "test", distanceKm = 10.0,
        estimatedIntensity = "4", rawIntensity = 4.0, warningLevel = WarningLevel.WARNING,
    )
    private val settings = Settings(enableSpeech = true, enableWarnings = true)

    @Test fun masterSwitchOffSuppressesEverything() {
        val off = settings.copy(enableWarnings = false)
        assertEquals(
            AlertPolicy.Decision(false, false, false, false, false, false),
            AlertPolicy.evaluate(event, off),
        )
    }

    @Test fun muteSuppressesOutputsNotVisuals() {
        for (decision in listOf(
            AlertPolicy.evaluate(event, settings.copy(isMuted = true)),
            AlertPolicy.evaluate(event, settings, eventMuted = true),
        )) {
            assertTrue(decision.fullScreen)
            assertFalse(decision.audio)
            assertFalse(decision.speech)
            assertFalse(decision.vibration)
            assertFalse(decision.dnd)
        }
    }

    @Test fun terminalAndBelowLocalThresholdSuppressAllEffects() {
        for (decision in listOf(
            AlertPolicy.evaluate(event.copy(isCanceled = true), settings),
            AlertPolicy.evaluate(event, settings, stopped = true),
            AlertPolicy.evaluate(event.copy(rawIntensity = 1.0), settings.copy(localIntensityFilter = 3.0)),
        )) assertEquals(AlertPolicy.Decision(false, false, false, false, false, false), decision)
    }

    @Test fun localIntensityFilterGatesAlertsWhenSet() {
        val filtered = settings.copy(localIntensityFilter = 5.0)
        // 震级达标但本地烈度低于过滤阈值 → 不提醒。
        assertFalse(AlertPolicy.evaluate(event.copy(rawIntensity = 2.0), filtered).eligible)
        assertTrue(AlertPolicy.evaluate(event.copy(rawIntensity = 6.0), filtered).eligible)
        // 无定位无法判定本地烈度：放行。
        assertTrue(AlertPolicy.evaluate(event.copy(distanceKm = -1.0, rawIntensity = 0.0), filtered).eligible)
    }

    @Test fun zeroVolumeOrDisabledAudioDoesNotTakeDndOwnership() {
        for (s in listOf(settings.copy(alertVolume = 0.0), settings.copy(enableSpeech = false, enableSoundAlert = false))) {
            val decision = AlertPolicy.evaluate(event, s)
            assertFalse(decision.audio)
            assertFalse(decision.speech)
            assertFalse(decision.dnd)
            assertTrue(decision.vibration)
        }
    }
}
