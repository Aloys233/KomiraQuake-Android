package com.aloys23.komiraquake.data.source.wolfx

/** Wolfx 协议常量。《NATIVE_PORT_SPEC》 §2。 */
object WolfxProtocol {
    val WS_URLS = listOf(
        "wss://ws-api.wolfx.jp/all_eew",
        "wss://api.wolfx.jp/all_eew",
    )
    const val EQ_LIST_URL = "https://api.wolfx.jp/cenc_eqlist.json"
    const val JMA_EQ_LIST_URL = "https://api.wolfx.jp/jma_eqlist.json"

    val QUERIES = listOf(
        "query_cenceew",
        "query_sceew",
        "query_jmaeew",
        "query_cwaeew",
        "query_fjeew",
        "query_cqeew",
    )

    val EEW_TYPES = setOf("cenc_eew", "sc_eew", "jma_eew", "cwa_eew", "fj_eew", "cq_eew")

    /** 报文展示名（HUD 标题）。对齐 Wolfx Open API 文档的接口名。 */
    val TITLES = mapOf(
        "cenc_eew" to "中国地震预警网 地震预警",
        "sc_eew" to "四川省地震局 地震预警",
        "jma_eew" to "JMA 紧急地震速报",
        "cwa_eew" to "CWA 地震预警",
        "fj_eew" to "福建省地震局 地震预警",
        "cq_eew" to "重庆市地震局 地震预警",
    )

    /** 数据源提供方，列表卡片左下角以 `<provider>·<agency>` 标明来源。 */
    const val PROVIDER = "Wolfx"

    /** HTTP 目录（cenc_eqlist）统一由中国地震台网发布。 */
    const val DIRECTORY_AGENCY = "CENC"

    /** HTTP 目录（jma_eqlist）由日本气象厅发布。 */
    const val DIRECTORY_AGENCY_JMA = "JMA"

    /**
     * 报数机构缩写。EEW 的 cenc_eew 是中国地震局（CEA，中国地震预警网）；
     * cenc_eqlist 才是中国地震台网（CENC）——两者不可混为一谈。
     */
    fun agencyFor(type: String): String = when (type) {
        "cenc_eew" -> "CEA"
        "sc_eew" -> "SC"
        "jma_eew" -> "JMA"
        "cwa_eew" -> "CWA"
        "fj_eew" -> "FJ"
        "cq_eew" -> "CQ"
        else -> type
    }

    const val QUERY_INTERVAL_MS = 15_000L
    const val POLL_INTERVAL_MS = 90_000L

    /**
     * EEW 新鲜度窗口：连接成功后 Wolfx 会立刻回放「最近一次」EEW，
     * 发震时刻超过此窗口的电文不算"正在发生"，只丢弃、不告警。
     */
    const val EEW_LIVE_WINDOW_MS = 30L * 60L * 1000L
}
