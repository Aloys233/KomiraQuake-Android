package com.aloys23.komiraquake.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.aloys23.komiraquake.ui.components.liquid.glassContainerColor
import com.aloys23.komiraquake.ui.components.liquid.lens
import com.aloys23.komiraquake.ui.components.liquid.vibrancy
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.BloomStroke
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.LightPosition
import top.yukonga.miuix.kmp.blur.highlight.LightSource
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** One source owned by QuakeApp. Never attach layerBackdrop to a glass consumer. */
@Stable
class MapGlassState(val backdrop: LayerBackdrop, val enabled: Boolean) {
    var sourceReady by mutableStateOf(false)
}

val LocalMapGlass = staticCompositionLocalOf<MapGlassState?> { null }

/** Kept separate from reduceMotion: neither setting silently changes the other. */
fun canUseMapBlur(enabled: Boolean, hardwareAccelerated: Boolean, shaderSupported: Boolean): Boolean =
    enabled && hardwareAccelerated && shaderSupported

/**
 * Specular edge shared with the floating bottom bar so panels and chrome read as one material.
 * Static (no tilt input) because map panels are many and must stay cheap.
 */
private val mapGlassSpecular: Highlight = Highlight(
    width = 1.dp,
    alpha = 1f,
    style = BloomStroke(
        color = Color.White.copy(alpha = 0.12f),
        innerBlurRadius = 2.0.dp,
        primaryLight = LightSource(
            position = LightPosition(0.5f, -0.3f, -0.05f),
            color = Color.White,
            intensity = 1f,
        ),
        secondaryLight = LightSource(
            position = LightPosition(0.5f, 0.8f, -0.5f),
            color = Color.White,
            intensity = 0.4f,
        ),
        dualPeak = true,
    ),
)

/**
 * Liquid glass for map panels, mirroring the floating bottom bar: a soft drop shadow for depth,
 * vibrancy + a light blur + edge refraction (lens) over the captured map layer, a tinted surface
 * drawn behind the content and a specular edge. No stroke — the bar has none either.
 *
 * Refraction is scaled to the panel's smaller side and capped so a small badge and the large HUD
 * card both stay legible. Content is drawn AFTER the filtered backdrop, tint and specular, so text
 * and controls stay sharp. Unattached, disabled and software-rendered sources use an opaque surface.
 */
@Composable
fun Modifier.mapGlass(dark: Boolean, shape: Shape = RoundedCornerShape(20.dp)): Modifier {
    val glass = LocalMapGlass.current
    val scheme = MiuixTheme.colorScheme
    val surface = scheme.surfaceContainer
    val tint = glassContainerColor(dark)
    val blurRadius = with(LocalDensity.current) { 4.dp.toPx() }
    val specular = mapGlassSpecular.copy(alpha = 0.75f)
    return this.then(
        if (glass != null && glass.enabled && glass.sourceReady) {
            Modifier.drawBackdrop(
                backdrop = glass.backdrop,
                shape = { shape },
                effects = {
                    val refraction = (size.minDimension * 0.45f).coerceIn(8.dp.toPx(), 24.dp.toPx())
                    padding = maxOf(padding, 40.dp.toPx())
                    vibrancy()
                    blur(radiusX = blurRadius, radiusY = blurRadius)
                    lens(refractionHeight = refraction, refractionAmount = refraction)
                },
                highlight = { specular },
                onDrawSurface = { drawRect(tint) },
            )
        } else {
            Modifier.background(surface, shape)
        },
    )
}
