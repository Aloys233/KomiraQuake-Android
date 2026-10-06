package com.aloys23.komiraquake.data.source.whews

import com.aloys23.komiraquake.data.source.SourceEventKind

/**
 * Whews 数据源（备用站国内 api.2v8.cn / 主站 api.beecld.com）的端点与频道映射。
 * 文档：https://api.2v8.cn/docs
 *
 * 单条 `/ws/all` 聚合连接覆盖下列全部频道；帧格式 `{"Data":{…},"md5":…,"source":"<短名>"}`，
 * 首连为 JSON 数组（各源最新一条），之后为单条对象。
 *
 * 时刻一律是**无时区墙钟**：JMA 系端点为 UTC+9，其余为 UTC+8，故每个频道都带 [WhewsChannel.tz]。
 * [WhewsChannel.eventNs] 为频道化 eventId 前缀，用于与 Wolfx / Pancakes / Jian 的同名频道
 * 对齐合并；留空表示直接使用上游 id 原文（cenc 与 Wolfx cenc_eqlist 对齐）。
 */
data class WhewsChannel(
    val source: String,
    val agency: String,
    val eventNs: String,
    val kind: SourceEventKind,
    val tz: WhewsTimeZone,
)

/** Whews 报文时刻的时区。 */
enum class WhewsTimeZone { UTC8, JST }

object WhewsProtocol {
    /**
     * 站点地址，**备用站（国内）在前**：优先直连国内站，不可用时才轮换到主站。
     * 索引只在连接失败时前进，连通后不回落；断网恢复时复位回首选站
     * （见 WhewsSource.urlIndex）。
     */
    val WS_HOSTS = listOf(
        "wss://api.2v8.cn",
        "wss://api.beecld.com",
    )

    /** 聚合端点路径；令牌以 `?token=wat_…` 附加在握手。 */
    const val WS_PATH = "/ws/all"

    const val PROVIDER = "Whews"
    const val HEARTBEAT_TIMEOUT_MS = 90_000L
    const val EEW_LIVE_WINDOW_MS = 30L * 60_000L

    /**
     * 服务端关闭码（握手升级成功后）。
     * 文档要求：`4401` 令牌无效、`4403` 被封禁时**停止盲目重连**（重连过频会被智能封禁）；
     * `4503` 鉴权服务暂不可用、`4008` 单令牌连接数超限则退避后重连。
     */
    object CloseCode {
        const val UNAUTHORIZED = 4401
        const val BANNED = 4403
    }

    /** 情报类端点首次连接会补发近期缓存（旧→新），之后仅在内容变化时推送。 */
    val CHANNELS: List<WhewsChannel> = listOf(
        // EEW：告警链路。前缀与 Wolfx / Pancakes 同名频道对齐，便于跨源合并。
        WhewsChannel("cea", "CEA", "cenc_eew", SourceEventKind.LIVE, WhewsTimeZone.UTC8),
        WhewsChannel("cea-pr", "CEA", "cenc_eew", SourceEventKind.LIVE, WhewsTimeZone.UTC8),
        WhewsChannel("jma_eew", "JMA", "jma_eew", SourceEventKind.LIVE, WhewsTimeZone.JST),
        WhewsChannel("cwa_eew", "CWA", "cwa_eew", SourceEventKind.LIVE, WhewsTimeZone.UTC8),
        WhewsChannel("sa_eew", "SA", "sa_eew", SourceEventKind.LIVE, WhewsTimeZone.UTC8),
        WhewsChannel("kma_eew", "KMA", "kma_eew", SourceEventKind.LIVE, WhewsTimeZone.UTC8),
        WhewsChannel("early_est", "Early-est", "early_est", SourceEventKind.LIVE, WhewsTimeZone.UTC8),
        // 速报/情报：只进列表与历史。cenc 用上游 id 原文，与 Wolfx cenc_eqlist 对齐。
        WhewsChannel("cenc", "CENC", "", SourceEventKind.DIRECTORY, WhewsTimeZone.UTC8),
        WhewsChannel("jma", "JMA", "jma_eqlist", SourceEventKind.DIRECTORY, WhewsTimeZone.JST),
        WhewsChannel("cwa", "CWA", "cwa_eqlist", SourceEventKind.DIRECTORY, WhewsTimeZone.UTC8),
        WhewsChannel("kma", "KMA", "kma_eqlist", SourceEventKind.DIRECTORY, WhewsTimeZone.UTC8),
        WhewsChannel("usgs", "USGS", "usgs", SourceEventKind.DIRECTORY, WhewsTimeZone.UTC8),
        WhewsChannel("emsc", "EMSC", "emsc", SourceEventKind.DIRECTORY, WhewsTimeZone.UTC8),
        WhewsChannel("hko", "HKO", "hko_eqlist", SourceEventKind.DIRECTORY, WhewsTimeZone.UTC8),
        WhewsChannel("bcsf", "BCSF", "bcsf", SourceEventKind.DIRECTORY, WhewsTimeZone.UTC8),
        WhewsChannel("bmkg", "BMKG", "bmkg", SourceEventKind.DIRECTORY, WhewsTimeZone.UTC8),
        WhewsChannel("geonet", "GeoNet", "geonet", SourceEventKind.DIRECTORY, WhewsTimeZone.UTC8),
        WhewsChannel("nrcan", "NRCan", "nrcan", SourceEventKind.DIRECTORY, WhewsTimeZone.UTC8),
        WhewsChannel("tmd", "TMD", "tmd", SourceEventKind.DIRECTORY, WhewsTimeZone.UTC8),
        WhewsChannel("usp", "USP", "usp", SourceEventKind.DIRECTORY, WhewsTimeZone.UTC8),
        WhewsChannel("gfz", "GFZ", "gfz", SourceEventKind.DIRECTORY, WhewsTimeZone.UTC8),
        WhewsChannel("ingv", "INGV", "ingv", SourceEventKind.DIRECTORY, WhewsTimeZone.UTC8),
        WhewsChannel("bgs", "BGS", "bgs", SourceEventKind.DIRECTORY, WhewsTimeZone.UTC8),
        WhewsChannel("mmd", "MMD", "mmd", SourceEventKind.DIRECTORY, WhewsTimeZone.UTC8),
        WhewsChannel("phivolcs", "PHIVOLCS", "phivolcs", SourceEventKind.DIRECTORY, WhewsTimeZone.UTC8),
        WhewsChannel("ipma", "IPMA", "ipma", SourceEventKind.DIRECTORY, WhewsTimeZone.UTC8),
        WhewsChannel("afad", "AFAD", "afad", SourceEventKind.DIRECTORY, WhewsTimeZone.UTC8),
        WhewsChannel("sed", "SED", "sed", SourceEventKind.DIRECTORY, WhewsTimeZone.UTC8),
        WhewsChannel("cenais", "CENAIS", "cenais", SourceEventKind.DIRECTORY, WhewsTimeZone.UTC8),
        WhewsChannel("gsras", "GSRAS", "gsras", SourceEventKind.DIRECTORY, WhewsTimeZone.UTC8),
        WhewsChannel("ga", "GA", "ga", SourceEventKind.DIRECTORY, WhewsTimeZone.UTC8),
        WhewsChannel("ssn", "SSN", "ssn", SourceEventKind.DIRECTORY, WhewsTimeZone.UTC8),
        WhewsChannel("noa", "NOA", "noa", SourceEventKind.DIRECTORY, WhewsTimeZone.UTC8),
        WhewsChannel("scsn", "SCSN", "scsn", SourceEventKind.DIRECTORY, WhewsTimeZone.UTC8),
        WhewsChannel("iag", "IAG", "iag", SourceEventKind.DIRECTORY, WhewsTimeZone.UTC8),
        WhewsChannel("igp", "IGP", "igp", SourceEventKind.DIRECTORY, WhewsTimeZone.UTC8),
        WhewsChannel("nepal", "NEPAL", "nepal", SourceEventKind.DIRECTORY, WhewsTimeZone.UTC8),
        WhewsChannel("funvisis", "FUNVISIS", "funvisis", SourceEventKind.DIRECTORY, WhewsTimeZone.UTC8),
        WhewsChannel("beijing", "北京地震局", "beijing", SourceEventKind.DIRECTORY, WhewsTimeZone.UTC8),
        WhewsChannel("yunnan", "云南地震局", "yunnan", SourceEventKind.DIRECTORY, WhewsTimeZone.UTC8),
        WhewsChannel("ningxia", "宁夏地震局", "ningxia", SourceEventKind.DIRECTORY, WhewsTimeZone.UTC8),
    )

    fun channelFor(source: String): WhewsChannel? = CHANNELS.firstOrNull { it.source == source }

    /** 报文展示名（HUD 标题）：按机构 + 预警/情报区分，对齐 Wolfx / Pancakes 的标题风格。 */
    fun titleFor(channel: WhewsChannel): String = when (channel.kind) {
        SourceEventKind.LIVE -> "${channel.agency} 地震预警"
        else -> "${channel.agency} 地震情报"
    }
}