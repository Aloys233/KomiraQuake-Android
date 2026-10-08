package com.aloys23.komiraquake.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
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
 * Miuix maps each consumer's local bounds into the captured map layer. Its native
 * blur expands the sample before clipping to the shape. Content is drawn AFTER
 * the filtered backdrop and tint, so text and controls remain sharp. Unattached,
 * disabled and software-rendered sources always use an entirely opaque surface.
 */
@Composable
fun Modifier.mapGlass(dark: Boolean, shape: Shape = RoundedCornerShape(20.dp)): Modifier {
    val glass = LocalMapGlass.current
    val scheme = MiuixTheme.colorScheme
    val surface = scheme.surfaceContainer
    val tint = scheme.surfaceContainer.copy(alpha = if (dark) 0.50f else 0.58f)
    val radius = with(LocalDensity.current) { 20.dp.toPx() }
    val backdropModifier = if (glass != null && glass.enabled && glass.sourceReady) {
        Modifier.drawBackdrop(
            backdrop = glass.backdrop,
            shape = { shape },
            effects = { blur(radiusX = radius, radiusY = radius) },
        ).background(tint, shape)
    } else {
        Modifier.background(surface, shape)
    }
    return this.clip(shape).then(backdropModifier)
        .border(1.dp, scheme.dividerLine.copy(alpha = 0.75f), shape)
}
