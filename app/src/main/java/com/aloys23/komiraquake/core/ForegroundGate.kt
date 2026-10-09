package com.aloys23.komiraquake.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * 应用前后台状态。目录 HTTP 轮询只在 [foreground] 为真时进行：后台不刷新目录，
 * 回到前台由 [awaitForeground] 立即唤醒并刷一次。实时 WS 长连接不受此闸门影响——
 * 预警必须后台可达，见《NATIVE_PORT_SPEC》 §15。
 */
interface ForegroundGate {
    /** 当前是否处于前台。初值假定前台，生命周期回调可能晚于数据源启动。 */
    val foreground: StateFlow<Boolean>

    /**
     * 挂起直到应用回到前台。已在前台时立即返回。
     *
     * @return true 表示本次确实经历了一次后台——调用方据此可立即刷新一次；
     *   false 表示原本就在前台，按正常周期继续。
     */
    suspend fun awaitForeground(): Boolean

    /** 无生命周期感知时的默认实现：恒前台，退化为改动前的“随源一直轮询”行为。 */
    object AlwaysForeground : ForegroundGate {
        override val foreground: StateFlow<Boolean> = MutableStateFlow(true)
        override suspend fun awaitForeground(): Boolean = false
    }
}
