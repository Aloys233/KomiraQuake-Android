package com.aloys23.komiraquake.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aloys23.komiraquake.R
import com.aloys23.komiraquake.ui.theme.AppTypography
import top.yukonga.miuix.kmp.basic.Text

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

/** Lucide 线性图标（spec §9.7）：本地矢量 + 统一着色。 */
@Composable
fun LucideIcon(icon: AppIcon, tint: Color, description: String? = null, modifier: Modifier = Modifier) {
    Image(painterResource(icon.resource), description, modifier.size(22.dp), colorFilter = ColorFilter.tint(tint))
}

/**
 * 文本适配器：底层用 Miuix [Text]，保留应用字体与等宽数字（tnum），颜色由调用方按 Miuix 令牌传入。
 */
@Composable
fun Label(text: String, color: Color, size: TextUnit = 14.sp, bold: Boolean = false,
    maxLines: Int = Int.MAX_VALUE, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = AppTypography.style(size.value.coerceAtLeast(12f), bold),
    )
}
