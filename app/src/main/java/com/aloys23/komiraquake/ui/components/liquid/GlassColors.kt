package com.aloys23.komiraquake.ui.components.liquid

import androidx.compose.ui.graphics.Color

/**
 * Neutral glass container tint shared by the floating bottom bar and every map panel:
 * near-black in dark, near-white in light. One source so the chrome and the panels never
 * drift apart in transparency or tint strength.
 */
fun glassContainerColor(dark: Boolean): Color =
    if (dark) Color(0xFF1F2124).copy(alpha = 0.45f) else Color.White.copy(alpha = 0.55f)

/**
 * Foreground for text/icons on glass. Crisp white in dark and near-black in light so it stays
 * legible over the translucent surface — the Miuix surface grays read as "washed" here.
 */
fun glassForegroundColor(dark: Boolean): Color =
    if (dark) Color.White else Color(0xFF1A1C1E)
