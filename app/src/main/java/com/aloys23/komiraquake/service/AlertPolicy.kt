package com.aloys23.komiraquake.service

import com.aloys23.komiraquake.data.prefs.Settings
import com.aloys23.komiraquake.model.EarthquakeEvent

/**
 * Shared side-effect policy.
 *
 * 提醒总开关是 [Settings.enableWarnings]（设置页「地震预警」，默认关闭）。开启后**只按本地
 * 预估烈度过滤**（[Settings.localIntensityFilter]）决定是否提醒，不再做任何震级过滤；
 * 未开启或未达阈值时事件仅进入列表/地图/HUD 展示，不产生声音、语音、震动或全屏预警。
 */
object AlertPolicy {
    data class Decision(
        val eligible: Boolean,
        val audio: Boolean,
        val speech: Boolean,
        val vibration: Boolean,
        val fullScreen: Boolean,
        val dnd: Boolean,
    )

    fun evaluate(event: EarthquakeEvent, settings: Settings, eventMuted: Boolean = false, stopped: Boolean = false): Decision {
        val hasDistance = event.distanceKm.isFinite() && event.distanceKm >= 0
        // 本地烈度过滤：仅当本地预估烈度达到该阈值时才提醒；0 = 不作筛选，无定位时无法判定故放行。
        val passesLocalFilter = settings.localIntensityFilter <= 0.0 || !hasDistance ||
            (event.rawIntensity.isFinite() && event.rawIntensity >= settings.localIntensityFilter)
        val eligible = settings.enableWarnings && !event.isCanceled && !stopped && passesLocalFilter
        val audible = eligible && !settings.isMuted && !eventMuted
        val audio = audible && settings.enableSoundAlert && settings.alertVolume > 0
        val speech = audible && settings.enableSpeech && settings.alertVolume > 0
        return Decision(
            eligible,
            audio,
            speech,
            audible,
            eligible,
            settings.enableDndBypass && (audio || speech),
        )
    }
}
