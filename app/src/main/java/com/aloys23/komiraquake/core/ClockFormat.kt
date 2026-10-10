package com.aloys23.komiraquake.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 地图左下角校时时钟的展示格式。《NATIVE_PORT_SPEC》 §12。
 *
 * `Clock.now()` 是 epoch ms（与时区无关）；展示时**固定渲染为 UTC+8**，
 * 因此设备处于任何时区，界面上的时刻与 `UTC+8` 标注都一致、不会说谎。
 */
object ClockFormat {
    /** 固定显示时区：UTC+8。 */
    const val ZONE_ID = "GMT+08:00"

    /** 时区标注文本。 */
    const val ZONE_LABEL = "UTC+8"

    /**
     * SimpleDateFormat 非线程安全，且构造昂贵（每次都要解析 pattern、载入 locale 数据）。
     * 列表每个条目、地图时钟每 200ms 都要渲染发震时刻，原来每次调用都新建实例，是滚动时
     * 主要的 GC 来源之一。这里按线程复用一个实例。
     */
    private val stampFormat: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone(ZONE_ID) }
    }

    /**
     * 把 epoch ms 渲染为 UTC+8 的 `yyyy-MM-dd HH:mm:ss`。
     * 使用显式时区，不依赖设备默认时区。
     */
    fun utc8Stamp(epochMs: Long): String = stampFormat.get()!!.format(Date(epochMs))
}
