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
    val basemapId: String = "petal",
    val intensityStandard: IntensityStandard = IntensityStandard.CSIS,
    /** 本地烈度过滤：仅当本地预估烈度达到该值时才提醒；0 表示不作筛选。 */
    val localIntensityFilter: Double = 0.0,
    /** 地震预警总开关（设置页「地震预警」）：关闭时只展示，不产生任何提醒。 */
    val enableWarnings: Boolean = false,
    /**
     * 用户是否已关闭过「预警未开启」的引导卡。预警默认关闭（不擅自替用户打开安全功能），
     * 但新装用户第一眼必须知道这件事——否则就成了「装了但不报警」。引导可关闭一次，
     * 此后不再打扰；开启预警后引导自然不再出现。
     */
    val warningOnboardingDismissed: Boolean = false,
    val enableSoundAlert: Boolean = true,
    val backgroundBlur: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val reduceMotion: Boolean = false,
    val isMuted: Boolean = false,
    /**
     * 已禁用的数据源 id 集合。采用「禁用集合」而非「启用集合」：未列出的源默认启用，
     * 新增数据源无需迁移即默认开启（与桌面端一致）。需要鉴权凭据的源（Jian）例外，
     * 默认关闭——未填凭据不建立任何连接，由用户登录后主动开启。
     */
    val disabledSources: Set<String> = defaultDisabledSources(),
    /**
     * Jian 数据源的刷新令牌（rt_…，登录后由应用写入并持久化；不向 UI 回显明文）。
     * 登录密钥 lk_… 一次性使用，不落盘。
     */
    val jianRefreshToken: String = "",
    /**
     * Whews 数据源的访问令牌（wat_…，在 auth.beecld.com 申请；设置页粘贴后写入并持久化；
     * 不向 UI 回显明文）。留空即视为未配置，不会建立任何连接。
     */
    val whewsToken: String = "",
    val alertVolume: Double = 1.0,
    val enableNtpSync: Boolean = true,
    /** 自定义 NTP 服务器主机名；留空则使用内置 SNTP 列表。 */
    val customNtpServer: String = "",
    /**
     * 开发者模式：显出模拟数据源的卡片与地址输入框。关闭时该源不被启动、不建立任何连接。
     * 模拟源是唯一消费者，但它自己的 `isConfigured()` 里也会再查一次，
     * 故即便这里被绕过，没有地址也不会连接。
     */
    val developerMode: Boolean = false,
    /**
     * 模拟数据源的 WebSocket 地址（如 `ws://10.0.2.2:8080/ws`）。留空即视为未配置。
     * 默认为空而非预填：默认值只作为 UI 占位提示，「开发者模式开但从未配置」
     * 仍应是未配置状态。
     */
    val simulatedUrl: String = "",
    val enableBackgroundGuard: Boolean = true,
    val enableDndBypass: Boolean = true,
) {
    /** 某数据源是否启用（未在禁用集合中即启用）。 */
    fun enabled(id: String): Boolean = id !in disabledSources
}

/** 需要鉴权凭据的源默认关闭：填好凭据后由用户主动开启，避免无凭据时建立连接。 */
fun defaultDisabledSources(): Set<String> = setOf(SourceIds.JIAN, SourceIds.WHEWS)

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

    /** 恢复默认：清空全部偏好后重新载入，与桌面端 resetToDefaults() 语义一致。 */
    fun resetToDefaults() {
        prefs.edit().clear().apply()
        _state.value = load()
    }

    private fun load(): Settings = Settings(
        basemapId = prefs.getString("basemapId", "petal") ?: "petal",
        intensityStandard = if (prefs.getInt("intensityStandard", 0) == 1) {
            IntensityStandard.JMA
        } else {
            IntensityStandard.CSIS
        },
        localIntensityFilter = prefs.getFloat("localIntensityFilter", 0.0f).toDouble(),
        enableWarnings = prefs.getBoolean("enableWarnings", false),
        warningOnboardingDismissed = prefs.getBoolean("warningOnboardingDismissed", false),
        enableSoundAlert = prefs.getBoolean("enableSoundAlert", true),
        backgroundBlur = prefs.getBoolean("backgroundBlur", true),
        themeMode = ThemeMode.fromPreference(prefs.getString("themeMode", null)),
        reduceMotion = prefs.getBoolean("reduceMotion", false),
        isMuted = prefs.getBoolean("isMuted", false),
        disabledSources = loadDisabledSources(prefs),
        jianRefreshToken = prefs.getString("jianRefreshToken", "") ?: "",
        whewsToken = prefs.getString("whewsToken", "") ?: "",
        alertVolume = prefs.getFloat("alertVolume", 1.0f).toDouble(),
        enableNtpSync = prefs.getBoolean("enableNtpSync", true),
        customNtpServer = prefs.getString("customNtpServer", "") ?: "",
        developerMode = prefs.getBoolean("developerMode", false),
        simulatedUrl = prefs.getString("simulatedUrl", "") ?: "",
        enableBackgroundGuard = prefs.getBoolean("enableBackgroundGuard", true),
        enableDndBypass = prefs.getBoolean("enableDndBypass", true),
    )

    /**
     * 读取禁用集合；首次升级时从旧版「启用集合」迁移：
     * 旧集合中未启用的源 → 禁用，其余（含之后新增的源）默认启用。
     */
    private fun loadDisabledSources(prefs: SharedPreferences): Set<String> {
        if (prefs.contains("disabledSources")) {
            return prefs.getStringSet("disabledSources", null) ?: defaultDisabledSources()
        }
        val legacyEnabled = prefs.getStringSet("enabledSources", null)
        // 旧版「启用集合」里未启用的源 → 禁用；并叠加默认关闭的鉴权源。
        return defaultDisabledSources() + if (legacyEnabled != null) SourceIds.ALL - legacyEnabled else emptySet()
    }

    private fun persist(s: Settings) {
        prefs.edit()
            .putString("basemapId", s.basemapId)
            .putInt("intensityStandard", if (s.intensityStandard == IntensityStandard.JMA) 1 else 0)
            .putFloat("localIntensityFilter", s.localIntensityFilter.toFloat())
            .putBoolean("enableWarnings", s.enableWarnings)
            .putBoolean("warningOnboardingDismissed", s.warningOnboardingDismissed)
            .putBoolean("enableSoundAlert", s.enableSoundAlert)
            .putBoolean("backgroundBlur", s.backgroundBlur)
            .putString("themeMode", s.themeMode.name)
            .putBoolean("reduceMotion", s.reduceMotion)
            .putBoolean("isMuted", s.isMuted)
            .putStringSet("disabledSources", s.disabledSources)
            .putString("jianRefreshToken", s.jianRefreshToken)
            .putString("whewsToken", s.whewsToken)
            .putFloat("alertVolume", s.alertVolume.toFloat())
            .putBoolean("enableNtpSync", s.enableNtpSync)
            .putString("customNtpServer", s.customNtpServer)
            .putBoolean("developerMode", s.developerMode)
            .putString("simulatedUrl", s.simulatedUrl)
            .putBoolean("enableBackgroundGuard", s.enableBackgroundGuard)
            .putBoolean("enableDndBypass", s.enableDndBypass)
            .apply()
    }

    companion object {
        const val PREFS = "komira_settings"
    }
}
