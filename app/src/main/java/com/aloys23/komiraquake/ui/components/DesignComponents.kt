package com.aloys23.komiraquake.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aloys23.komiraquake.R
import com.aloys23.komiraquake.ui.theme.AppSurfaces
import com.aloys23.komiraquake.ui.theme.SeismicColors
import top.yukonga.miuix.kmp.basic.Switch

/** Official Lucide vectors only. Decorative icons use a null description. */
enum class AppIcon(@DrawableRes val resource: Int) {
    Activity(R.drawable.ic_activity), Map(R.drawable.ic_map), List(R.drawable.ic_list),
    Settings(R.drawable.ic_settings), Search(R.drawable.ic_search), Refresh(R.drawable.ic_refresh_cw),
    Back(R.drawable.ic_arrow_left), Forward(R.drawable.ic_arrow_right),
    ChevronLeft(R.drawable.ic_chevron_left), ChevronRight(R.drawable.ic_chevron_right), ChevronDown(R.drawable.ic_chevron_down),
    Plus(R.drawable.ic_plus), Minus(R.drawable.ic_minus), Locate(R.drawable.ic_locate_fixed),
    MapPin(R.drawable.ic_map_pin), Navigation(R.drawable.ic_navigation), Layers(R.drawable.ic_layers),
    Volume(R.drawable.ic_volume_2), Mute(R.drawable.ic_volume_x), Bell(R.drawable.ic_bell), BellOff(R.drawable.ic_bell_off),
    Close(R.drawable.ic_x), Check(R.drawable.ic_check), CircleCheck(R.drawable.ic_circle_check),
    CircleAlert(R.drawable.ic_circle_alert), Warning(R.drawable.ic_triangle_alert), Info(R.drawable.ic_info),
    Globe(R.drawable.ic_globe), Clock(R.drawable.ic_clock), Copy(R.drawable.ic_copy), Share(R.drawable.ic_share_2),
    ExternalLink(R.drawable.ic_external_link), Play(R.drawable.ic_play), Sun(R.drawable.ic_sun), Moon(R.drawable.ic_moon),
    Radio(R.drawable.ic_radio), Shield(R.drawable.ic_shield), Sliders(R.drawable.ic_sliders_horizontal),
    Eye(R.drawable.ic_eye), EyeOff(R.drawable.ic_eye_off), Maximize(R.drawable.ic_maximize_2),
}

@Composable
fun LucideIcon(icon: AppIcon, tint: Color, description: String? = null, modifier: Modifier = Modifier) {
    Image(painterResource(icon.resource), description, modifier.size(22.dp), colorFilter = ColorFilter.tint(tint))
}

@Composable
fun AppCard(dark: Boolean, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(AppSurfaces.surfaceContainer(dark))
            .border(1.dp, AppSurfaces.outlineVariant(dark).copy(alpha = 0.6f), RoundedCornerShape(20.dp))
            .padding(16.dp),
        content = content,
    )
}

@Composable
fun AppButton(
    text: String, dark: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    icon: AppIcon? = null, primary: Boolean = false, enabled: Boolean = true,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val pressed by interactions.collectIsPressedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val accent = AppSurfaces.accent(dark)
    val background = when {
        !enabled -> AppSurfaces.surfaceContainerHigh(dark)
        primary -> accent
        pressed || hovered -> AppSurfaces.accentContainer(dark)
        else -> AppSurfaces.surfaceContainerLow(dark)
    }
    val foreground = when {
        !enabled -> AppSurfaces.disabled(dark)
        primary -> SeismicColors.on(accent)
        else -> accent
    }
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.clip(shape).background(background)
            .border(if (focused) 2.dp else 1.dp, if (focused) accent else AppSurfaces.outlineVariant(dark), shape)
            .hoverable(interactions, enabled)
            .clickable(interactionSource = interactions, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { LucideIcon(it, foreground) }
        Label(text, foreground, 14.sp, bold = true, modifier = Modifier.weight(1f, fill = false))
    }
}

@Composable
fun AppIconButton(
    icon: AppIcon, description: String, dark: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, selected: Boolean = false, glass: Boolean = false,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val pressed by interactions.collectIsPressedAsState()
    val hovered by interactions.collectIsHoveredAsState()
    val shape = RoundedCornerShape(14.dp)
    val accent = AppSurfaces.accent(dark)
    val fill = if (selected || pressed || hovered) AppSurfaces.accentContainer(dark) else AppSurfaces.surfaceContainer(dark)
    val surface = if (glass && !selected && !pressed && !hovered) Modifier.mapGlass(dark, shape) else Modifier.background(fill, shape)
    Box(
        modifier.size(48.dp).clip(shape).then(surface)
            .border(if (focused) 2.dp else 1.dp, if (focused) accent else AppSurfaces.outlineVariant(dark), shape)
            .semantics { contentDescription = description }
            .hoverable(interactions, enabled)
            .clickable(interactionSource = interactions, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LucideIcon(icon, if (!enabled) AppSurfaces.disabled(dark) else if (selected) accent else AppSurfaces.onSurface(dark))
    }
}

@Composable
fun AppChip(text: String, selected: Boolean, dark: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val shape = RoundedCornerShape(14.dp)
    val accent = AppSurfaces.accent(dark)
    Row(
        modifier.clip(shape)
            .background(if (selected) AppSurfaces.accentContainer(dark) else AppSurfaces.surfaceContainerLow(dark))
            .border(1.dp, if (selected) accent else AppSurfaces.outlineVariant(dark), shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) LucideIcon(AppIcon.Check, accent, modifier = Modifier.size(16.dp))
        Label(text, if (!enabled) AppSurfaces.disabled(dark) else if (selected) accent else AppSurfaces.onSurface(dark), 13.sp,
            modifier = Modifier.weight(1f, fill = false))
    }
}

@Composable
fun SectionHeader(text: String, dark: Boolean, modifier: Modifier = Modifier) {
    Label(text, AppSurfaces.outline(dark), 13.sp, bold = true,
        modifier = modifier.padding(start = 4.dp, bottom = 8.dp).semantics { heading() })
}

@Composable
fun PageHeader(title: String, subtitle: String, icon: AppIcon, dark: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        LucideIcon(icon, AppSurfaces.accent(dark), modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f)) {
            Label(title, AppSurfaces.onSurface(dark), 26.sp, bold = true, modifier = Modifier.semantics { heading() })
            Label(subtitle, AppSurfaces.outline(dark), 13.sp)
        }
    }
}

@Composable
fun AppSwitchRow(title: String, checked: Boolean, dark: Boolean, onChange: (Boolean) -> Unit, summary: String? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Label(title, AppSurfaces.onSurface(dark), 14.sp)
            summary?.let { Label(it, AppSurfaces.outline(dark), 12.sp, modifier = Modifier.padding(top = 4.dp)) }
        }
        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
            Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.semantics { contentDescription = title })
        }
    }
}
