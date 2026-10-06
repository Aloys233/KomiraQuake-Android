package com.aloys23.komiraquake.data.source.pancakes

import com.aloys23.komiraquake.core.IntensityStandard
import com.aloys23.komiraquake.data.source.SourceEventKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PancakesParserTest {

    private fun envelope(source: String, action: String, payload: String): JSONObject = JSONObject(
        """{"source":"$source","type":"earthquake","action":"$action","timestampMs":1700000000000,"payload":$payload}""",
    )

    @Test fun usgsUpdateMapsToLiveEvent() {
        val parsed = PancakesParser.parseRealtime(
            envelope(
                "usgs", "update",
                """{"eventId":"us7000abcd","placeName":"10 km SE of Nowhere","latitude":-12.5,"longitude":166.25,"depth":35.0,"magnitude":5.8,"magnitudeType":"mww","originTimeMs":1700000000000,"updatedTimeMs":1700000500000,"infoType":"Reviewed"}""",
            ),
            user = null, standard = IntensityStandard.CSIS, now = 1700000000000,
        )!!
        assertEquals(SourceEventKind.LIVE, parsed.kind)
        assertEquals("Pancakes", parsed.event.sourceProvider)
        assertEquals("USGS", parsed.event.sourceAgency)
        assertEquals("usgs:us7000abcd", parsed.event.eventId)
        assertEquals(5.8, parsed.event.magnitude, 1e-9)
        assertEquals("10 km SE of Nowhere", parsed.event.location)
        assertTrue(parsed.event.isFinal)
        assertEquals(1700000500000L, parsed.event.sourceUpdatedAt)
    }

    @Test fun gqArchivedIsFinal() {
        val parsed = PancakesParser.parseRealtime(
            envelope(
                "gq", "archived",
                """{"id":"a1b2","latitude":35.0,"longitude":140.0,"depth":10.0,"magnitude":6.5,"originTimeMs":1700000000000,"region":"日本关东地区","revisionId":3,"lastUpdateMs":1700000005000,"intensity":"VIII"}""",
            ),
            user = null, standard = IntensityStandard.CSIS, now = 1700000000000,
        )!!
        assertEquals(SourceEventKind.LIVE, parsed.kind)
        assertEquals("GQ", parsed.event.sourceAgency)
        assertEquals("gq:a1b2", parsed.event.eventId)
        assertTrue(parsed.event.isFinal)
        assertEquals(4, parsed.event.reportNum)
        assertEquals("VIII", parsed.event.maxIntensityText)
    }

    @Test fun gqCancelledProducesTerminalEvent() {
        val parsed = PancakesParser.parseRealtime(
            envelope("gq", "cancelled", """{"id":"a1b2"}"""),
            user = null, standard = IntensityStandard.CSIS, now = 1700000000000,
        )!!
        assertTrue(parsed.event.isCanceled)
        assertEquals(PancakesProtocol.CANCEL_REPORT_NUM, parsed.event.reportNum)
        assertEquals("gq:a1b2", parsed.event.eventId)
    }

    @Test fun jmaEewReusesJmaFields() {
        val parsed = PancakesParser.parseRealtime(
            envelope(
                "jma_eew", "update",
                """{"EventID":"20231114221320","Serial":4,"AnnouncedTime":"2023-11-14T22:13:30+09:00","OriginTime":"2023-11-14T22:13:20+09:00","Hypocenter":"東京湾","Latitude":35.5,"Longitude":139.8,"Magunitude":6.1,"Depth":20.0,"MaxIntensity":"5+","isFinal":false,"isCancel":false}""",
            ),
            user = null, standard = IntensityStandard.JMA, now = 1700000000000,
        )!!
        assertEquals(SourceEventKind.LIVE, parsed.kind)
        assertEquals("JMA", parsed.event.sourceAgency)
        assertEquals("jma_eew:20231114221320", parsed.event.eventId)
        assertEquals(4, parsed.event.reportNum)
        assertEquals(6.1, parsed.event.magnitude, 1e-9)
        assertEquals("東京湾", parsed.event.location)
        assertEquals(5.5, parsed.event.maxIntensityRaw, 1e-9)
        // 带 +09:00 偏移的 ISO 时间应正确解析为 UTC epoch。
        assertEquals(1700000000000L - 9 * 3600_000L + 0, parsed.event.timestamp)
    }

    @Test fun jmaEqlistMapsToDirectory() {
        val parsed = PancakesParser.parseRealtime(
            envelope(
                "jma_eqlist", "update",
                """{"eventId":"20231114221320","originTime":"2023-11-14T22:13:20+09:00","placeName":"東京湾","latitude":35.5,"longitude":139.8,"depth":20.0,"magnitude":4.5,"maxIntensity":"3","serial":1,"reportTime":"2023-11-14T22:21:00+09:00"}""",
            ),
            user = null, standard = IntensityStandard.CSIS, now = 1700000000000,
        )!!
        assertEquals(SourceEventKind.DIRECTORY, parsed.kind)
        assertEquals("jma_eqlist:20231114221320", parsed.event.eventId)
        assertTrue(parsed.event.isFinal)
        assertFalse(parsed.event.isCanceled)
    }

    @Test fun nonQuakeSourceIsIgnored() {
        assertNull(
            PancakesParser.parseRealtime(
                envelope("cma", "update", """{"id":"x","latitude":1.0,"longitude":2.0}"""),
                user = null, standard = IntensityStandard.CSIS, now = 1700000000000,
            ),
        )
    }

    @Test fun httpListItemMapsToDirectoryEvent() {
        val item = JSONObject(
            """{"source":"usgs","eventId":"us7000abcd","status":"active","revision":1791199263393,"originTime":"2026-10-05T11:19:29.04Z","magnitude":4.9,"magnitudeType":"mww","depthKm":10.0,"place":"Somewhere","latitude":38.8,"longitude":-122.8}""",
        )
        val event = PancakesParser.parseListItem(item, user = null, standard = IntensityStandard.CSIS, now = 0L)!!
        assertEquals("usgs:us7000abcd", event.eventId)
        assertEquals("Pancakes", event.sourceProvider)
        assertEquals(
            java.time.Instant.parse("2026-10-05T11:19:29.04Z").toEpochMilli(),
            event.timestamp,
        )
        assertEquals(1791199263393L, event.sourceUpdatedAt)
    }
}
