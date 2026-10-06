package com.aloys23.komiraquake.data.source.pancakes

/**
 * PancakesAPI 协议常量。
 *
 * 统一主域 `api.aloys23.link`：聚合通道 `/api/v1/alert/ws/all` 透传全部突发事件，
 * 外层统一为 `{source, type, action, timestampMs, payload}`；本项目只接入其中的
 * 地震类子源（gq / usgs / jma_eew / jma_eqlist），其余气象、海洋与火山事件一律忽略。
 * 列表/历史另走各子源的 HTTP 接口 `GET /api/v1/alert/quake/{source}`。
 */
object PancakesProtocol {
    const val WS_URL = "wss://api.aloys23.link/api/v1/alert/ws/all"
    const val BASE_URL = "https://api.aloys23.link"

    const val SOURCE_GQ = "gq"
    const val SOURCE_USGS = "usgs"
    const val SOURCE_JMA_EEW = "jma_eew"
    const val SOURCE_JMA_EQLIST = "jma_eqlist"

    /** 本项目接入的地震子源。 */
    val QUAKE_SOURCES = listOf(SOURCE_GQ, SOURCE_USGS, SOURCE_JMA_EEW, SOURCE_JMA_EQLIST)

    /** 数据源提供方，列表卡片以 `<provider>·<agency>` 标明来源。 */
    const val PROVIDER = "Pancakes"

    fun listUrl(source: String) = "$BASE_URL/api/v1/alert/quake/$source"

    /** 报数机构缩写。JMA 的 EEW 与速报同属气象厅，合并展示为 JMA。 */
    fun agencyFor(source: String): String = when (source) {
        SOURCE_GQ -> "GQ"
        SOURCE_USGS -> "USGS"
        SOURCE_JMA_EEW -> "JMA"
        SOURCE_JMA_EQLIST -> "JMA"
        else -> source.uppercase()
    }

    /** 报文展示名（HUD 标题）。 */
    fun titleFor(source: String): String = when (source) {
        SOURCE_GQ -> "GlobalQuake地震信息"
        SOURCE_USGS -> "USGS 地震信息"
        SOURCE_JMA_EEW -> "JMA 紧急地震速报"
        SOURCE_JMA_EQLIST -> "JMA 地震情报"
        else -> source
    }

    /**
     * 实时告警新鲜度窗口：上游连接建立后不回放历史，此窗口仅用于防御迟到/重放的报文。
     */
    const val LIVE_WINDOW_MS = 30L * 60L * 1000L

    /** HTTP 目录轮询间隔。 */
    const val POLL_INTERVAL_MS = 90_000L

    /** 取消报没有递增报数，用哨兵报数保证能覆盖旧报次并终止生命周期。 */
    const val CANCEL_REPORT_NUM = 999_999
}
