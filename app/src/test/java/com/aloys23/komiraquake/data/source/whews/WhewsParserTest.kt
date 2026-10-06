package com.aloys23.komiraquake.data.source.whews

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
import java.time.Instant
import java.time.ZoneOffset

/** Whews（api.2v8.cn）：频道映射 + 时区 + 字段解析 + 跨源合并键。 */
class WhewsParserTest {

    private fun channel(source: String) = requireNotNull(WhewsProtocol.channelFor(source)) { source }

    private fun record(source: String, json: String) = WhewsParser.parseRecord(
        channel(source), JSONObject(json), null, IntensityStandard.CSIS,
    )

    /** 2026-08-13 08:47:00 +08:00 → epoch ms。 */
    private val cencShockMs = Instant.parse("2026-08-13T00:47:00Z").toEpochMilli()
    /** 2024-01-01 16:10:08 +09:00 → epoch ms。 */
    private val jmaShockMs = Instant.parse("2024-01-01T07:10:08Z").toEpochMilli()

    @Test fun unhandledChannelsAreIgnored() {
        // 文档明示这些端点不进入 /ws/all，不应被当作已接入频道。
        assertNull(WhewsProtocol.channelFor("cenc_int"))
        assertNull(WhewsProtocol.channelFor("cmt_usgs"))
        assertNull(WhewsProtocol.channelFor("nied"))
        assertNull(WhewsProtocol.channelFor("weatheralarm"))
        assertEquals(SourceEventKind.LIVE, channel("jma_eew").kind)
        assertEquals(SourceEventKind.DIRECTORY, channel("cenc").kind)
    }

    /** 时刻是无时区墙钟：非 JMA 频道按 UTC+8、JMA 频道按 UTC+9 解释。 */
    @Test fun wallClockIsParsedPerChannelTimeZone() {
        val cenc = record(
            "cenc",
            """{"id":"CD.20260813084717.000","shockTime":"2026-08-13 08:47:00",
               "latitude":36.06,"longitude":103.55,"depth":11.0,"magnitude":3.2,
               "placeName":"甘肃临夏州永靖县","infoTypeName":"正式测定"}""",
        )
        assertNotNull(cenc)
        assertEquals(cencShockMs, cenc!!.timestamp)

        val jma = record(
            "jma_eew",
            """{"id":"20240101161010","updates":10,"shockTime":"2024-01-01 16:10:08",
               "latitude":37.5,"longitude":137.3,"depth":10.0,"magnitude":6.2,
               "placeName":"石川県能登地方","epiIntensity":"6+"}""",
        )
        assertNotNull(jma)
        assertEquals(jmaShockMs, jma!!.timestamp)
        // JMA 墙钟若被误按 UTC+8 解释会差 1 小时，这里断言确实按 UTC+9。
        assertEquals(Instant.parse("2024-01-01T07:10:08Z").atOffset(ZoneOffset.UTC).toEpochSecond(),
            jma.timestamp / 1000)
    }

    /** createTime / updateTime 同样按频道时区解析。 */
    @Test fun reportTimeUsesChannelTimeZone() {
        val cenc = record(
            "cenc",
            """{"id":"CD.1","shockTime":"2026-08-13 08:47:00","createTime":"2026-08-13 08:51:51",
               "latitude":36.06,"longitude":103.55,"magnitude":3.2}""",
        )
        assertEquals(Instant.parse("2026-08-13T00:51:51Z").toEpochMilli(), cenc!!.sourceUpdatedAt)

        val jma = record(
            "jma",
            """{"id":"20240101161010","shockTime":"2024-01-01 16:10:08",
               "createTime":"2024-01-01 16:12:00","latitude":37.5,"longitude":137.3,"magnitude":6.2}""",
        )
        assertEquals(Instant.parse("2024-01-01T07:12:00Z").toEpochMilli(), jma!!.sourceUpdatedAt)
    }

    @Test fun updatesMapsToReportNumAndCancelFinalAreHonoured() {
        val ongoing = record(
            "jma_eew",
            """{"id":"A","updates":3,"shockTime":"2024-01-01 16:10:08","latitude":37.5,
               "longitude":137.3,"magnitude":6.2,"cancel":false,"final":false}""",
        )
        assertEquals(3, ongoing!!.reportNum)
        assertFalse(ongoing.isFinal)
        assertFalse(ongoing.isCanceled)

        val done = record(
            "jma_eew",
            """{"id":"B","updates":9,"shockTime":"2024-01-01 16:10:08","latitude":37.5,
               "longitude":137.3,"magnitude":6.2,"cancel":true,"final":true}""",
        )
        assertTrue(done!!.isFinal)
        assertTrue(done.isCanceled)
    }

    /** cenc 用上游 id 原文（无频道前缀），以便与 Wolfx cenc_eqlist 对齐合并。 */
    @Test fun cencUsesUpstreamIdVerbatimForCrossSourceMerge() {
        val cenc = record(
            "cenc",
            """{"id":"CD.20260813084717.000","shockTime":"2026-08-13 08:47:00",
               "latitude":36.06,"longitude":103.55,"magnitude":3.2}""",
        )
        assertEquals("CD.20260813084717.000", cenc!!.eventId)
        assertEquals("CENC|CD.20260813084717.000", cenc.identity)

        // 带 _M / _A 后缀时去掉，与 Wolfx 的 EventID 对齐。
        val finalReport = record(
            "cenc",
            """{"id":"CD.20260813084717.000_M","shockTime":"2026-08-13 08:47:00",
               "latitude":36.06,"longitude":103.55,"magnitude":3.2}""",
        )
        assertEquals("CD.20260813084717.000", finalReport!!.eventId)
    }

    /** EEW 频道带 eventNs 前缀，与 Wolfx / Pancakes 同名频道落进同一合并键。 */
    @Test fun eewChannelsUseChannelNamespacedEventId() {
        val cea = record(
            "cea",
            """{"id":"bi9wyea65mayd","updates":3,"shockTime":"2026-08-13 08:47:00",
               "latitude":29.43,"longitude":101.09,"depth":8,"magnitude":4.0,
               "placeName":"四川甘孜州雅江县","epiIntensity":5.5}""",
        )
        assertEquals("cenc_eew:bi9wyea65mayd", cea!!.eventId)
        assertEquals("CEA|cenc_eew:bi9wyea65mayd", cea.identity)
        assertEquals(WhewsProtocol.PROVIDER, cea.sourceProvider)
        assertEquals("CEA", cea.sourceAgency)
    }

    /** JMA 震度文本（"6+"）须映射为原始数值；CEA 的数值型 epiIntensity 直接取用。 */
    @Test fun intensityAcceptsBothJmaTextAndNumeric() {
        val jma = record(
            "jma_eew",
            """{"id":"A","shockTime":"2024-01-01 16:10:08","latitude":37.5,"longitude":137.3,
               "magnitude":6.2,"epiIntensity":"6+"}""",
        )
        assertEquals(6.5, jma!!.maxIntensityRaw, 1e-9)

        val cea = record(
            "cea",
            """{"id":"B","shockTime":"2026-08-13 08:47:00","latitude":29.43,"longitude":101.09,
               "magnitude":4.0,"epiIntensity":5.5}""",
        )
        assertEquals(5.5, cea!!.maxIntensityRaw, 1e-9)
    }

    /** 情报为字符串型 maxIntensity（USGS MVI）时也应解析。 */
    @Test fun stringMaxIntensityIsParsed() {
        val usgs = record(
            "usgs",
            """{"id":"nc75443921","shockTime":"2026-07-23 10:16:14",
               "updateTime":"2026-07-23 10:22:35","latitude":33.704,"longitude":-116.771,
               "depth":16.3,"magnitude":2.6,"placeName":"5km NE of Anza, CA","maxIntensity":"3.2"}""",
        )
        assertEquals(3.2, usgs!!.maxIntensityRaw, 1e-9)
    }

    /** 目录事件永不产生 warning/critical。 */
    @Test fun directoryEntriesAreCappedAtWatch() {
        val usgs = record(
            "usgs",
            """{"id":"nc1","shockTime":"2026-07-23 10:16:14","latitude":33.704,
               "longitude":-116.771,"depth":16.3,"magnitude":6.5}""",
        )
        assertEquals(WarningLevel.WATCH, usgs!!.warningLevel)
        assertTrue(usgs.isFinal)
    }

    @Test fun missingRequiredFieldsAreRejected() {
        assertNull(record("cenc", """{"shockTime":"2026-08-13 08:47:00","latitude":36.0,"longitude":103.5}"""))
        assertNull(record("cenc", """{"id":"CD.1","latitude":36.0,"longitude":103.5}"""))
        assertNull(record("cenc", """{"id":"CD.1","shockTime":"2026-08-13 08:47:00","longitude":103.5}"""))
        assertNull(record("cenc", """{"id":"CD.1","shockTime":"2026-08-13 08:47:00","latitude":36.0}"""))
    }
}