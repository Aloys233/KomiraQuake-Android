package com.aloys23.komiraquake.data.prefs

import android.content.SharedPreferences
import com.aloys23.komiraquake.core.IntensityStandard
import org.junit.Assert.*
import org.junit.Test

class SettingsStoreTest {
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
        store.update { it.copy(basemapId = "osm", enabledSources = setOf("wolfx"), intensityStandard = IntensityStandard.JMA,
            localIntensityFilter = 5.0, enableWarnings = true, isMuted = true) }
        val before = store.current
        store.update { it.copy(themeMode = ThemeMode.LIGHT, backgroundBlur = false, reduceMotion = true) }
        val expected = before.copy(themeMode = ThemeMode.LIGHT, backgroundBlur = false, reduceMotion = true)
        assertEquals(expected, store.state.value)
        assertEquals(expected, SettingsStore(prefs).current)
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
