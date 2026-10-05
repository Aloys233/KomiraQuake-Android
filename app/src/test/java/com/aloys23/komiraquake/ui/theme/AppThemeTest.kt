package com.aloys23.komiraquake.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.aloys23.komiraquake.data.prefs.ThemeMode
import com.aloys23.komiraquake.model.WarningLevel
import com.aloys23.komiraquake.ui.components.canUseMapBlur
import org.junit.Assert.*
import org.junit.Test

class AppThemeTest {
    @Test fun miuixSurfaceAndTextRolesMatchAppTokensInBothThemes() {
        for (dark in listOf(false, true)) {
            val colors = appColorScheme(dark)
            assertEquals(AppSurfaces.surface(dark), colors.background)
            assertEquals(AppSurfaces.onSurface(dark), colors.onBackground)
            assertEquals(AppSurfaces.surfaceContainer(dark), colors.surface)
            assertEquals(AppSurfaces.onSurface(dark), colors.onSurface)
            assertEquals(AppSurfaces.outline(dark), colors.onSurfaceSecondary)
            assertEquals(AppSurfaces.surfaceContainerHigh(dark), colors.surfaceVariant)
            assertEquals(AppSurfaces.surfaceContainer(dark), colors.surfaceContainer)
            assertEquals(AppSurfaces.onSurface(dark), colors.onSurfaceContainer)
            assertEquals(AppSurfaces.outlineVariant(dark), colors.dividerLine)
        }
    }

    @Test fun controlsUseTealInsteadOfMiuixDefaultBlue() {
        for (dark in listOf(false, true)) {
            val colors = appColorScheme(dark)
            assertEquals(AppSurfaces.accent(dark), colors.primary)
            assertEquals(AppSurfaces.accent(dark), colors.primaryVariant)
            assertEquals(AppSurfaces.accent(dark), colors.onSurfaceVariantActions)
            assertEquals(AppSurfaces.accentContainer(dark), colors.primaryContainer)
            assertEquals(SeismicColors.on(colors.primary), colors.onPrimary)
            assertEquals(AppSurfaces.disabled(dark), colors.disabledOnSurface)
            assertEquals(AppSurfaces.disabled(dark), colors.disabledOnPrimaryButton)
        }
    }

    @Test fun normalAndSecondaryTextStayReadableAcrossOpaqueSurfaces() {
        for (dark in listOf(false, true)) {
            val surfaces = listOf(AppSurfaces.surface(dark), AppSurfaces.surfaceContainerLow(dark),
                AppSurfaces.surfaceContainer(dark), AppSurfaces.surfaceContainerHigh(dark))
            for (surface in surfaces) {
                assertEquals(1f, surface.alpha)
                assertTrue("Primary text dark=$dark", contrast(AppSurfaces.onSurface(dark), surface) >= 4.5f)
                assertTrue("Secondary text dark=$dark", contrast(AppSurfaces.outline(dark), surface) >= 4.5f)
            }
            assertTrue(contrast(SeismicColors.on(AppSurfaces.accent(dark)), AppSurfaces.accent(dark)) >= 4.5f)
        }
    }

    @Test fun warningSemanticColorsRetainLegibleForegrounds() {
        for (dark in listOf(false, true)) for (level in WarningLevel.entries) {
            val severity = SeismicColors.severity(level, dark)
            assertTrue("Warning $level dark=$dark", contrast(SeismicColors.on(severity), severity) >= 4.5f)
        }
    }

    @Test fun explicitThemesOverrideTheSystemAndSystemModeFollowsIt() {
        for (systemDark in listOf(false, true)) {
            assertEquals(systemDark, resolveDarkTheme(ThemeMode.SYSTEM, systemDark))
            assertFalse(resolveDarkTheme(ThemeMode.LIGHT, systemDark))
            assertTrue(resolveDarkTheme(ThemeMode.DARK, systemDark))
        }
    }

    @Test fun glassTintsLeaveTheBackdropVisibleWithoutChangingOpaqueSurfaces() {
        for (dark in listOf(false, true)) {
            val tint = AppSurfaces.backdropTint(dark)
            val surface = AppSurfaces.surfaceContainer(dark)
            assertEquals(if (dark) 0.60f else 0.68f, tint.alpha, 0.005f)
            assertEquals(surface, tint.copy(alpha = 1f))
            assertEquals(1f, surface.alpha)
        }
    }

    @Test fun blurRequiresSettingHardwareAndRuntimeSupportIndependently() {
        for (setting in listOf(false, true)) for (hardware in listOf(false, true)) for (shader in listOf(false, true)) {
            assertEquals(setting && hardware && shader, canUseMapBlur(setting, hardware, shader))
        }
    }

    private fun contrast(a: Color, b: Color): Float {
        val first = a.luminance()
        val second = b.luminance()
        return (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
    }
}
