package com.aloys23.komiraquake.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.aloys23.komiraquake.data.prefs.ThemeMode
import com.aloys23.komiraquake.model.WarningLevel
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.aloys23.komiraquake.R
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import kotlin.math.roundToInt

/** 语义配色令牌。《NATIVE_PORT_SPEC》 §9。 */
object SeismicColors {
    val P_WAVE = Color(0xFF0288D1)
    val S_WAVE = Color(0xFFE65100)

    // 震中 X 十字（参考 kanameishi eqlistCross.svg）
    val HYPOCENTER_HALO = Color(0xFFFFF1AA)
    val HYPOCENTER_CROSS = Color(0xFFE21D1D)

    private val normalLight = Color(0xFF006874)
    private val normalDark = Color(0xFF4DDAD7)
    private val watchLight = Color(0xFF7A5900)
    private val watchDark = Color(0xFFFFBA28)
    private val warningLight = Color(0xFFBC2800)
    private val warningDark = Color(0xFFFF8C66)
    private val criticalLight = Color(0xFFBA1A1A)
    private val criticalDark = Color(0xFFFFB4AB)

    fun accent(dark: Boolean): Color = if (dark) normalDark else normalLight

    fun severity(level: WarningLevel, dark: Boolean): Color = when (level) {
        WarningLevel.NORMAL -> if (dark) normalDark else normalLight
        WarningLevel.WATCH -> if (dark) watchDark else watchLight
        WarningLevel.WARNING -> if (dark) warningDark else warningLight
        WarningLevel.CRITICAL -> if (dark) criticalDark else criticalLight
    }

    fun container(level: WarningLevel, dark: Boolean): Color =
        severity(level, dark).copy(alpha = 0.14f)

    // 校时状态（地图左下角时钟字体色）。《NATIVE_PORT_SPEC》 §9.2。
    fun clockSynced(dark: Boolean): Color = if (dark) Color(0xFF66BB6A) else Color(0xFF2E7D32)

    fun clockUnsynced(dark: Boolean): Color = if (dark) Color(0xFFEF5350) else Color(0xFFC62828)

    /** 中国地震烈度色阶（对齐 kanameishi CSIS 配色）。 */
    fun intensityColor(rawIntensity: Double): Color = when (val level = rawIntensity.roundToInt()) {
        1 -> Color(0xFF9F9F9F) // 灰
        2 -> Color(0xFFCFCFCF) // 浅灰
        3 -> Color(0xFF5FCFFF) // 天蓝
        4 -> Color(0xFF3FAFFF) // 蓝
        5 -> Color(0xFF5FDF8F) // 绿
        6 -> Color(0xFFF7E757) // 黄
        7 -> Color(0xFFFF8F00) // 橙
        8 -> Color(0xFFFF4F00) // 橙红
        9 -> Color(0xFFDF0F0F) // 红
        else -> if (level >= 10) Color(0xFF7F007F) else Color(0xFF9F9F9F) // 紫
    }

    /** JMA categories are discrete; never color them using the unrelated CSIS raw value. */
    fun jmaIntensityColor(text: String): Color {
        val level = when (text) {
        "0" -> 0.0
        "1" -> 1.0
        "2" -> 2.0
        "3" -> 3.0
        "4" -> 4.0
        "5弱", "5-" -> 5.0
        "5强", "5強", "5+" -> 6.0
        "6弱", "6-" -> 7.0
        "6强", "6強", "6+" -> 8.0
        "7" -> 10.0
        else -> return Color(0xFF657579)
        }
        return intensityColor(level)
    }

    fun magnitudeColor(magnitude: Double): Color = when {
        magnitude < 3.0 -> Color(0xFF00796B)
        magnitude < 4.5 -> Color(0xFFF57F17)
        magnitude < 6.0 -> Color(0xFFE64A19)
        else -> Color(0xFFC2185B)
    }

    fun on(bg: Color): Color = if (bg.luminance() < 0.179f) Color.White else Color(0xFF1A1C1E)
}

/** Neutral, opaque surfaces. Translucency is only applied by the map glass wrapper. */
object AppSurfaces {
    fun surface(dark: Boolean) = if (dark) Color(0xFF171B1D) else Color(0xFFF1F3F3)
    fun surfaceContainerLow(dark: Boolean) = if (dark) Color(0xFF1E2325) else Color(0xFFF8F9F9)
    fun surfaceContainer(dark: Boolean) = if (dark) Color(0xFF252B2D) else Color(0xFFFFFFFF)
    fun surfaceContainerHigh(dark: Boolean) = if (dark) Color(0xFF303739) else Color(0xFFE7ECEC)
    fun onSurface(dark: Boolean) = if (dark) Color(0xFFEBF0EF) else Color(0xFF20292C)
    fun outline(dark: Boolean) = if (dark) Color(0xFFADB9B9) else Color(0xFF566568)
    fun outlineVariant(dark: Boolean) = if (dark) Color(0xFF424D50) else Color(0xFFD4DEDE)
    fun accent(dark: Boolean) = if (dark) Color(0xFF76D9CD) else Color(0xFF006B62)
    fun accentContainer(dark: Boolean) = if (dark) Color(0xFF234840) else Color(0xFFDDEFEA)
    fun disabled(dark: Boolean) = if (dark) Color(0xFF829091) else Color(0xFF758285)
    fun backdropTint(dark: Boolean) = surfaceContainer(dark).copy(alpha = if (dark) 0.50f else 0.58f)
}

fun resolveDarkTheme(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** Every Miuix role is deliberately mapped: no blue defaults on switches or inputs. */
fun appColorScheme(dark: Boolean): Colors {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val accent = AppSurfaces.accent(dark)
    val ink = AppSurfaces.onSurface(dark)
    val secondary = AppSurfaces.outline(dark)
    val card = AppSurfaces.surfaceContainer(dark)
    val raised = AppSurfaces.surfaceContainerHigh(dark)
    val disabled = AppSurfaces.disabled(dark)
    val error = SeismicColors.severity(WarningLevel.CRITICAL, dark)
    return base.copy(
        primary = accent, onPrimary = SeismicColors.on(accent),
        primaryVariant = accent, onPrimaryVariant = SeismicColors.on(accent),
        primaryContainer = AppSurfaces.accentContainer(dark), onPrimaryContainer = accent,
        error = error, onError = SeismicColors.on(error),
        errorContainer = raised, onErrorContainer = error,
        disabledPrimary = raised, disabledOnPrimary = disabled,
        disabledPrimaryButton = raised, disabledOnPrimaryButton = disabled,
        disabledPrimarySlider = disabled,
        secondary = raised, onSecondary = ink,
        secondaryVariant = raised, onSecondaryVariant = ink,
        disabledSecondary = raised, disabledOnSecondary = disabled,
        disabledSecondaryVariant = raised, disabledOnSecondaryVariant = disabled,
        secondaryContainer = raised, onSecondaryContainer = ink,
        secondaryContainerVariant = raised, onSecondaryContainerVariant = secondary,
        tertiaryContainer = raised, onTertiaryContainer = ink, tertiaryContainerVariant = raised,
        background = AppSurfaces.surface(dark), onBackground = ink, onBackgroundVariant = secondary,
        surface = card, onSurface = ink, surfaceVariant = raised,
        onSurfaceSecondary = secondary, onSurfaceVariantSummary = secondary,
        onSurfaceVariantActions = accent, disabledOnSurface = disabled,
        surfaceContainer = card, onSurfaceContainer = ink, onSurfaceContainerVariant = secondary,
        surfaceContainerHigh = raised, onSurfaceContainerHigh = ink,
        surfaceContainerHighest = raised, onSurfaceContainerHighest = ink,
        outline = AppSurfaces.outlineVariant(dark), dividerLine = AppSurfaces.outlineVariant(dark),
        windowDimming = Color.Black.copy(alpha = 0.48f),
        sliderKeyPoint = secondary, sliderKeyPointForeground = SeismicColors.on(accent), sliderBackground = raised,
    )
}

/**
 * 打包的 Google Sans 静态拉丁子集（OFL，见 tools/import_google_sans.py）。
 * 不含 CJK，中文由系统字体回退；tnum 由 AppTypography/BadgeText 显式开启。
 */
val AppFontFamily = FontFamily(
    Font(R.font.google_sans_regular, FontWeight.Normal),
    Font(R.font.google_sans_medium, FontWeight.Medium),
    Font(R.font.google_sans_semibold, FontWeight.SemiBold),
    Font(R.font.google_sans_bold, FontWeight.Bold),
)

/** 拉丁用 Google Sans、中文回退系统 CJK；等宽数字让实时数据不抖动。 */
object AppTypography {
    fun style(size: Float, emphasis: Boolean = false) = TextStyle(
        fontFamily = AppFontFamily,
        fontSize = size.sp,
        lineHeight = (size * 1.4f).sp,
        fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal,
        fontFeatureSettings = "tnum",
    )
    val miuix = TextStyles(
        main = style(15f), paragraph = style(15f), body1 = style(15f), body2 = style(14f),
        button = style(14f, true), footnote1 = style(13f), footnote2 = style(12f),
        headline1 = style(17f, true), headline2 = style(16f, true), subtitle = style(14f),
        title1 = style(28f, true), title2 = style(24f, true), title3 = style(20f, true), title4 = style(18f, true),
    )
}

val LocalAppDark = staticCompositionLocalOf { false }
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun KomiraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = remember(darkTheme) { appColorScheme(darkTheme) }
    CompositionLocalProvider(LocalAppDark provides darkTheme) {
        MiuixTheme(colors = colors, textStyles = AppTypography.miuix, content = content)
    }
}
