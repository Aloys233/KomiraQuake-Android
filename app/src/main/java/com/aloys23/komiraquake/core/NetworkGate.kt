package com.aloys23.komiraquake.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * 网络可达性。仅 Android 侧有实际实现：手机网络会静默掉线并切换（WiFi→蜂窝、
 * 电梯地铁），桌面端为固定有线网络，不存在该问题。
 *
 * 存在的意义是让重连退避不再空转：断网期间退避计时毫无意义，恢复的那一刻才是
 * 零成本重连的时机。见 [awaitOnline]。
 */
interface NetworkGate {
    /** 当前是否具备可用网络。初值假定在线，网络回调可能晚于数据源启动。 */
    val online: StateFlow<Boolean>

    /**
     * 挂起直到网络可用。在线时立即返回。
     *
     * @return true 表示本次确实经历了一次断网并等到恢复——调用方应据此跳过剩余
     *   退避并复位退避计数与站点索引（此前的失败由断网造成，不代表站点本身有问题）；
     *   false 表示原本就在线，应照常走退避。
     */
    suspend fun awaitOnline(): Boolean

    /** 无网络感知时的默认实现：恒在线，退化为各源原有的固定退避行为。 */
    object AlwaysOnline : NetworkGate {
        override val online: StateFlow<Boolean> = MutableStateFlow(true)
        override suspend fun awaitOnline(): Boolean = false
    }
}
