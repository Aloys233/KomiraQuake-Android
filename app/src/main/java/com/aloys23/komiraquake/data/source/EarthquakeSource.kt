package com.aloys23.komiraquake.data.source

import com.aloys23.komiraquake.model.DataSourceInfo
import com.aloys23.komiraquake.model.EarthquakeEvent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** 数据源事件类别：LIVE 走告警链路（预警音/倒计时/HUD），DIRECTORY 只进列表与历史库。 */
enum class SourceEventKind { LIVE, DIRECTORY }

/** 统一的源事件。[generation] 为会话令牌；stop 后到达的缓冲事件由 [EarthquakeSource.isCurrent] 丢弃。 */
data class SourceEvent(val event: EarthquakeEvent, val kind: SourceEventKind, val generation: Long = 0)

/**
 * 数据源提供方的统一接口。Wolfx / Pancakes 以及后续新增的聚合商彼此**平级、互为备份**：
 * QuakeRepository 只按本接口接线，不再逐源硬编码。新增源只需实现该接口并在注册表登记一行；
 * 跨源合并、去重与状态聚合都由通用逻辑处理。
 */
interface EarthquakeSource {
    /** 稳定标识（见 SourceIds）。 */
    val id: String
    val events: SharedFlow<SourceEvent>
    val status: StateFlow<DataSourceInfo>
    /** 该事件是否属于当前会话（start/stop 令牌）；用于丢弃过期迟到事件。 */
    fun isCurrent(event: SourceEvent): Boolean
    /**
     * 凭据是否齐备（如 Jian 的登录令牌）。未就绪的源不会被启动，也就不会建立任何连接；
     * 需要鉴权的源在设置里默认关闭，填好凭据后由用户主动开启。
     */
    fun isConfigured(): Boolean = true
    fun start()
    fun stop()
    fun onLocationChanged()
    fun refreshDirectory()
}
