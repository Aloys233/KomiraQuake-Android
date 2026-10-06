package com.aloys23.komiraquake.data.source

import com.aloys23.komiraquake.core.NetworkGate
import kotlinx.coroutines.delay

/**
 * 网络感知的重连退避，供各数据源的 `disconnected()` 共用。
 *
 * 断网时不再空转退避计时：挂起等待网络恢复，恢复那一刻立即重连。移动端在电梯、
 * 地铁、弱网下会长时间无网，原先的盲退避既白耗电，又让恢复后的预警迟到最多 15 秒。
 *
 * 只负责「要不要等这 [delayMs]」这一个判断；复位退避计数与站点索引、以及连接本身，
 * 都交回调用方在它自己的 `synchronized(lock)` 内完成——那些是各源的共享可变状态，
 * 放到锁外改会与回调线程争用。
 *
 * @return true 表示本次确实经历了一次断网并等到恢复——此前的失败由断网造成，
 *   不代表站点本身不可用，调用方应据此复位退避计数与站点索引。
 */
suspend fun awaitReconnectDelay(gate: NetworkGate, delayMs: Long): Boolean {
    if (gate.awaitOnline()) return true
    delay(delayMs)
    return false
}
