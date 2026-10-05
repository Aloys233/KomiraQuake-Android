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
     * 把 epoch ms 渲染为 UTC+8 的 `yyyy-MM-dd HH:mm:ss`。
     * 使用显式时区，不依赖设备默认时区。
     */
    fun utc8Stamp(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone(ZONE_ID) }
            .format(Date(epochMs))
}
