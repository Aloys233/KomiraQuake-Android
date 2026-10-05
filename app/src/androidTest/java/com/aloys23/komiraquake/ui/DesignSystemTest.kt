package com.aloys23.komiraquake.ui

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.aloys23.komiraquake.R
import com.aloys23.komiraquake.data.prefs.SettingsStore
import com.aloys23.komiraquake.model.EarthquakeEvent
import com.aloys23.komiraquake.model.WarningLevel
import com.aloys23.komiraquake.ui.components.*
import com.aloys23.komiraquake.ui.theme.AppSurfaces
import com.aloys23.komiraquake.ui.theme.KomiraTheme
import com.aloys23.komiraquake.ui.theme.LocalAppDark
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic UI only; IsolatedTestRunner never starts production feeds or warnings. */
class DesignSystemTest {
    @get:Rule val compose = createComposeRule()

    @Test fun iconButtonsExposeNamesAndDisabledButtonsDoNotInvokeActions() {
        var calls = 0
        compose.setContent {
            KomiraTheme(false) {
                Row {
                    AppIconButton(AppIcon.Plus, "放大地图", false, { calls++ }, enabled = false)
                    AppIconButton(AppIcon.Locate, "定位到我的位置", false, { calls++ })
                }
            }
        }
        compose.onNodeWithContentDescription("放大地图").assertIsNotEnabled()
        compose.onNodeWithContentDescription("定位到我的位置").assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun allLucideResourcesLoadThroughTheStandardTintedWrapper() {
        compose.setContent {
            KomiraTheme(false) {
                FlowRow { AppIcon.entries.forEach { LucideIcon(it, AppSurfaces.onSurface(false), it.name) } }
            }
        }
        AppIcon.entries.forEach { compose.onNodeWithContentDescription(it.name).assertExists() }
    }

    @Test fun largeFontSwitchKeepsItsDescriptionAndClickAction() {
        var checked by mutableStateOf(true)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                KomiraTheme(false) {
                    Box(Modifier.width(320.dp)) {
                        AppSwitchRow("背景模糊", checked, false, { checked = it }, "仅模糊地图背景，不影响文字与减少动态效果设置")
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("背景模糊").assertIsDisplayed().assertIsOn().performClick().assertIsOff()
        compose.runOnIdle { assertFalse(checked) }
    }

    @Test fun warningActionsStayDistinctAndReachableAtLargeFonts() {
        var muted = 0
        var collapsed = 0
        var stopped = 0
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                KomiraTheme(false) {
                    Box(Modifier.width(320.dp).fillMaxHeight()) {
                        WarningOverlay(sample, 12, { collapsed++ }, onMute = { muted++ }, onStop = { stopped++ })
                    }
                }
            }
        }
        compose.onNodeWithText("静音本次").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, muted); assertEquals(0, collapsed); assertEquals(0, stopped) }
        compose.onNodeWithText("收起全屏 · 保留提醒").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("停止本次提醒").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, collapsed); assertEquals(1, stopped) }
    }

    @Test fun warningDefaultUsesEnclosingLightThemeRatherThanDark() {
        var darkSeen: Boolean? = null
        compose.setContent {
            KomiraTheme(false) {
                darkSeen = LocalAppDark.current
                WarningOverlay(sample, -1, {}, modifier = Modifier.testTag("warning"))
            }
        }
        compose.runOnIdle { assertEquals(false, darkSeen) }
        val pixel = compose.onNodeWithTag("warning").captureToImage().toPixelMap()[0, 0]
        assertEquals(AppSurfaces.surface(false).toArgb(), pixel.toArgb())
        compose.onNodeWithText("本地到时未知").assertExists()
    }

    @Test fun missingBackdropUsesOpaqueSurfaceInBothThemes() {
        var dark by mutableStateOf(false)
        compose.setContent {
            KomiraTheme(dark) { Box(Modifier.size(80.dp).testTag("fallback").mapGlass(dark)) }
        }
        for (mode in listOf(false, true)) {
            compose.runOnIdle { dark = mode }
            val pixel = compose.onNodeWithTag("fallback").captureToImage().toPixelMap()[40, 40]
            assertEquals(AppSurfaces.surfaceContainer(mode).toArgb(), pixel.toArgb())
        }
    }

    @Test fun realPreferencesRestoreBlurWithoutCouplingItToMotion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "appearance_test_${System.nanoTime()}"
        try {
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val store = SettingsStore(prefs)
            assertTrue(store.current.backgroundBlur)
            store.update { it.copy(backgroundBlur = false) }
            store.update { it.copy(reduceMotion = true) }
            assertFalse(SettingsStore(prefs).current.backgroundBlur)
            assertTrue(SettingsStore(prefs).current.reduceMotion)
            store.update { it.copy(backgroundBlur = true) }
            assertTrue(SettingsStore(prefs).current.reduceMotion)
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun mainAndWarningLaunchSurfacesMatchComposeInDayAndNight() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        for (dark in listOf(false, true)) {
            val configuration = Configuration(base.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val context = base.createConfigurationContext(configuration)
            for (theme in listOf(R.style.Theme_KomiraQuake, R.style.Theme_KomiraQuake_Warning)) {
                val attrs = ContextThemeWrapper(context, theme).obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground))
                try { assertEquals(AppSurfaces.surface(dark).toArgb(), attrs.getColor(0, 0)) }
                finally { attrs.recycle() }
            }
        }
    }

    private val sample = EarthquakeEvent(id = "ui-test-only", magnitude = 5.5, latitude = 30.0, longitude = 100.0,
        depth = 10.0, location = "用于布局测试的较长地震区域名称", timestamp = 1000L, source = "test",
        distanceKm = 80.0, estimatedIntensity = "IV", rawIntensity = 4.0, warningLevel = WarningLevel.WARNING)
}
