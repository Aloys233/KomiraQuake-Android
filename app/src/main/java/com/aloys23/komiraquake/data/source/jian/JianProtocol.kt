package com.aloys23.komiraquake.data.source.jian

import com.aloys23.komiraquake.data.source.SourceEventKind

/**
 * Jian（api.sismotide.top，Jian Project）频道映射。文档：https://api.sismotide.top/api/
 *
 * 只接入「中国 + 日本 + 台湾」地震类频道；EEW 走告警链路，地震速报/目录只进列表。
 * [JianChannel.eventNs] 为频道化 eventId 前缀，用于与 Wolfx / Pancakes 的同名频道对齐合并
 * （互为备份）；留空表示直接使用上游 id 原文（cenc 与 Wolfx cenc_eqlist 对齐）。
 */
data class JianChannel(
    val type: String,
    val agency: String,
    val eventNs: String,
    val kind: SourceEventKind,
)

object JianProtocol {
    /** 聚合通道：一条连接覆盖全部已接入频道（服务端连接数上限 3）。 */
    const val WS_URL = "wss://api.sismotide.top/all"
    /** 登录密钥 → 刷新令牌。 */
    const val REFRESH_URL = "https://auth.sismotide.top/api/refresh"
    /** 刷新令牌 → 访问令牌。 */
    const val ACCESS_URL = "https://auth.sismotide.top/api/access"

    const val PROVIDER = "Jian"
    const val LIST_COMMAND = "alllist"
    const val HEARTBEAT_TIMEOUT_MS = 90_000L
    const val EEW_LIVE_WINDOW_MS = 30L * 60_000L

    val CHANNELS: List<JianChannel> = listOf(
        // EEW：告警链路。前缀与 Wolfx / Pancakes 同名频道对齐，便于跨源合并。
        JianChannel("cea", "CEA", "cenc_eew", SourceEventKind.LIVE),
        JianChannel("cwa-eew", "CWA", "cwa_eew", SourceEventKind.LIVE),
        JianChannel("jma-eew", "JMA", "jma_eew", SourceEventKind.LIVE),
        // 速报/目录：只进列表与历史。cenc 用上游 id 原文，与 Wolfx cenc_eqlist 对齐。
        JianChannel("cenc", "CENC", "", SourceEventKind.DIRECTORY),
        JianChannel("cwa", "CWA", "cwa_eqlist", SourceEventKind.DIRECTORY),
        JianChannel("jma", "JMA", "jma_eqlist", SourceEventKind.DIRECTORY),
        JianChannel("hko", "HKO", "hko_eqlist", SourceEventKind.DIRECTORY),
    )

    fun channelFor(type: String): JianChannel? = CHANNELS.firstOrNull { it.type == type }
}
