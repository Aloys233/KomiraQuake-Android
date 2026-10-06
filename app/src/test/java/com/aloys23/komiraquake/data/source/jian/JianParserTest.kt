package com.aloys23.komiraquake.data.source.jian

import com.aloys23.komiraquake.core.IntensityStandard
import com.aloys23.komiraquake.data.source.SourceEventKind
import com.aloys23.komiraquake.model.WarningLevel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Jian（api.sismotide.top）：频道映射 + 字段解析 + 跨源合并键。 */
class JianParserTest {

    private fun channel(type: String) = requireNotNull(JianProtocol.channelFor(type)) { type }

    private fun record(type: String, json: String) = JianParser.parseRecord(
        channel(type), JSONObject(json), null, IntensityStandard.CSIS,
    )

    @Test fun unhandledChannelsAreIgnored() {
        assertNull(JianProtocol.channelFor("weather"))
        assertNull(JianProtocol.channelFor("jma-tsunami"))
        assertEquals(SourceEventKind.LIVE, channel("cea").kind)
        assertEquals(SourceEventKind.DIRECTORY, channel("cenc").kind)
    }

    @Test fun ceaEewMapsToLiveWithChannelNamespacedId() {
        val cea = record(
            "cea",
            """{"id":"202608201100.0001","number":2,"originTime":1787194858000,"latitude":35.789,
               "longitude":115.7,"depth":16,"magnitude":4.1,"placeName":"山东菏泽市郓城县"}""",
        )
        assertNotNull(cea)
        cea!!
        assertEquals(JianProtocol.PROVIDER, cea.sourceProvider)
        assertEquals("CEA", cea.sourceAgency)
        assertEquals("cenc_eew:202608201100.0001", cea.eventId)
        assertEquals(2, cea.reportNum)
        assertEquals(1787194858000L, cea.timestamp)
        assertFalse(cea.isFinal)
    }

    @Test fun cencCatalogStripsSuffixAndNeverAlerts() {
        val cenc = record(
            "cenc",
            """{"id":"CD.20260819132221.000_M","originTime":1787116941000,"latitude":37.84,
               "longitude":95.62,"depth":10.0,"magnitude":3.7,"placeName":"青海海西州直辖区",
               "infoTypeName":"[正式测定]"}""",
        )
        assertNotNull(cenc)
        cenc!!
        // 去掉 _M/_A 后与 Wolfx cenc_eqlist 的 EventID 对齐。
        assertEquals("CD.20260819132221.000", cenc.eventId)
        assertEquals("CENC", cenc.sourceAgency)
        assertTrue(cenc.isFinal)
        assertFalse(cenc.warningLevel == WarningLevel.CRITICAL || cenc.warningLevel == WarningLevel.WARNING)
    }

    @Test fun jmaEewIsoTimeIsParsedWithOffset() {
        val jma = JianParser.parseRecord(
            channel("jma-eew"),
            JSONObject(
                """{"id":"20260820083831","originTime":"2026-08-20T08:38:21+09:00","latitude":36.5,
                   "longitude":140.6,"depth":70.0,"magnitude":3.5,"placeName":"茨城県北部",
                   "infoTypeName":"予報","intensity":"2","serial":3,"isFinal":true,"isCancel":false}""",
            ),
            null, IntensityStandard.JMA,
        )
        assertNotNull(jma)
        jma!!
        assertEquals("2026-08-20T08:38:21+09:00".let {
            java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli()
        }, jma.timestamp)
        assertEquals("jma_eew:20260820083831", jma.eventId)
        assertEquals("JMA", jma.sourceAgency)
        assertEquals("2", jma.maxIntensityText)
        assertEquals(3, jma.reportNum)
        assertTrue(jma.isFinal)
    }

    @Test fun jmaCatalogCancelIsRecognised() {
        val cancel = record(
            "jma",
            """{"id":"20260820094551","originTime":"2026-08-20T09:45:00+09:00","latitude":32.6,
               "longitude":130.7,"depth":10.0,"magnitude":2.3,"placeName":"熊本県熊本地方",
               "infoType":"取消"}""",
        )
        assertNotNull(cancel)
        cancel!!
        assertTrue(cancel.isCanceled)
        assertEquals("jma_eqlist:20260820094551", cancel.eventId)
    }

    @Test fun recordsMissingRequiredFieldsAreDropped() {
        assertNull(record("cenc", """{"id":"x","originTime":1787116941000}"""))
        assertNull(record("cenc", """{"originTime":1787116941000,"latitude":37.8,"longitude":95.6}"""))
    }

    @Test fun authTokenParsing() {
        assertEquals("rt_x", JianParser.parseAuthToken("""{"ok":true,"token":"rt_x"}"""))
        assertNull(JianParser.parseAuthToken("""{"ok":false,"code":4101}"""))
        assertNull(JianParser.parseAuthToken("not json"))
    }
}
