package com.aloys23.komiraquake.model

enum class ConnectionStatus { CONNECTED, CONNECTING, DISCONNECTED, ERROR }

/** 数据源链路状态。字段与 《NATIVE_PORT_SPEC》 §1.3 一致。 */
data class DataSourceInfo(
    val id: String,
    val name: String,
    val region: String,
    val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val latencyMs: Long? = null,
    val lastHeartbeat: Long? = null,
    val description: String = "",
    /** HTTP catalog health is independent of the real-time WebSocket status above. */
    val directoryStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val directoryLatencyMs: Long? = null,
    val directoryLastSuccessAt: Long? = null,
    val directoryError: String? = null,
) {
    val statusLabel: String
        get() = when (status) {
            ConnectionStatus.CONNECTED -> "在线"
            ConnectionStatus.CONNECTING -> "连接中"
            ConnectionStatus.DISCONNECTED -> "已断开"
            ConnectionStatus.ERROR -> "异常"
        }
}

/** 数据源唯一标识。 */
object SourceIds {
    const val WOLFX = "wolfx"
    const val PANCAKES = "pancakes"
    const val JIAN = "jian"
    const val WHEWS = "whews"

    /** 自建模拟源（仅开发自测）。由开发者模式 + 地址双重门控，不在下方迁移白名单内。 */
    const val SIMULATED = "simulated"

    /**
     * 旧版「启用集合」时代已知的源，仅用于从 `enabledSources` 迁移到 `disabledSources`。
     * **不要**把后来新增的源加进来：迁移只应把当时被用户关闭的源标为禁用，新源默认启用。
     */
    val ALL = setOf(WOLFX, PANCAKES)
}
