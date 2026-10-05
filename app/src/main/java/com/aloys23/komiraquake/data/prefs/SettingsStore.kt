package com.aloys23.komiraquake.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.aloys23.komiraquake.core.IntensityStandard
import com.aloys23.komiraquake.model.SourceIds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    companion object {
        fun fromPreference(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

/** 设置快照。《NATIVE_PORT_SPEC》 §7。 */
data class Settings(
    val basemapId: String = "amap_vector",
    val customBasemapUrl: String = "",
    val customBasemapDatum: Int = 1, // 0 = wgs84, 1 = gcj02
    val intensityStandard: IntensityStandard = IntensityStandard.CSIS,
    /** 本地烈度过滤：仅当本地预估烈度达到该值时才提醒；0 表示不作筛选。 */
    val localIntensityFilter: Double = 0.0,
    /** 地震预警总开关（设置页「地震预警」）：关闭时只展示，不产生任何提醒。 */
    val enableWarnings: Boolean = false,
    val enableSoundAlert: Boolean = true,
    val backgroundBlur: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val reduceMotion: Boolean = false,
    val isMuted: Boolean = false,
    val enabledSources: Set<String> = SourceIds.ALL,
    val alertVolume: Double = 1.0,
    val enableSpeech: Boolean = false,
    val speakUpdates: Boolean = false,
    val speakCountdown: Boolean = true,
    val speechRate: Double = 0.5,
    val enableNtpSync: Boolean = true,
    /** 自定义 NTP 服务器主机名；留空则使用内置 SNTP 列表。 */
    val customNtpServer: String = "",
    val enableBackgroundGuard: Boolean = true,
    val enableDndBypass: Boolean = true,
)

/** SharedPreferences 持久化，暴露 StateFlow 供 Compose 订阅。 */
class SettingsStore internal constructor(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
    )

    private val _state = MutableStateFlow(load())
    val state: StateFlow<Settings> = _state.asStateFlow()

    val current: Settings get() = _state.value

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_state.value)
        persist(next)
        _state.value = next
    }

    private fun load(): Settings = Settings(
        basemapId = prefs.getString("basemapId", "amap_vector") ?: "amap_vector",
        customBasemapUrl = prefs.getString("customBasemapUrl", "") ?: "",
        customBasemapDatum = prefs.getInt("customBasemapDatum", 1),
        intensityStandard = if (prefs.getInt("intensityStandard", 0) == 1) {
            IntensityStandard.JMA
        } else {
            IntensityStandard.CSIS
        },
        localIntensityFilter = prefs.getFloat("localIntensityFilter", 0.0f).toDouble(),
        enableWarnings = prefs.getBoolean("enableWarnings", false),
        enableSoundAlert = prefs.getBoolean("enableSoundAlert", true),
        backgroundBlur = prefs.getBoolean("backgroundBlur", true),
        themeMode = ThemeMode.fromPreference(prefs.getString("themeMode", null)),
        reduceMotion = prefs.getBoolean("reduceMotion", false),
        isMuted = prefs.getBoolean("isMuted", false),
        enabledSources = prefs.getStringSet("enabledSources", SourceIds.ALL) ?: SourceIds.ALL,
        alertVolume = prefs.getFloat("alertVolume", 1.0f).toDouble(),
        enableSpeech = prefs.getBoolean("enableSpeech", false),
        speakUpdates = prefs.getBoolean("speakUpdates", false),
        speakCountdown = prefs.getBoolean("speakCountdown", true),
        speechRate = prefs.getFloat("speechRate", 0.5f).toDouble(),
        enableNtpSync = prefs.getBoolean("enableNtpSync", true),
        customNtpServer = prefs.getString("customNtpServer", "") ?: "",
        enableBackgroundGuard = prefs.getBoolean("enableBackgroundGuard", true),
        enableDndBypass = prefs.getBoolean("enableDndBypass", true),
    )

    private fun persist(s: Settings) {
        prefs.edit()
            .putString("basemapId", s.basemapId)
            .putString("customBasemapUrl", s.customBasemapUrl)
            .putInt("customBasemapDatum", s.customBasemapDatum)
            .putInt("intensityStandard", if (s.intensityStandard == IntensityStandard.JMA) 1 else 0)
            .putFloat("localIntensityFilter", s.localIntensityFilter.toFloat())
            .putBoolean("enableWarnings", s.enableWarnings)
            .putBoolean("enableSoundAlert", s.enableSoundAlert)
            .putBoolean("backgroundBlur", s.backgroundBlur)
            .putString("themeMode", s.themeMode.name)
            .putBoolean("reduceMotion", s.reduceMotion)
            .putBoolean("isMuted", s.isMuted)
            .putStringSet("enabledSources", s.enabledSources)
            .putFloat("alertVolume", s.alertVolume.toFloat())
            .putBoolean("enableSpeech", s.enableSpeech)
            .putBoolean("speakUpdates", s.speakUpdates)
            .putBoolean("speakCountdown", s.speakCountdown)
            .putFloat("speechRate", s.speechRate.toFloat())
            .putBoolean("enableNtpSync", s.enableNtpSync)
            .putString("customNtpServer", s.customNtpServer)
            .putBoolean("enableBackgroundGuard", s.enableBackgroundGuard)
            .putBoolean("enableDndBypass", s.enableDndBypass)
            .apply()
    }

    companion object {
        const val PREFS = "komira_settings"
    }
}
