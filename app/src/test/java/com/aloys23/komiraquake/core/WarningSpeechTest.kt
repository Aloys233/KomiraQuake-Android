package com.aloys23.komiraquake.core

import com.aloys23.komiraquake.model.EarthquakeEvent
import com.aloys23.komiraquake.model.WarningLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预警播报文案。TalkBack 用户此前完全拿不到预警信息，这段文案的措辞与档位
 * 直接决定他们能否在地震时听懂发生了什么。
 */
class WarningSpeechTest {

    private fun event(
        warningLevel: WarningLevel = WarningLevel.WARNING,
        location: String = "四川省阿坝州汶川县",
        distanceKm: Double = 180.0,
        estimatedIntensity: String = "5",
        maxIntensityText: String = "",
        sourceAgency: String = "CENC",
    ) = EarthquakeEvent(
        id = "id-1", magnitude = 6.8, latitude = 30.0, longitude = 103.0, depth = 12.0,
        location = location, timestamp = 0L, source = "预警", sourceProvider = "Wolfx",
        sourceAgency = sourceAgency, distanceKm = distanceKm, estimatedIntensity = estimatedIntensity,
        maxIntensityText = maxIntensityText, warningLevel = warningLevel,
    )

    @Test fun summaryCarriesLevelMagnitudeDistanceAndAction() {
        val text = WarningSpeech.summary(event(), IntensityStandard.CSIS)!!
        assertTrue(text.startsWith("地震预警，四川省阿坝州汶川县"))
        assertTrue("应含震级", text.contains("震级 6.8 级"))
        assertTrue("应含深度", text.contains("深度 12 公里"))
        assertTrue("应含距离", text.contains("距你 180 公里"))
        assertTrue("应含烈度", text.contains("本地预估 烈度 5"))
        assertTrue("应给出行动指引", text.endsWith("请立即伏地、遮挡、抓牢。"))
    }

    @Test fun criticalLevelIsAnnouncedDistinctly() {
        val text = WarningSpeech.summary(event(warningLevel = WarningLevel.CRITICAL), IntensityStandard.CSIS)!!
        assertTrue(text.startsWith("严重地震预警"))
    }

    /** JMA 是震度量纲，不该按国标烈度念。 */
    @Test fun jmaStandardUsesShindoWording() {
        val text = WarningSpeech.summary(
            event(estimatedIntensity = "5弱", sourceAgency = "JMA"), IntensityStandard.JMA)!!
        assertTrue(text.contains("日本震度 5弱"))
        assertTrue("不应出现国标措辞", !text.contains("预估 烈度"))
    }

    /** 无本地预估时退回来源最大烈度，避免播报里出现「预估 --」。 */
    @Test fun fallsBackToMaxIntensityWhenLocalEstimateMissing() {
        val text = WarningSpeech.summary(
            event(distanceKm = -1.0, estimatedIntensity = "--", maxIntensityText = "Ⅷ"), IntensityStandard.CSIS)!!
        assertTrue(text.contains("来源最大 烈度 Ⅷ"))
        assertTrue("距离未知时不应念距离", !text.contains("距你"))
    }

    @Test fun summaryIsNullWithoutEvent() {
        assertNull(WarningSpeech.summary(null, IntensityStandard.CSIS))
    }

    /** 逐秒播报会淹没 TalkBack 队列，只保留关键档位。 */
    @Test fun countdownSpeaksOnlyAtMilestones() {
        assertEquals("距离地震波抵达还有 60 秒。", WarningSpeech.countdown(60))
        assertEquals("距离地震波抵达还有 10 秒。", WarningSpeech.countdown(10))
        assertNull("非档位秒数不应播报", WarningSpeech.countdown(37))
        assertNull("非档位秒数不应播报", WarningSpeech.countdown(1))
    }

    @Test fun countdownAndArrivalHaveOwnWording() {
        assertEquals("地震波预计已抵达你所在区域，请保持避险姿势。", WarningSpeech.countdown(0))
        assertEquals("地震波预计到达时间未知，请立即避险。", WarningSpeech.countdown(-1))
    }
}
