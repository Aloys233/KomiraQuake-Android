package com.aloys23.komiraquake.data.source

import com.aloys23.komiraquake.core.IntensityStandard
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wolfx jma_eqlist（JMA 地震情报）目录解析。 */
class EewParserDirectoryTest {

    private fun jma(vararg pairs: Pair<String, Any>): JSONObject = JSONObject().apply {
        pairs.forEach { (k, v) -> put(k, v) }
    }

    @Test
    fun mapsFieldsAndNamespacesEventId() {
        val event = EewParser.parseJmaDirectory(
            jma(
                "EventID" to "20261006134727",
                "time_full" to "2026/10/06 13:47:00",
                "location" to "熊本県熊本地方",
                "magnitude" to "2.9",
                "shindo" to "1",
                "depth" to "10km",
                "latitude" to "32.6",
                "longitude" to "130.7",
            ),
            null,
            IntensityStandard.CSIS,
        )
        assertNotNull(event)
        event!!
        assertEquals("JMA 地震情报", event.source)
        assertEquals("熊本県熊本地方", event.location)
        assertEquals(2.9, event.magnitude, 1e-6)
        assertEquals(10.0, event.depth, 1e-6)
        assertEquals("1", event.maxIntensityText)
        assertEquals(1.0, event.maxIntensityRaw, 1e-6)
        assertEquals("jma_eqlist:20261006134727", event.eventId)
        assertTrue(event.id.startsWith("wolfx_jmaeqlist_"))
        assertTrue(event.timestamp > 0)
        assertTrue(event.isFinal)
        // 目录永不产生 warning/critical
        assertTrue(!event.warningLevel.isAlert)
    }

    @Test
    fun stripsDepthUnitAndMapsCompositeShindo() {
        val event = EewParser.parseJmaDirectory(
            jma(
                "EventID" to "20261005223109",
                "time_full" to "2026/10/05 22:30:00",
                "location" to "石垣島北西沖",
                "magnitude" to "4.6",
                "shindo" to "5-",
                "depth" to "150km",
                "latitude" to "25.1",
                "longitude" to "123.4",
            ),
            null,
            IntensityStandard.CSIS,
        )
        assertNotNull(event)
        event!!
        assertEquals(150.0, event.depth, 1e-6)
        assertEquals("5-", event.maxIntensityText)
        assertEquals(5.0, event.maxIntensityRaw, 1e-6)
    }

    @Test
    fun acceptsSecondsLessTimeAndSynthesizesIdWhenNoEventId() {
        val event = EewParser.parseJmaDirectory(
            jma(
                "time" to "2026/10/06 13:47",
                "location" to "熊本県熊本地方",
                "magnitude" to "2.9",
                "shindo" to "1",
                "depth" to "10km",
                "latitude" to "32.6",
                "longitude" to "130.7",
            ),
            null,
            IntensityStandard.CSIS,
        )
        assertNotNull(event)
        event!!
        assertTrue(event.id.startsWith("wolfx_jmaeqlist_"))
        assertTrue(event.eventId.startsWith("jma_eqlist:"))
        assertTrue(event.timestamp > 0)
    }

    @Test
    fun dropsEntriesWithoutTimeOrCoordinates() {
        assertNull(
            EewParser.parseJmaDirectory(
                jma("latitude" to "32.6", "longitude" to "130.7"), null, IntensityStandard.CSIS,
            ),
        )
        assertNull(
            EewParser.parseJmaDirectory(
                jma("time_full" to "2026/10/06 13:47:00", "longitude" to "130.7"), null, IntensityStandard.CSIS,
            ),
        )
    }

    // Wolfx 的 JMA 报文时刻是无时区 JST(UTC+9) 墙钟；必须按 JST 解析，才能与 Pancakes 的
    // 同一地震（带偏移 ISO）对齐，两路 jma_eqlist 由此合并为一条，互为备份而不重复。
    @Test
    fun parsesWallClockAsJstSoSourcesAgree() {
        val expected = java.time.OffsetDateTime.parse("2026-10-06T04:47:00Z").toInstant().toEpochMilli()
        val wolfx = EewParser.parseJmaDirectory(
            jma(
                "EventID" to "20261006134727",
                "time_full" to "2026/10/06 13:47:00",
                "location" to "熊本県熊本地方",
                "magnitude" to "2.9", "shindo" to "1", "depth" to "10km",
                "latitude" to "32.6", "longitude" to "130.7",
            ),
            null,
            IntensityStandard.CSIS,
        )!!
        assertEquals(expected, wolfx.timestamp)

        // 只有分钟精度（time，无 time_full）也必须按 JST 解析成功。
        val minute = EewParser.parseJmaDirectory(
            jma(
                "time" to "2026/10/06 13:47", "location" to "熊本県熊本地方", "magnitude" to "2.9",
                "depth" to "10km", "latitude" to "32.6", "longitude" to "130.7",
            ),
            null,
            IntensityStandard.CSIS,
        )!!
        assertEquals(expected, minute.timestamp)

        // Wolfx jma_eew 的 OriginTime 同样是 JST 墙钟。
        val eew = EewParser.parse(
            JSONObject(
                """{"type":"jma_eew","EventID":"20261005223109","OriginTime":"2026/10/05 22:30:47",""" +
                    """"Hypocenter":"石垣島北西沖","Latitude":25.1,"Longitude":123.3,""" +
                    """"Magnitude":4.6,"Depth":140,"MaxIntensity":"2","isCancel":false}""",
            ),
            null,
            IntensityStandard.JMA,
            "JMA 紧急地震速报",
            "wolfx_",
            0L,
            originTimeIsJst = true,
        )!!
        assertEquals(
            java.time.OffsetDateTime.parse("2026-10-05T13:30:47Z").toInstant().toEpochMilli(),
            eew.timestamp,
        )
    }
}
