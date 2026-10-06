package com.aloys23.komiraquake.data.prefs

import android.content.SharedPreferences
import com.aloys23.komiraquake.core.IntensityStandard
import com.aloys23.komiraquake.model.SourceIds
import org.junit.Assert.*
import org.junit.Test

class SettingsStoreTest {
    /** 需要鉴权的源默认关闭，未填凭据前不建立连接。 */
    @Test fun credentialSourcesAreOffByDefault() {
        val store = SettingsStore(MemoryPreferences())
        assertFalse(store.current.enabled(SourceIds.JIAN))
        assertFalse(store.current.enabled(SourceIds.WHEWS))
        assertTrue(store.current.enabled(SourceIds.WOLFX))
        assertTrue(store.current.enabled(SourceIds.PANCAKES))
        assertEquals("", store.current.jianRefreshToken)
        assertEquals("", store.current.whewsToken)
        // 旧版「启用集合」迁移后仍叠加默认关闭的鉴权源。
        val legacy = MemoryPreferences()
        legacy.edit().putStringSet("enabledSources", setOf(SourceIds.WOLFX)).apply()
        val migrated = SettingsStore(legacy).current
        assertTrue(migrated.enabled(SourceIds.WOLFX))
        assertFalse(migrated.enabled(SourceIds.PANCAKES))
        assertFalse(migrated.enabled(SourceIds.JIAN))
        assertFalse(migrated.enabled(SourceIds.WHEWS))
    }

    /** Whews 令牌可持久化。 */
    @Test fun whewsTokenIsPersisted() {
        val store = SettingsStore(MemoryPreferences())
        store.update { it.copy(whewsToken = "wat_x") }
        assertEquals("wat_x", store.current.whewsToken)
        assertTrue(store.current.enabled(SourceIds.JIAN).not())
        // 写入令牌不影响其它源的启用状态。
        assertTrue(store.current.enabled(SourceIds.WOLFX))
    }

    /** 新增源不得加入旧版迁移白名单：否则升级用户会被误判为「已禁用过」。 */
    @Test fun whewsIsNotInLegacyMigrationAllowlist() {
        assertFalse(SourceIds.WHEWS in SourceIds.ALL)
    }
    @Test fun blurDefaultsOnAndMotionDefaultsOff() {
        val store = SettingsStore(MemoryPreferences())
        assertTrue(Settings().backgroundBlur)
        assertTrue(store.current.backgroundBlur)
        assertFalse(store.current.reduceMotion)
    }

    @Test fun legacyPreferencesKeepTheirMotionChoiceWithoutDisablingBlur() {
        val prefs = MemoryPreferences()
        prefs.edit().putBoolean("reduceMotion", true).putFloat("localIntensityFilter", 5.5f).apply()
        val restored = SettingsStore(prefs).current
        assertTrue(restored.backgroundBlur)
        assertTrue(restored.reduceMotion)
        assertEquals(5.5, restored.localIntensityFilter, 0.0)
    }

    @Test fun everyBlurMotionCombinationSurvivesReloadIndependently() {
        val prefs = MemoryPreferences()
        for (blur in listOf(false, true)) for (motion in listOf(false, true)) {
            val store = SettingsStore(prefs)
            store.update { it.copy(backgroundBlur = blur, reduceMotion = motion) }
            assertEquals(blur, prefs.getBoolean("backgroundBlur", !blur))
            assertEquals(motion, prefs.getBoolean("reduceMotion", !motion))
            assertEquals(blur, SettingsStore(prefs).current.backgroundBlur)
            assertEquals(motion, SettingsStore(prefs).current.reduceMotion)
        }
    }

    @Test fun themeDefaultsToSystemForNewLegacyAndUnknownPreferences() {
        assertEquals(ThemeMode.SYSTEM, Settings().themeMode)
        val prefs = MemoryPreferences()
        prefs.edit().putBoolean("reduceMotion", true).apply()
        assertEquals(ThemeMode.SYSTEM, SettingsStore(prefs).current.themeMode)
        prefs.edit().putString("themeMode", "unknown-mode").apply()
        assertEquals(ThemeMode.SYSTEM, SettingsStore(prefs).current.themeMode)
        assertTrue(SettingsStore(prefs).current.reduceMotion)
    }

    @Test fun everyThemeBlurMotionCombinationSurvivesReloadIndependently() {
        val prefs = MemoryPreferences()
        for (theme in ThemeMode.entries) for (blur in listOf(false, true)) for (motion in listOf(false, true)) {
            val store = SettingsStore(prefs)
            store.update { it.copy(themeMode = theme, backgroundBlur = blur, reduceMotion = motion) }
            assertEquals(theme.name, prefs.getString("themeMode", null))
            val restored = SettingsStore(prefs).current
            assertEquals(theme, restored.themeMode)
            assertEquals(blur, restored.backgroundBlur)
            assertEquals(motion, restored.reduceMotion)
        }
    }

    @Test fun changingMotionDoesNotUndoPersistedBlurChoice() {
        val prefs = MemoryPreferences()
        val store = SettingsStore(prefs)
        store.update { it.copy(backgroundBlur = false) }
        store.update { it.copy(reduceMotion = true) }
        store.update { it.copy(reduceMotion = false) }
        assertFalse(SettingsStore(prefs).current.backgroundBlur)
        assertFalse(store.current.reduceMotion)
    }

    @Test fun appearanceUpdatesPreserveWarningSourceAndMapPreferences() {
        val prefs = MemoryPreferences()
        val store = SettingsStore(prefs)
        store.update { it.copy(basemapId = "osm", disabledSources = setOf("pancakes"), intensityStandard = IntensityStandard.JMA,
            localIntensityFilter = 5.0, enableWarnings = true, isMuted = true) }
        val before = store.current
        store.update { it.copy(themeMode = ThemeMode.LIGHT, backgroundBlur = false, reduceMotion = true) }
        val expected = before.copy(themeMode = ThemeMode.LIGHT, backgroundBlur = false, reduceMotion = true)
        assertEquals(expected, store.state.value)
        assertEquals(expected, SettingsStore(prefs).current)
    }

    @Test fun resetToDefaultsRestoresEveryFieldAndPersists() {
        val prefs = MemoryPreferences()
        val store = SettingsStore(prefs)
        store.update {
            it.copy(themeMode = ThemeMode.DARK, backgroundBlur = false, reduceMotion = true,
                basemapId = "osm", enableWarnings = true, localIntensityFilter = 4.5,
                alertVolume = 0.3, disabledSources = setOf("wolfx", "jian"))
        }
        store.resetToDefaults()
        assertEquals(Settings(), store.state.value)
        // 清空后重新读取也必须是默认值，而不是内存里的旧快照。
        assertEquals(Settings(), SettingsStore(prefs).current)
    }

    @Test fun resetToDefaultsDropsPersistedTokens() {
        val prefs = MemoryPreferences()
        val store = SettingsStore(prefs)
        store.update { it.copy(jianRefreshToken = "rt_secret") }
        assertTrue(SettingsStore(prefs).current.jianRefreshToken.isNotEmpty())
        store.resetToDefaults()
        assertEquals("", SettingsStore(prefs).current.jianRefreshToken)
    }

    /**
     * 开发者模式与模拟源地址必须经 persist→load 往返存活。
     * 漏写 `persist()` 是本文件最隐蔽的坑：内存里看着对，重启即丢。
     */
    @Test fun developerModeAndSimulatedUrlSurvivePersistReload() {
        val prefs = MemoryPreferences()
        val store = SettingsStore(prefs)
        // 默认值：开发者模式关、地址空（不预填，使「开了但没配」仍是未配置）。
        assertFalse(store.current.developerMode)
        assertEquals("", store.current.simulatedUrl)

        store.update { it.copy(developerMode = true, simulatedUrl = "ws://10.0.2.2:8080/ws") }
        val reloaded = SettingsStore(prefs).current
        assertTrue("开发者模式应已持久化", reloaded.developerMode)
        assertEquals("ws://10.0.2.2:8080/ws", reloaded.simulatedUrl)

        // 模拟源刻意不进默认禁用集：门控交给 isConfigured，否则形成三重门。
        assertFalse(SourceIds.SIMULATED in defaultDisabledSources())
        assertTrue(SettingsStore(prefs).current.enabled(SourceIds.SIMULATED))
    }

    /** 恢复默认必须一并清掉开发者模式与地址，否则源会在下次启动时静默连上。 */
    @Test fun resetToDefaultsClearsDeveloperSettings() {
        val prefs = MemoryPreferences()
        val store = SettingsStore(prefs)
        store.update { it.copy(developerMode = true, simulatedUrl = "ws://10.0.2.2:8080/ws") }
        store.resetToDefaults()
        assertFalse(SettingsStore(prefs).current.developerMode)
        assertEquals("", SettingsStore(prefs).current.simulatedUrl)
    }

    /** 新增源不得进旧版迁移白名单，否则升级用户会被当成「已禁用」。 */
    @Test fun simulatedSourceIsNotInLegacyMigrationAllowlist() {
        assertFalse(SourceIds.SIMULATED in SourceIds.ALL)
    }

    /**
     * 预警默认关闭（不擅自替用户打开安全功能），引导卡的关闭状态必须持久化，
     * 否则每次启动都弹一次就成了骚扰。
     */
    @Test fun warningsStayOffByDefaultAndOnboardingDismissalPersists() {
        val prefs = MemoryPreferences()
        val store = SettingsStore(prefs)
        assertFalse("预警默认应为关闭", store.current.enableWarnings)
        assertFalse("引导卡默认应显示", store.current.warningOnboardingDismissed)

        store.update { it.copy(warningOnboardingDismissed = true) }
        val reloaded = SettingsStore(prefs).current
        assertTrue("关闭引导的状态应已持久化", reloaded.warningOnboardingDismissed)
        assertFalse("关闭引导不得顺带打开预警", reloaded.enableWarnings)

        // 开启预警后引导不再出现，但关闭标记保留，用户手动改回时无需重看。
        store.update { it.copy(enableWarnings = true) }
        assertTrue(SettingsStore(prefs).current.enableWarnings)
        assertTrue(SettingsStore(prefs).current.warningOnboardingDismissed)
    }

    /** 恢复默认应把引导卡重新带回未关闭状态。 */
    @Test fun resetToDefaultsRestoresOnboardingPrompt() {
        val prefs = MemoryPreferences()
        val store = SettingsStore(prefs)
        store.update { it.copy(enableWarnings = true, warningOnboardingDismissed = true) }
        store.resetToDefaults()
        assertFalse(store.current.enableWarnings)
        assertFalse(store.current.warningOnboardingDismissed)
    }
}

/** In-memory SharedPreferences contract; no Android framework or production settings touched. */
private class MemoryPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun contains(key: String?) = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clearFirst = false
        private fun put(key: String?, value: Any?) = apply { if (key != null) pending[key] = value }
        override fun putString(key: String?, value: String?) = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values?.toSet())
        override fun putInt(key: String?, value: Int) = put(key, value)
        override fun putLong(key: String?, value: Long) = put(key, value)
        override fun putFloat(key: String?, value: Float) = put(key, value)
        override fun putBoolean(key: String?, value: Boolean) = put(key, value)
        override fun remove(key: String?) = put(key, null)
        override fun clear(): SharedPreferences.Editor = apply { clearFirst = true }
        override fun commit(): Boolean {
            if (clearFirst) values.clear()
            pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            return true
        }
        override fun apply() { commit() }
    }
}
