// Adapted from Kyant0/AndroidLiquidGlass (Apache 2.0), mirrored from the compose-miuix-ui example.
package com.aloys23.komiraquake.ui.components.liquid

import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.colorControls

/** Boosts saturation only — the "vibrancy" pass Apple's material applies under glass. */
fun BackdropEffectScope.vibrancy() {
    colorControls(
        brightness = 0f,
        contrast = 1f,
        saturation = 1.5f,
    )
}
