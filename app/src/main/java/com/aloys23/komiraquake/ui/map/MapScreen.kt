package com.aloys23.komiraquake.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aloys23.komiraquake.core.AppClock
import com.aloys23.komiraquake.core.CoordinateTransform
import com.aloys23.komiraquake.core.IntensityCalculator
import com.aloys23.komiraquake.core.TravelTimeService
import com.aloys23.komiraquake.model.EarthquakeEvent
import com.aloys23.komiraquake.model.WarningLevel
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.layout.onGloballyPositioned
import com.aloys23.komiraquake.ui.components.AppIcon
import com.aloys23.komiraquake.ui.components.AppIconButton
import com.aloys23.komiraquake.ui.components.LucideIcon
import com.aloys23.komiraquake.ui.components.Label
import com.aloys23.komiraquake.ui.components.LocalMapGlass
import com.aloys23.komiraquake.ui.components.mapGlass
import top.yukonga.miuix.kmp.blur.layerBackdrop
import com.aloys23.komiraquake.ui.theme.AppSurfaces
import com.aloys23.komiraquake.ui.theme.SeismicColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

private const val TILE_SIZE = 256
private const val WAVE_TABLE_SWITCH_KM = 2000.0
private const val WAVE_MAX_RADIUS_KM = 10000.0
private const val WAVE_WINDOW_MS = 60L * 60L * 1000L
/** 波前隐去的烈度阈值（CSIS I：可感下限）；超过该烈度对应的半径后波前渐隐。 */
private const val CSIS_FADE_LEVEL = 1.0

/** 视口外多取一圈瓦片，平移时新露出的区域已就绪，不会一格一格拼出来。 */
private const val TILE_PREFETCH_MARGIN = 1
/** 跟随时新瓦片淡入时长（ms）：掩盖跨层级切换的清晰度突变。 */
private const val TILE_FADE_MS = 180f
private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 18f

/** 捏合缩放阻尼：1.0 = 手指张开一倍即放大一级；小于 1 可降低灵敏度。 */
private const val ZOOM_SENSITIVITY = 0.8f
private val LN2 = ln(2.0)

/** 瓦片加载失败重试：最多 3 次尝试，退避 500ms / 1000ms。为冷缓存瓦片源（如 Petal 高层）兜底。 */
private const val TILE_MAX_ATTEMPTS = 3
private const val TILE_RETRY_BASE_DELAY_MS = 500L

/** 底图配置。《NATIVE_PORT_SPEC》 §8。 */
data class Basemap(
    val id: String,
    val name: String,
    val urlTemplate: String,
    val subdomains: List<String> = emptyList(),
    val isGcj02: Boolean = false,
    val maxZoom: Int = 18,
)

val AmapVector = Basemap(
    id = "amap_vector",
    name = "高德矢量",
    urlTemplate = "https://webrd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=7&x={x}&y={y}&z={z}",
    subdomains = listOf("1", "2", "3", "4"),
    isGcj02 = true,
)

val Osm = Basemap(
    id = "osm",
    name = "OpenStreetMap",
    urlTemplate = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
    maxZoom = 19,
)

/** 花瓣地图路网切片（自建瓦片服务，512px 2x，标准 zxy；境内为 GCJ-02）。 */
val Petal = Basemap(
    id = "petal",
    name = "Petal",
    urlTemplate = "https://tilemap.aloys23.link/petal/{z}/{x}/{y}",
    isGcj02 = true,
    maxZoom = 18,
)

// 与桌面端 MapView 保持一致：同样的四个底图，不含各端独有的项。
val Basemaps = listOf(AmapVector, Petal, Osm)

private fun worldSize(z: Int) = TILE_SIZE.toDouble() * 2.0.pow(z)
private fun projX(lon: Double, z: Int) = (lon + 180.0) / 360.0 * worldSize(z)
private fun projY(lat: Double, z: Int): Double {
    val rad = lat.coerceIn(-85.05112878, 85.05112878) * PI / 180.0
    val y = (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0
    return y * worldSize(z)
}
private fun unprojLon(x: Double, z: Int) = x / worldSize(z) * 360.0 - 180.0
private fun unprojLat(y: Double, z: Int): Double {
    val n = PI - 2.0 * PI * y / worldSize(z)
    return 180.0 / PI * atan(0.5 * (exp(n) - exp(-n)))
}

// 归一化世界坐标（[0,1]），与缩放级别无关：projX(lon,z) == normX(lon)·worldSize(z)。
private fun wrapLon(lon: Double) = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
private fun wrappedDelta(delta: Double) = delta - floor(delta + 0.5)
private fun normX(lon: Double) = (lon + 180.0) / 360.0
private fun normY(lat: Double): Double {
    val rad = lat.coerceIn(-85.05112878, 85.05112878) * PI / 180.0
    return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0
}

private data class TileRef(val x: Int, val y: Int, val z: Int, val px: Float, val py: Float, val size: Float, val url: String)

private fun tileUrl(basemap: Basemap, x: Int, y: Int, z: Int): String {
    var url = basemap.urlTemplate.replace("{x}", x.toString()).replace("{y}", y.toString()).replace("{z}", z.toString())
    if (url.contains("{s}")) {
        val sub = basemap.subdomains[(x + y) % basemap.subdomains.size]
        url = url.replace("{s}", sub)
    }
    return url
}

/**
 * 目标瓦片未就绪时，用已缓存的最近父瓦片放大顶替，避免出现空白块。
 * 只读 [TileLoader] 缓存，不触发网络。返回是否真的画了（用于判断淡入时是否有底可垫）。
 */
private fun DrawScope.drawParentTile(tile: TileRef, basemap: Basemap, loader: TileLoader, dst: IntSize): Boolean {
    var level = tile.z
    var px = tile.x
    var py = tile.y
    var factor = 1
    while (level > 0) {
        px /= 2
        py /= 2
        level--
        factor *= 2
        val parent = loader.peek(tileUrl(basemap, px, py, level)) ?: continue
        val sub = parent.width / factor
        if (sub <= 0) return false
        drawImage(
            image = parent,
            srcOffset = IntOffset((tile.x % factor) * sub, (tile.y % factor) * sub),
            srcSize = IntSize(sub, sub),
            dstOffset = IntOffset(tile.px.roundToInt(), tile.py.roundToInt()),
            dstSize = dst,
        )
        return true
    }
    return false
}

/** 反解走时表得到 P/S 波前半径（km）；超出量程记 -1（不画）。 */
private fun waveRadii(depthKm: Double, seconds: Double): Pair<Double, Double> {
    if (seconds < 0.0) return -1.0 to -1.0
    var p = TravelTimeService.distanceForTime(depthKm, seconds, true, TravelTimeService.DEFAULT_TABLE)
    var s = TravelTimeService.distanceForTime(depthKm, seconds, false, TravelTimeService.DEFAULT_TABLE)
    // jma2001 到量程上限会 clamp 回 2000 km，`>` 永远触发不了，故用 `>=` 才能切到 jb 表；
    // 端点处 jb 返回值与 jma2001 连续，不会跳变。
    if (p >= WAVE_TABLE_SWITCH_KM) p = TravelTimeService.distanceForTime(depthKm, seconds, true, "jb")
    if (s >= WAVE_TABLE_SWITCH_KM) s = TravelTimeService.distanceForTime(depthKm, seconds, false, "jb")
    // jb 表上限 10000 km 同样会 clamp，到端点即视为超出量程不再画。
    return (if (p >= WAVE_MAX_RADIUS_KM) -1.0 else p) to (if (s >= WAVE_MAX_RADIUS_KM) -1.0 else s)
}

/**
 * 聚焦取景半径（km）：有活跃波前时跟随较大的那一片并设 100 km 下限，避免 t≈0 时贴得过近；
 * P/S 全部隐藏（历史事件，或已淡出）时用固定 300 km。
 */
internal fun focusRadiusKm(pKm: Double, sKm: Double): Double =
    if (pKm < 0.0 && sKm < 0.0) 300.0 else maxOf(100.0, pKm, sKm)

/**
 * Compose 自绘栅格瓦片地图。《NATIVE_PORT_SPEC》 §8 / §12。
 * 同一时刻只画一个震中 X 十字（[focusEvent]）；"还年轻"的事件按 10 Hz 画出 P/S 波前圆。
 */
@Composable
fun MapScreen(
    userLat: Double?,
    userLon: Double?,
    dark: Boolean,
    basemap: Basemap,
    tileLoader: TileLoader,
    focusEvent: EarthquakeEvent?,
    /** 当前事件是否为活跃预警或用户显式焦点：只有此时才画 P/S 波前圆。 */
    waveEligible: Boolean = false,
    modifier: Modifier = Modifier,
    cameraRequest: Long = 0L,
    topOcclusion: androidx.compose.ui.unit.Dp = 310.dp,
    /** 波前出现/结束回调：供上层收起 HUD。 */
    onWavesStarted: () -> Unit = {},
    onWavesFinished: () -> Unit = {},
    /** 是否存在活动预警：空闲自动归位时优先回到预警震中而非我的位置。 */
    warningActive: Boolean = false,
    /** 用户操作地图后，无操作多久自动回到默认视野（我的位置 / 全国概览）。 */
    idleResetMs: Long = 20_000L,
) {
    val glass = LocalMapGlass.current
    DisposableEffect(glass) { onDispose { glass?.sourceReady = false } }
    var centerLat by remember { mutableStateOf(35.0) }
    var centerLon by remember { mutableStateOf(105.0) }
    var zoom by remember { mutableStateOf(6f) }

    // 镜头是否锁定在焦点事件上（浮动按钮的"跟随"选中态）。默认不跟随：无定位时地图是全国概览。
    var following by remember { mutableStateOf(false) }
    // 用户是否手动控制过镜头（拖动/缩放/定位/退出跟随），避免定位到达时抢镜头。
    var userMovedCamera by remember { mutableStateOf(false) }
    var initializedLocation by remember { mutableStateOf(false) }
    // 波前是否出现过：用于识别"波前消失"的时刻，自动归位并收起 HUD。
    var wavesShown by remember { mutableStateOf(false) }
    // 用户操作地图的计数器：每次变化即重置"空闲自动归位"计时。
    var interactionTick by remember { mutableStateOf(0) }
    // Ordinary fixes never interrupt a user's camera. Only use the first fix if idle.
    LaunchedEffect(userLat, userLon) {
        if (!initializedLocation && userLat != null && userLon != null) {
            initializedLocation = true
            if (!userMovedCamera && focusEvent == null) {
                val position = if (basemap.isGcj02) CoordinateTransform.wgs84ToGcj02(userLat, userLon) else userLat to userLon
                centerLat = position.first
                centerLon = position.second
                // 固定缩放：无定位时地图已按全国范围取景，那个级别不适合作为"聚焦我的位置"的默认值。
                zoom = 6f
                following = false
            }
        }
    }

    // 只有"还年轻"（发震在 60 min 内）的焦点/活跃事件才需要按帧重算波前半径。
    var waveNow by remember { mutableStateOf(AppClock.now()) }
    LaunchedEffect(focusEvent?.identity, focusEvent?.timestamp, waveEligible) {
        if (focusEvent == null || !waveEligible) return@LaunchedEffect
        do {
            waveNow = AppClock.now()
            // 跟随时按显示帧率刷新，波前缩放的跟随才够细腻（不在一格一格地跳）；
            // 未跟随时 100ms 足够画波前圆，避免整屏无谓重组。
            if (following) withFrameNanos { } else delay(100)
        } while (waveNow - focusEvent.timestamp <= WAVE_WINDOW_MS && !focusEvent.isCanceled)
    }

    val focus = focusEvent
    val waveSeconds = if (focus != null) (waveNow - focus.timestamp) / 1000.0 else -1.0
    // 波前"影响半径"：CSIS 降到可感下限（I）时的震中距。有意取代 kanameishi 的经验式
    // clamp(50·M², 200, 2000)。震级未知（<=0）时无法反解，退回量程上限，保证波前仍可见。
    val waveFadeKm = if (focus != null && focus.magnitude > 0.0) {
        IntensityCalculator.distanceForCsis(focus.magnitude, focus.depth, CSIS_FADE_LEVEL)
    } else {
        WAVE_MAX_RADIUS_KM
    }
    val (pKm, sKm) = if (waveEligible && focus != null && !focus.isCanceled && focus.timestamp > 0 && waveSeconds >= 0.0 &&
        waveNow - focus.timestamp <= WAVE_WINDOW_MS
    ) {
        val (p, s) = waveRadii(focus.depth, waveSeconds)
        // 透明度归零即视为隐藏：半径记 -1，取景与绘制都会跳过。
        fun visibleRadius(r: Double): Double =
            if (r > 0.0 && IntensityCalculator.waveOpacity(r, waveFadeKm) > 0.0) r else -1.0
        visibleRadius(p) to visibleRadius(s)
    } else {
        -1.0 to -1.0
    }
    val pOpacity = if (pKm > 0.0) IntensityCalculator.waveOpacity(pKm, waveFadeKm) else 0.0
    val sOpacity = if (sKm > 0.0) IntensityCalculator.waveOpacity(sKm, waveFadeKm) else 0.0
    // S 波径向渐变填充的不透明度：只在影响半径内可见（对齐 kanameishi 的 sWaveFill）。
    val sFillOpacity = if (sKm > 0.0) IntensityCalculator.waveFillOpacity(sKm, waveFadeKm) else 0.0

    BoxWithConstraints(modifier = modifier.clip(RoundedCornerShape(0.dp))) {
        val mapHeight = maxHeight
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val labelPaint = remember(dark, density) {
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = AppSurfaces.onSurface(dark).toArgb()
                textSize = with(density) { 12.sp.toPx() }
                setShadowLayer(3f, 0f, 0f, AppSurfaces.surface(dark).toArgb())
            }
        }

        val topInsetPx = with(density) { topOcclusion.toPx() }.coerceAtMost(heightPx * 0.75f)
        val bottomInsetPx = with(density) { 64.dp.toPx() }
        val sideInsetPx = with(density) { 72.dp.toPx() }

        /** 无定位、无焦点时按中国版图范围取景（大陆 + 海南 + 台湾），取代原先写死的 zoom 6。 */
        fun fitChina() {
            val lonMin = 73.5; val lonMax = 135.1; val latMin = 18.0; val latMax = 53.6
            val x0 = normX(lonMin); val x1 = normX(lonMax)
            val y0 = normY(latMax); val y1 = normY(latMin)
            val availableW = (widthPx - 2 * sideInsetPx).coerceAtLeast(80f)
            val availableH = (heightPx - topInsetPx - bottomInsetPx).coerceAtLeast(80f)
            val scale = minOf(availableW / (x1 - x0), availableH / (y1 - y0))
            // 取整到整数层级（对齐 Leaflet fitBounds 的 floor），静止时瓦片 1:1 渲染、无缩放拉伸。
            zoom = floor(ln(scale / TILE_SIZE) / LN2).toFloat().coerceIn(MIN_ZOOM, minOf(MAX_ZOOM, basemap.maxZoom.toFloat()))
            val world = TILE_SIZE * 2.0.pow(zoom.toDouble())
            val targetY = topInsetPx + availableH / 2.0
            centerLon = wrapLon((x0 + x1) / 2.0 * 360.0 - 180.0)
            centerLat = unprojLat(((y0 + y1) / 2.0 - (targetY - heightPx / 2.0) / world).coerceIn(0.0, 1.0) * worldSize(0), 0)
            following = false
        }

        /** 默认视野：有定位则聚焦用户，否则全国概览。用于退出跟随 / 取消焦点。 */
        fun resetToDefaultView() {
            following = false
            if (userLat != null && userLon != null) {
                val p = if (basemap.isGcj02) CoordinateTransform.wgs84ToGcj02(userLat, userLon) else userLat to userLon
                centerLat = p.first
                centerLon = p.second
                zoom = 6f
            } else {
                fitChina()
            }
        }

        fun fitFocus() {
            val event = focusEvent
            if (event == null) {
                // 无焦点：回到默认视野。
                resetToDefaultView()
                return
            }
            fun position(lat: Double, lon: Double) =
                if (basemap.isGcj02) CoordinateTransform.wgs84ToGcj02(lat, lon) else lat to lon
            val (lat, lon) = position(event.latitude, event.longitude)
            val ex = normX(lon)
            val ey = normY(lat)
            // 只框震中区域，不并入用户所在地；半径随 P/S 波前扩大（100km 下限 / 无波 300km）。
            val radiusKm = focusRadiusKm(pKm, sKm)
            val radius = radiusKm / (40075.0 * cos(lat * PI / 180.0).coerceAtLeast(0.05))
            val left = ex - radius
            val right = ex + radius
            val top = ey - radius
            val bottom = ey + radius
            val availableW = (widthPx - 2 * sideInsetPx).coerceAtLeast(80f)
            val availableH = (heightPx - topInsetPx - bottomInsetPx).coerceAtLeast(80f)
            val scale = minOf(availableW / (right - left), availableH / (bottom - top))
            // 无级缩放：不取整，随波前连续变化（瓦片渲染器本就支持非整数 zoom）。
            zoom = (ln(scale / TILE_SIZE) / LN2).toFloat().coerceIn(MIN_ZOOM, minOf(MAX_ZOOM, basemap.maxZoom.toFloat()))
            val world = TILE_SIZE * 2.0.pow(zoom.toDouble())
            val targetY = topInsetPx + availableH / 2.0
            centerLon = wrapLon((left + right) / 2.0 * 360.0 - 180.0)
            centerLat = unprojLat(((top + bottom) / 2.0 - (targetY - heightPx / 2.0) / world).coerceIn(0.0, 1.0) * worldSize(0), 0)
            following = true
        }
        LaunchedEffect(focusEvent?.identity, cameraRequest) { fitFocus() }
        // 波前每 ~100ms 变化一次；仅在跟随中重取景，用户拖动后 following=false 自动停止。
        LaunchedEffect(waveNow) { if (following) fitFocus() }
        LaunchedEffect(widthPx, heightPx, topOcclusion) {
            if (following) fitFocus()
            else if (focusEvent == null && !userMovedCamera) fitChina()
        }
        // 波前全部消失（走完/淡出/事件结束）：若仍在跟随震中，自动回到我的位置（无定位则全国概览），
        // 并通知上层收起 HUD。用户已手动操作过镜头（following=false）时不抢镜头。
        val hasWaves = pKm > 0.0 || sKm > 0.0
        LaunchedEffect(hasWaves) {
            if (hasWaves) {
                wavesShown = true
                onWavesStarted()
            } else if (wavesShown) {
                wavesShown = false
                if (following) resetToDefaultView()
                onWavesFinished()
            }
        }
        // 空闲自动归位：用户拖动/缩放后一段时间无操作且未在跟随事件，则回到默认视野。
        LaunchedEffect(interactionTick) {
            if (interactionTick == 0) return@LaunchedEffect
            delay(idleResetMs)
            if (!following) {
                // 有活动预警时归位到预警震中并重新跟随；否则回到我的位置（无定位则全国概览）。
                if (warningActive && focusEvent != null) fitFocus() else resetToDefaultView()
            }
        }

        val tileZoom = zoom.roundToInt().coerceIn(MIN_ZOOM.toInt(), basemap.maxZoom)
        val tileScale = 2f.pow(zoom - tileZoom)
        val originX = projX(centerLon, tileZoom) * tileScale - widthPx / 2.0
        val originY = projY(centerLat, tileZoom) * tileScale - heightPx / 2.0
        val scaledTile = TILE_SIZE * tileScale
        val n = 2.0.pow(tileZoom).toInt()

        val tiles = remember(tileZoom, centerLat, centerLon, zoom, widthPx, heightPx, basemap.id) {
            val list = ArrayList<TileRef>()
            val minX = floor(originX / scaledTile).toInt() - TILE_PREFETCH_MARGIN
            val maxX = floor((originX + widthPx) / scaledTile).toInt() + TILE_PREFETCH_MARGIN
            val minY = floor(originY / scaledTile).toInt() - TILE_PREFETCH_MARGIN
            val maxY = floor((originY + heightPx) / scaledTile).toInt() + TILE_PREFETCH_MARGIN
            for (tx in minX..maxX) {
                val wrappedX = ((tx % n) + n) % n
                for (ty in minY..maxY) {
                    if (ty < 0 || ty >= n) continue
                    list.add(
                        TileRef(
                            x = wrappedX, y = ty, z = tileZoom,
                            px = (tx * scaledTile - originX).toFloat(),
                            py = (ty * scaledTile - originY).toFloat(),
                            size = scaledTile + 1f,
                            url = tileUrl(basemap, wrappedX, ty, tileZoom),
                        ),
                    )
                }
            }
            list
        }

        val images = remember { mutableStateMapOf<String, ImageBitmap>() }
        val pending = remember { mutableSetOf<String>() }
        // 每个瓦片首次出现的时刻（ms），用于淡入（见绘制处的 alpha）。
        val tileAppearAt = remember { mutableStateMapOf<String, Long>() }
        val loadScope = rememberCoroutineScope()
        // 瓦片淡入的逐帧时钟：仅在跟随时或仍有瓦片处于淡入窗口内时按帧推进，
        // 空闲时降到 100ms 轮询，避免常驻 60Hz 重组。手动缩放/平移也能平滑淡入。
        var frameNow by remember { mutableStateOf(AppClock.now()) }
        LaunchedEffect(Unit) {
            while (true) {
                val fading = tileAppearAt.values.any { frameNow - it < TILE_FADE_MS.toLong() }
                if (following || fading) {
                    withFrameNanos { }
                    frameNow = AppClock.now()
                } else {
                    delay(100)
                }
            }
        }
        LaunchedEffect(tiles) {
            val wanted = tiles.mapTo(HashSet()) { it.url }
            // 丢弃移出视野的瓦片，避免状态表无限增长（解码位图仍由 TileLoader 的 LRU 持有）。
            images.keys.toList().forEach {
                if (it !in wanted) {
                    images.remove(it)
                    tileAppearAt.remove(it)
                }
            }
            for (tile in tiles) {
                val url = tile.url
                if (images.containsKey(url) || !pending.add(url)) continue
                // 用 composition 作用域而非本 effect：相机移动不会取消已在途的下载。
                loadScope.launch {
                    // 冷缓存瓦片首访可能超时/被重置：失败后退避重试，用尽次数才放弃。
                    // 放弃后本轮不再重试；瓦片移出再进入视野时由 LaunchedEffect 重新发起。
                    var attempt = 0
                    while (true) {
                        val bmp = tileLoader.load(url)
                        if (bmp != null) {
                            images[url] = bmp
                            if (!tileAppearAt.containsKey(url)) tileAppearAt[url] = AppClock.now()
                            break
                        }
                        attempt++
                        if (attempt >= TILE_MAX_ATTEMPTS) break
                        delay(TILE_RETRY_BASE_DELAY_MS shl (attempt - 1))
                    }
                    pending.remove(url)
                }
            }
        }

        val gcjShift = basemap.isGcj02
        fun shift(lat: Double, lon: Double) =
            if (gcjShift) CoordinateTransform.wgs84ToGcj02(lat, lon) else lat to lon
        fun screenX(lon: Double): Float = (widthPx / 2.0 + wrappedDelta(normX(lon) - normX(centerLon)) * worldSize(tileZoom) * tileScale).toFloat()
        fun screenY(lat: Double): Float = (projY(lat, tileZoom) * tileScale - originY).toFloat()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppSurfaces.surfaceContainerLow(dark))
                .pointerInput(basemap.id) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        following = false
                        userMovedCamera = true
                        interactionTick++
                        val viewW = size.width.toFloat()
                        val viewH = size.height.toFloat()

                        // 屏上尺度：worldSize(z)·tileScale == TILE_SIZE·2^zoom，与 z 的取整无关。
                        val dZoom = (ln(gestureZoom.toDouble()) / LN2 * ZOOM_SENSITIVITY).toFloat()
                        val newZoom = (zoom + dZoom).coerceIn(MIN_ZOOM, minOf(MAX_ZOOM, basemap.maxZoom.toFloat()))
                        val kOld = TILE_SIZE * 2f.pow(zoom)
                        val kNew = TILE_SIZE * 2f.pow(newZoom)

                        // 以手势焦点为锚点缩放：手指按住的地理位置在缩放前后保持不动，
                        // 否则地图会绕屏幕中心放大，产生"滑走 / 不听使唤"的手感。
                        // 该点在屏上的新位置 = 焦点 + 焦点位移·缩放比（内容跟随手指）。
                        val anchorX = normX(centerLon) + (centroid.x - viewW / 2f) / kOld
                        val anchorY = normY(centerLat) + (centroid.y - viewH / 2f) / kOld
                        val focusX = centroid.x + pan.x * gestureZoom
                        val focusY = centroid.y + pan.y * gestureZoom
                        val centerNx = anchorX - (focusX - viewW / 2f) / kNew
                        val centerNy = anchorY - (focusY - viewH / 2f) / kNew

                        val z = newZoom.roundToInt().coerceIn(MIN_ZOOM.toInt(), basemap.maxZoom)
                        val world = worldSize(z)
                        centerLon = wrapLon(unprojLon(centerNx * world, z))
                        centerLat = unprojLat(centerNy.coerceIn(0.0, 1.0) * world, z)
                        zoom = newZoom
                    }
                },
        ) {
            // ONLY tiles, seismic geometry and the user marker enter the shared backdrop.
            // HUD, legend, controls and scale are siblings outside this capture boundary.
            Box(Modifier.fillMaxSize()
                .then(if (glass?.enabled == true) Modifier.layerBackdrop(glass.backdrop) else Modifier)
                .background(AppSurfaces.surfaceContainerLow(dark))
                .onGloballyPositioned { if (glass?.enabled == true) glass.sourceReady = true }) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                for (tile in tiles) {
                    val dst = IntSize(tile.size.roundToInt(), tile.size.roundToInt())
                    val bmp = images[tile.url]
                    if (bmp == null) {
                        drawParentTile(tile, basemap, tileLoader, dst)
                        continue
                    }
                    // 新瓦片淡入，掩盖跨层级清晰度突变造成的顿挫。
                    // 底下有父瓦片可垫时才淡入（否则半透明会露出底色）；否则直接显示。
                    val appearAt = tileAppearAt[tile.url]
                    val alpha = if (appearAt != null) {
                        ((frameNow - appearAt).toFloat() / TILE_FADE_MS).coerceIn(0f, 1f)
                    } else {
                        1f
                    }
                    if (alpha < 1f && drawParentTile(tile, basemap, tileLoader, dst)) {
                        drawImage(
                            image = bmp,
                            dstOffset = IntOffset(tile.px.roundToInt(), tile.py.roundToInt()),
                            dstSize = dst,
                            alpha = alpha,
                        )
                    } else {
                        drawImage(
                            image = bmp,
                            dstOffset = IntOffset(tile.px.roundToInt(), tile.py.roundToInt()),
                            dstSize = dst,
                        )
                    }
                }

                val event = focus ?: return@Canvas
                val (fLatD, fLonD) = shift(event.latitude, event.longitude)
                val fx = screenX(fLonD)
                val fy = screenY(fLatD)

                // px per km。墨卡托保角，水平/垂直同尺度：worldPx/360° ÷ (111.32·cosφ) km/°。
                val cosLat = cos(event.latitude * PI / 180.0).coerceAtLeast(0.01)
                val pxPerKm = (TILE_SIZE * 2.0.pow(tileZoom) * tileScale / 360.0) / (111.32 * cosLat)

                // P/S 波前圆：反解走时表得到半径（km），-1 表示不画。
                if (pKm > 0.0) {
                    drawCircle(
                        color = SeismicColors.P_WAVE.copy(alpha = pOpacity.toFloat()),
                        radius = (pKm * pxPerKm).toFloat(),
                        center = Offset(fx, fy),
                        style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))),
                    )
                    drawContext.canvas.nativeCanvas.drawText("P 纵波", fx + 8f, fy - (pKm * pxPerKm).toFloat() - 6f, labelPaint)
                }
                // S 波径向渐变填充：中心透明、边缘着色，衬在描边之下（对齐 kanameishi 的 sWaveFill）。
                if (sKm > 0.0 && sFillOpacity > 0.0) {
                    val fillRadius = (sKm * pxPerKm).toFloat()
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.Transparent, SeismicColors.S_WAVE),
                            center = Offset(fx, fy),
                            radius = fillRadius,
                        ),
                        radius = fillRadius,
                        center = Offset(fx, fy),
                        alpha = sFillOpacity.toFloat(),
                    )
                }
                if (sKm > 0.0) {
                    drawCircle(
                        color = SeismicColors.S_WAVE.copy(alpha = sOpacity.toFloat()),
                        radius = (sKm * pxPerKm).toFloat(),
                        center = Offset(fx, fy),
                        style = Stroke(width = 2f),
                    )
                    drawContext.canvas.nativeCanvas.drawText("S 横波", fx + 8f, fy + (sKm * pxPerKm).toFloat() + 18f, labelPaint)
                }

                // 震中 X 十字：10px 米黄描边打底 + 6px 红描边，圆头。
                val d = with(density) { 15.dp.toPx() }
                val haloWidth = with(density) { 10.dp.toPx() }
                val crossWidth = with(density) { 6.dp.toPx() }
                fun cross(color: androidx.compose.ui.graphics.Color, width: Float) {
                    drawLine(color, Offset(fx - d, fy - d), Offset(fx + d, fy + d), width, StrokeCap.Round)
                    drawLine(color, Offset(fx + d, fy - d), Offset(fx - d, fy + d), width, StrokeCap.Round)
                }
                cross(SeismicColors.HYPOCENTER_HALO, haloWidth)
                cross(SeismicColors.HYPOCENTER_CROSS, crossWidth)
            }

            // 用户位置
            if (userLat != null && userLon != null) {
                val (lat, lon) = shift(userLat, userLon)
                val x = screenX(lon)
                val y = screenY(lat)
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (x - with(density) { 19.dp.toPx() }).roundToInt(),
                                (y - with(density) { 19.dp.toPx() }).roundToInt(),
                            )
                        }
                        .size(38.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(SeismicColors.accent(dark).copy(alpha = 0.18f)),
                    )
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(SeismicColors.accent(dark)),
                    )
                }
            }

            } // end map-only backdrop source

            // Foreground controls remain sharp and are never sampled.
            Column(
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 76.dp)
                    .heightIn(max = (mapHeight - topOcclusion - 88.dp).coerceAtLeast(48.dp))
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 放大（整数层级 ±1，和桌面端滚轮一致）
                MapFloatingButton(AppIcon.Plus, dark, "放大地图", zoom < minOf(MAX_ZOOM, basemap.maxZoom.toFloat())) {
                    following = false
                    userMovedCamera = true
                    interactionTick++
                    val target = (zoom.roundToInt() + 1).toFloat()
                        .coerceAtMost(minOf(MAX_ZOOM, basemap.maxZoom.toFloat()))
                    zoom = target
                }
                // 缩小
                MapFloatingButton(AppIcon.Minus, dark, "缩小地图", zoom > MIN_ZOOM) {
                    following = false
                    userMovedCamera = true
                    interactionTick++
                    val target = (zoom.roundToInt() - 1).toFloat().coerceAtLeast(MIN_ZOOM)
                    zoom = target
                }
                // 回到我的位置
                if (userLat != null && userLon != null) {
                    MapFloatingButton(AppIcon.Locate, dark, "定位到我的位置") {
                        following = false
                        userMovedCamera = true
                        interactionTick++
                        val position = shift(userLat, userLon)
                        centerLat = position.first
                        centerLon = position.second
                        zoom = 6f
                    }
                }
                // 回到震中 / 退出跟随
                if (focus != null) {
                    MapFloatingButton(AppIcon.Navigation, dark,
                        if (following) "正在跟随事件；点击退出跟随" else "恢复事件视野，聚焦震中区域",
                        selected = following) {
                        if (following) resetToDefaultView() else fitFocus()
                    }
                }
            }

            // 右下角动态比例尺
            val scaleCosLat = cos(centerLat * PI / 180.0).coerceAtLeast(0.01)
            val currentPxPerKm = (TILE_SIZE * 2.0.pow(tileZoom) * tileScale / 360.0) / (111.32 * scaleCosLat)
            val targetKm = with(density) { 70.dp.toPx() } / currentPxPerKm
            val scaleKm = listOf(0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0)
                .minBy { kotlin.math.abs(ln(it / targetKm)) }
            val scaleBarWidth = with(density) { (scaleKm * currentPxPerKm).toFloat().toDp() }

            Box(
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomEnd)
                    .padding(bottom = 14.dp, end = 14.dp)
                    .mapGlass(dark, RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    com.aloys23.komiraquake.ui.components.Label(
                        if (scaleKm < 1) "${(scaleKm * 1000).roundToInt()} m" else "${scaleKm.roundToInt()} km",
                        AppSurfaces.onSurface(dark),
                        size = 10.sp,
                    )
                    Spacer(Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .width(scaleBarWidth)
                            .height(3.dp)
                            .background(AppSurfaces.onSurface(dark)),
                    )
                }
            }
        }
    }
}

@Composable
private fun MapFloatingButton(icon: AppIcon, dark: Boolean, description: String, enabled: Boolean = true,
    selected: Boolean = false, onClick: () -> Unit) {
    AppIconButton(icon, description, dark, onClick, enabled = enabled, selected = selected, glass = true)
}

/** Data marks, not font glyphs: the hypocenter cross and the user location dot. */
@Composable
fun MapLegend(dark: Boolean) {
    FlowRow(Modifier.mapGlass(dark, RoundedCornerShape(14.dp)).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("震中", "本地").forEachIndexed { index, label ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Canvas(Modifier.size(16.dp)) {
                    val center = Offset(size.width / 2, size.height / 2)
                    when (index) {
                        0 -> {
                            listOf(SeismicColors.HYPOCENTER_HALO to 5.dp.toPx(), SeismicColors.HYPOCENTER_CROSS to 2.dp.toPx()).forEach { (color, width) ->
                                drawLine(color, Offset(3.dp.toPx(), 3.dp.toPx()), Offset(size.width - 3.dp.toPx(), size.height - 3.dp.toPx()), width, StrokeCap.Round)
                                drawLine(color, Offset(size.width - 3.dp.toPx(), 3.dp.toPx()), Offset(3.dp.toPx(), size.height - 3.dp.toPx()), width, StrokeCap.Round)
                            }
                        }
                        else -> drawCircle(SeismicColors.accent(dark), 4.dp.toPx(), center)
                    }
                }
                Label(label, AppSurfaces.onSurface(dark), 12.sp)
            }
        }
    }
}

/** 供设置页展示。 */
fun basemapById(id: String): Basemap = Basemaps.firstOrNull { it.id == id } ?: AmapVector
