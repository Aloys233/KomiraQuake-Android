package com.aloys23.komiraquake.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.aloys23.komiraquake.data.prefs.ThemeMode
import com.aloys23.komiraquake.model.WarningLevel
import com.aloys23.komiraquake.ui.components.canUseMapBlur
import org.junit.Assert.*
import org.junit.Test
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

class AppThemeTest {
    @Test fun appSchemeUsesMiuixDefaultsExceptCriticalErrorRole() {
        for (dark in listOf(false, true)) {
            val base = if (dark) darkColorScheme() else lightColorScheme()
            val colors = appColorScheme(dark)
            // 主色/表面/文字角色全部沿用 Miuix 默认，不再覆盖为自建青绿令牌。
            assertEquals(base.primary, colors.primary)
            assertEquals(base.primaryVariant, colors.primaryVariant)
            assertEquals(base.primaryContainer, colors.primaryContainer)
            assertEquals(base.onPrimary, colors.onPrimary)
            assertEquals(base.onPrimaryVariant, colors.onPrimaryVariant)
            assertEquals(base.onSurfaceVariantActions, colors.onSurfaceVariantActions)
            assertEquals(base.background, colors.background)
            assertEquals(base.onBackground, colors.onBackground)
            assertEquals(base.surface, colors.surface)
            assertEquals(base.onSurface, colors.onSurface)
            assertEquals(base.surfaceVariant, colors.surfaceVariant)
            assertEquals(base.surfaceContainer, colors.surfaceContainer)
            assertEquals(base.surfaceContainerHigh, colors.surfaceContainerHigh)
            assertEquals(base.onSurfaceSecondary, colors.onSurfaceSecondary)
            assertEquals(base.outline, colors.outline)
            assertEquals(base.dividerLine, colors.dividerLine)
            assertEquals(base.disabledOnSurface, colors.disabledOnSurface)
            // 唯一保留的语义覆盖：error 角色表达「严重预警」。
            assertEquals(SeismicColors.severity(WarningLevel.CRITICAL, dark), colors.error)
            assertEquals(SeismicColors.on(SeismicColors.severity(WarningLevel.CRITICAL, dark)), colors.onError)
        }
    }

    @Test fun primaryUsesMiuixDefaultBlueInsteadOfTeal() {
        // Miuix 默认主色（见官方 Color System：light #3482FF / dark #277AF7）。
        assertEquals(Color(0xFF3482FF), appColorScheme(dark = false).primary)
        assertEquals(Color(0xFF277AF7), appColorScheme(dark = true).primary)
        assertNotEquals(Color(0xFF006B62), appColorScheme(dark = false).primary)
        assertNotEquals(Color(0xFF76D9CD), appColorScheme(dark = true).primary)
    }

    @Test fun opaqueSurfacesAreFullyOpaque() {
        for (dark in listOf(false, true)) {
            val colors = appColorScheme(dark)
            for (surface in listOf(colors.background, colors.surface, colors.surfaceContainer)) {
                assertEquals(1f, surface.alpha)
            }
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
