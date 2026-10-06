package com.aloys23.komiraquake.data.source.simulated

import com.aloys23.komiraquake.data.source.SourceEventKind

/**
 * 模拟数据源常量。协议 `sim-eew/1`，契约见《NATIVE_PORT_SPEC》 §2.7。
 *
 * 服务端在仓库 `Simulated/`（Go + WebSocket + 浏览器控制台），仅供开发自测。
 */
object SimulatedProtocol {
    /**
     * 机构代号。**必须是 `SIM`，不得改用真实机构代号**：合并键是
     * `sourceAgency|eventId`，若与真实报文（如 CENC）撞键，`EventGate` 会判进同一条目，
     * 告警不触发，用户看到的是真实数据被假数据静默覆盖。
     */
    const val AGENCY = "SIM"

    const val PROVIDER = "Simulated"

    /** 端点路径；主机与端口由用户在设置页自填。 */
    const val PATH = "/ws"

    const val HEARTBEAT_TIMEOUT_MS = 90_000L

    /** Live 帧的活跃窗口：超此年龄的帧由本源丢弃。 */
    const val EEW_LIVE_WINDOW_MS = 30L * 60L * 1000L

    const val DEFAULT_DEPTH = 10.0

    val UNKNOWN_LOCATION = "未知震源"

    /** 报文展示名（HUD 标题）。带「模拟」字样，避免测试时被误认为真实预警。 */
    fun titleFor(kind: SourceEventKind): String = when (kind) {
        SourceEventKind.LIVE -> "模拟数据源 地震预警"
        SourceEventKind.DIRECTORY -> "模拟数据源 地震情报"
    }
}
