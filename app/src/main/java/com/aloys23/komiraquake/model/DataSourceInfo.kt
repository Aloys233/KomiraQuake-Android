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
    val ALL = setOf(WOLFX, PANCAKES)
}
