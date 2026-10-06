package com.aloys23.komiraquake.core

/**
 * 语句通道播放队列。串行播放，避免新播报打断尚未播完的语句。
 * 纯逻辑，不依赖 Android framework，便于单元测试。
 *
 * 倒计时与警报音不走本队列：它们每秒触发，长音频会堵死队列导致秒数被吞，
 * 见AlertSoundService 的提示通道。
 */
class SoundQueue {
    private val items = ArrayDeque<String>()

    /** 入队；同一路径已在队列中时不重复排队。队列满时丢弃最旧的一条。 */
    fun enqueue(path: String): Boolean {
        if (path in items) return false
        while (items.size >= maxSize) items.removeFirst()
        items.addLast(path)
        return true
    }

    fun isEmpty(): Boolean = items.isEmpty()

    fun size(): Int = items.size

    /** 取下一条待播项；队列为空返回 null。 */
    fun take(): String? = items.removeFirstOrNull()

    fun clear() = items.clear()

    private companion object {
        const val maxSize = 16
    }
}