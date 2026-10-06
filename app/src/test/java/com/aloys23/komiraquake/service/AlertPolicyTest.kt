package com.aloys23.komiraquake.service

import com.aloys23.komiraquake.core.IntensityCalculator
import com.aloys23.komiraquake.core.IntensityStandard
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
    private val settings = Settings(enableWarnings = true)

    @Test fun masterSwitchOffSuppressesEverything() {
        val off = settings.copy(enableWarnings = false)
        assertEquals(
            AlertPolicy.Decision(false, false, false, false, false),
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
            assertFalse(decision.vibration)
            assertFalse(decision.dnd)
        }
    }

    @Test fun terminalAndBelowLocalThresholdSuppressAllEffects() {
        for (decision in listOf(
            AlertPolicy.evaluate(event.copy(isCanceled = true), settings),
            AlertPolicy.evaluate(event, settings, stopped = true),
            AlertPolicy.evaluate(event.copy(rawIntensity = 1.0), settings.copy(localIntensityFilter = 3.0)),
        )) assertEquals(AlertPolicy.Decision(false, false, false, false, false), decision)
    }

    @Test fun localIntensityFilterGatesAlertsWhenSet() {
        val filtered = settings.copy(localIntensityFilter = 5.0)
        // 震级达标但本地烈度低于过滤阈值 → 不提醒。
        assertFalse(AlertPolicy.evaluate(event.copy(rawIntensity = 2.0), filtered).eligible)
        assertTrue(AlertPolicy.evaluate(event.copy(rawIntensity = 6.0), filtered).eligible)
        // 无定位无法判定本地烈度：放行。
        assertTrue(AlertPolicy.evaluate(event.copy(distanceKm = -1.0, rawIntensity = 0.0), filtered).eligible)
    }

    @Test fun localIntensityFilterComparesTheDisplayedLevelNotTheRawValue() {
        val filtered = settings.copy(localIntensityFilter = 3.0)
        // raw 2.6 显示为Ⅲ度，阈值 3.0 应视为「已达到」——否则 HUD 显示Ⅲ度却收不到提醒。
        assertEquals("III", IntensityCalculator.formatCsis(2.6))
        assertTrue(AlertPolicy.evaluate(event.copy(rawIntensity = 2.6), filtered).eligible)
        // raw 2.4 显示为Ⅱ度，仍未达到 3.0 → 拦截。
        assertEquals("II", IntensityCalculator.formatCsis(2.4))
        assertFalse(AlertPolicy.evaluate(event.copy(rawIntensity = 2.4), filtered).eligible)
    }

    @Test fun localIntensityFilterFollowsTheSelectedStandard() {
        // M5.0 / 30 km 这个事件两制式显示级数差很多：CSIS 5级 vs JMA 1 级
        // （实测：JMA s=1.0986→1 级；CSIS raw=4.6268→5 级）。
        // 若过滤不跟随所选标准，拿 CSIS 阈值去比 JMA 震度就会误判。
        val strong = event.copy(magnitude = 5.0, distanceKm = 30.0, depth = 10.0, rawIntensity = 4.6268)
        val at5 = settings.copy(localIntensityFilter = 5.0)
        assertEquals(5.0, IntensityCalculator.displayedLevel(5.0, 4.6268, 30.0, 10.0, IntensityStandard.CSIS), 0.0)
        assertEquals(1.0, IntensityCalculator.displayedLevel(5.0, 4.6268, 30.0, 10.0, IntensityStandard.JMA), 0.0)
        assertTrue(AlertPolicy.evaluate(strong, at5.copy(intensityStandard = IntensityStandard.CSIS)).eligible)
        assertFalse(AlertPolicy.evaluate(strong, at5.copy(intensityStandard = IntensityStandard.JMA)).eligible)
        // 「及以上」语义在JMA 下同样成立：阈值 1.0 时 1 级放行。
        val at1 = settings.copy(localIntensityFilter = 1.0)
        assertTrue(AlertPolicy.evaluate(strong, at1.copy(intensityStandard = IntensityStandard.JMA)).eligible)
        // JMA 的 5弱/5强 同为 5 级，过滤不区分强弱（实测 M6.0/10km→5弱、M6.0/5km→5强）。
        val at5Jma = settings.copy(localIntensityFilter = 5.0, intensityStandard = IntensityStandard.JMA)
        for ((m, d, text) in listOf(Triple(6.0, 10.0, "5弱"), Triple(6.0, 5.0, "5强"))) {
            val e = event.copy(magnitude = m, distanceKm = d, depth = 10.0, rawIntensity = 5.7)
            assertEquals(text, IntensityCalculator.formatJma(m, d, 10.0))
            assertEquals(5.0, IntensityCalculator.displayedLevel(m, 5.7, d, 10.0, IntensityStandard.JMA), 0.0)
            assertTrue("$text 应按 5 级放行", AlertPolicy.evaluate(e, at5Jma).eligible)
        }
    }

    @Test fun zeroVolumeOrDisabledAudioDoesNotTakeDndOwnership() {
        for (s in listOf(settings.copy(alertVolume = 0.0), settings.copy(enableSoundAlert = false))) {
            val decision = AlertPolicy.evaluate(event, s)
            assertFalse(decision.audio)
            assertFalse(decision.dnd)
            assertTrue(decision.vibration)
        }
    }
}
