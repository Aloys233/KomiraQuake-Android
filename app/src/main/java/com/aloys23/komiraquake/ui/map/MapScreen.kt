package com.aloys23.komiraquake.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aloys23.komiraquake.core.AppClock
import com.aloys23.komiraquake.core.CoordinateTransform
import com.aloys23.komiraquake.core.IntensityCalculator
import com.aloys23.komiraquake.core.TravelTimeService
import com.aloys23.komiraquake.model.EarthquakeEvent
import com.aloys23.komiraquake.model.WarningLevel
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.onGloballyPositioned
import com.aloys23.komiraquake.ui.components.AppIcon
import com.aloys23.komiraquake.ui.components.LucideIcon
import com.aloys23.komiraquake.ui.components.Label
import com.aloys23.komiraquake.ui.components.LocalMapGlass
import com.aloys23.komiraquake.ui.components.liquid.glassForegroundColor
import com.aloys23.komiraquake.ui.components.mapGlass
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.blur.layerBackdrop
import com.aloys23.komiraquake.ui.theme.LocalReduceMotion
import com.aloys23.komiraquake.ui.theme.SeismicColors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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

/** 视口外预取一圈瓦片；手势期间优先保证当前视口，避免预取请求挡住可见内容。 */
private const val TILE_PREFETCH_MARGIN = 1
/** 跟随时新瓦片淡入时长（ms）：掩盖跨层级切换的清晰度突变。 */
private const val TILE_FADE_MS = 180f
/** 缩小兜底：最多向更细层钻取的层级数（下采样顶替）。兼顾快速捏合时跳过的层级。 */
private const val TILE_FALLBACK_CHILD_LEVELS = 3
/** 放大兜底：最多向更粗层钻取的层级数。高层级时逐级探到 0 会造成大量无谓的缓存查询。 */
private const val TILE_FALLBACK_PARENT_LEVELS = 6
/** 兜底子瓦片的最小屏幕边长（px）：太小就不值得画，避免无谓的缓存查询。 */
private const val TILE_FALLBACK_MIN_SUBPX = 24f
/** 跨层底衬最长保留时长（ms）：新层迟迟不齐（弱网/持续失败）时也不无限留着旧层。 */
private const val UNDERLAY_MAX_MS = 1500L
private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 18f
/** 用户定位默认镜头：比全国概览（zoom 6）近一级，便于确认当前位置。 */
internal const val USER_LOCATION_ZOOM = 7f

/** 捏合缩放阻尼：1.0 = 手指张开一倍即放大一级；小于 1 可降低灵敏度。 */
private const val ZOOM_SENSITIVITY = 0.8f
private val LN2 = ln(2.0)

/** 程序化镜头补间时长（ms）：对齐桌面端 cameraDuration。 */
private const val CAMERA_TWEEN_MS = 420L
/** 缩放按钮步进补间时长（ms）：对齐桌面端 zoomDuration。 */
private const val ZOOM_TWEEN_MS = 180L
/** 波前半径显示平滑时长（ms）：对齐桌面端 Behavior，掩盖目标值的台阶。 */
private const val WAVE_SMOOTH_MS = 120.0

/** 目标镜头（中心经纬度 + 缩放）。 */
private data class Camera(val lat: Double, val lon: Double, val zoom: Float)

/**
 * 自动取景的输入快照。
 *
 * 事件 identity 在实时报次之间保持不变，但震中坐标/发震时刻可能被修正；同时 Compose
 * 首帧的视口和 HUD 遮挡值可能还未稳定。它们都必须让取景副作用重新计算目标 zoom，不能
 * 只依赖 cameraRequest（cameraRequest 只在显式聚焦时递增）。
 */
internal data class FocusCameraKey(
    val identity: String?,
    val timestamp: Long?,
    val latitude: Double?,
    val longitude: Double?,
    val magnitude: Double?,
    val depth: Double?,
    val request: Long,
    val hasFocus: Boolean,
    val widthPx: Float,
    val heightPx: Float,
    val topOcclusionPx: Float,
)

internal fun focusCameraKey(
    event: EarthquakeEvent?,
    request: Long,
    hasFocus: Boolean,
    widthPx: Float,
    heightPx: Float,
    topOcclusionPx: Float,
) = FocusCameraKey(
    identity = event?.identity,
    timestamp = event?.timestamp,
    latitude = event?.latitude,
    longitude = event?.longitude,
    magnitude = event?.magnitude,
    depth = event?.depth,
    request = request,
    hasFocus = hasFocus,
    widthPx = widthPx,
    heightPx = heightPx,
    topOcclusionPx = topOcclusionPx,
)

/** OutCubic 缓动：与桌面端 Easing.OutCubic 一致。 */
private fun easeOutCubic(t: Double): Double { val u = 1.0 - t; return 1.0 - u * u * u }

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

/**
 * Render one integer tile layer through the whole [z, z + 1) zoom interval.
 * The previous rounded level made the tile range change repeatedly during a
 * pinch, restarting tile work while the camera was still moving.
 */
private fun tileRenderLevel(zoom: Float, maxZoom: Int): Int =
    floor(zoom.toDouble()).toInt().coerceIn(MIN_ZOOM.toInt(), minOf(MAX_ZOOM.toInt(), maxZoom))

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
/** 把瓦片列折叠到 [0, 2^z)，用于 URL 与缓存探测。 */
private fun wrapTileX(x: Int, z: Int): Int {
    val n = 1 shl z
    return ((x % n) + n) % n
}
private fun normX(lon: Double) = (lon + 180.0) / 360.0
private fun normY(lat: Double): Double {
    val rad = lat.coerceIn(-85.05112878, 85.05112878) * PI / 180.0
    return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0
}

/**
 * 网格里的一格瓦片：只含整型瓦片坐标与 URL。屏幕位置在**绘制期**由当前相机算出，
 * 因此拖动/捏合时这个列表保持相等、不会每帧重建（对齐桌面端 MapView.qml 的 tileModel）。
 */
private data class TileRef(
    /** 未折叠的原始瓦片列（可为负/超过 n），用于屏幕定位；URL 才折叠到 [0, n)。 */
    val x: Int,
    val y: Int,
    val z: Int,
    val url: String,
    /** 0 = currently visible, 1 = prefetch. Visible tiles must enter the loader first. */
    val priority: Int,
)

/**
 * 覆盖视口的整型瓦片网格。相机连续变化时网格保持相等 —— 依赖它的重组与加载副作用
 * 因此不会每帧触发，只有跨过瓦片边界或整数缩放层级时才更新。
 */
internal data class TileGrid(
    val z: Int,
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int,
    val n: Int,
)

/** 由相机反推覆盖视口的瓦片范围（含 [margin] 圈预取）。纯函数，便于单测。 */
internal fun tileGrid(
    centerLat: Double,
    centerLon: Double,
    zoom: Float,
    widthPx: Float,
    heightPx: Float,
    maxZoom: Int,
    margin: Int,
): TileGrid {
    val z = zoom.roundToInt().coerceIn(MIN_ZOOM.toInt(), maxOf(MIN_ZOOM.toInt(), maxZoom))
    val tileScale = 2f.pow(zoom - z)
    val scaledTile = TILE_SIZE * tileScale
    val originX = projX(centerLon, z) * tileScale - widthPx / 2.0
    val originY = projY(centerLat, z) * tileScale - heightPx / 2.0
    return TileGrid(
        z = z,
        minX = floor(originX / scaledTile).toInt() - margin,
        maxX = floor((originX + widthPx) / scaledTile).toInt() + margin,
        minY = floor(originY / scaledTile).toInt() - margin,
        maxY = floor((originY + heightPx) / scaledTile).toInt() + margin,
        n = 2.0.pow(z).toInt(),
    )
}

/** 网格左上角在屏上的原点（连续量，只在绘制期求值）。 */
internal fun tileOriginX(centerLon: Double, zoom: Float, z: Int, widthPx: Float): Double {
    val tileScale = 2f.pow(zoom - z)
    return projX(centerLon, z) * tileScale - widthPx / 2.0
}

internal fun tileOriginY(centerLat: Double, zoom: Float, z: Int, heightPx: Float): Double {
    val tileScale = 2f.pow(zoom - z)
    return projY(centerLat, z) * tileScale - heightPx / 2.0
}

/**
 * 瓦片左上角的屏幕 x（px）。[tileX] 必须是**未折叠**的原始列：跨 ±180° 时相机原点与瓦片
 * 列会同步平移整 n 格，用原始列才能让瓦片保持在原位（否则反经线处整片空白并跳变）。
 */
internal fun tileScreenX(tileX: Int, centerLon: Double, zoom: Float, z: Int, widthPx: Float): Float {
    val tileScale = 2f.pow(zoom - z)
    val originX = projX(centerLon, z) * tileScale - widthPx / 2.0
    return (tileX * (TILE_SIZE * tileScale) - originX).toFloat()
}

/**
 * tileUrl 会做多次 String.replace，而兜底探测在绘制热路径上每帧要调用数百次
 * （每个缺口瓦片最多 ~90 次 peek），是逐帧 GC 压力的主要来源。URL 是恒定值，
 * 缓存起来消除这些中间字符串分配。仅 UI 线程访问，容量有界。
 */
private val tileUrlCache = android.util.LruCache<Long, String>(4096)

private fun packTileKey(basemap: Basemap, x: Int, y: Int, z: Int): Long {
    val bm = Basemaps.indexOfFirst { it.id == basemap.id }.coerceAtLeast(0).toLong()
    return (bm shl 47) or (z.toLong() shl 42) or
        ((x.toLong() and 0x1FFFFF) shl 21) or (y.toLong() and 0x1FFFFF)
}

private fun tileUrl(basemap: Basemap, x: Int, y: Int, z: Int): String {
    val key = packTileKey(basemap, x, y, z)
    tileUrlCache.get(key)?.let { return it }
    var url = basemap.urlTemplate.replace("{x}", x.toString()).replace("{y}", y.toString()).replace("{z}", z.toString())
    if (url.contains("{s}")) {
        val sub = basemap.subdomains[(x + y) % basemap.subdomains.size]
        url = url.replace("{s}", sub)
    }
    tileUrlCache.put(key, url)
    return url
}

private val tilePaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)

/**
 * 以亚像素位置绘制一块瓦片。位置与尺寸保持浮点，避免在连续缩放动画里逐帧取整造成
 * 相邻瓦片之间的相对位移（整屏"波纹/抖动"）。用单条原生 drawBitmap(src, RectF, paint)
 * 完成，而不是 save/translate/scale/restore 包裹：同样亚像素，但只有一条绘制指令，
 * 省下 RenderThread 逐帧回放时的指令录制开销。缩放目标尺寸固定为 sizePx×sizePx，兼容 256/512px 瓦片源。
 */
private fun DrawScope.drawTileBitmap(
    bmp: ImageBitmap,
    left: Float,
    top: Float,
    sizePx: Float,
    alpha: Float = 1f,
) {
    if (bmp.width <= 0 || bmp.height <= 0 || sizePx <= 0f) return
    tilePaint.alpha = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
    drawContext.canvas.nativeCanvas.drawBitmap(
        bmp.asAndroidBitmap(),
        null,
        android.graphics.RectF(left, top, left + sizePx, top + sizePx),
        tilePaint,
    )
}

/**
 * 目标瓦片未就绪时，用已缓存的最近父瓦片放大顶替，避免出现空白块。
 * 只读 [TileLoader] 缓存，不触发网络。返回是否真的画了（用于判断淡入时是否有底可垫）。
 */
private fun DrawScope.drawParentTile(
    tile: TileRef,
    px: Float,
    py: Float,
    sizePx: Float,
    basemap: Basemap,
    loader: TileLoader,
): Boolean {
    var level = tile.z
    // 折叠回 [0, n)：原始列可为负/超界，逐层钻取的坐标与 srcOffset 需要非负的有效索引。
    val wx = wrapTileX(tile.x, tile.z)
    var tx = wx
    var ty = tile.y
    var factor = 1
    // 逐层向粗层钻取，命中即停；最多 TILE_FALLBACK_PARENT_LEVELS 层。
    var steps = 0
    while (level > 0 && steps < TILE_FALLBACK_PARENT_LEVELS) {
        tx /= 2
        ty /= 2
        level--
        factor *= 2
        steps++
        val parent = loader.peek(tileUrl(basemap, tx, ty, level)) ?: continue
        val sub = parent.width / factor
        if (sub <= 0) return false
        val sx = (wx % factor) * sub
        val sy = (tile.y % factor) * sub
        tilePaint.alpha = 255
        drawContext.canvas.nativeCanvas.drawBitmap(
            parent.asAndroidBitmap(),
            android.graphics.Rect(sx, sy, sx + sub, sy + sub),
            android.graphics.RectF(px, py, px + sizePx, py + sizePx),
            tilePaint,
        )
        return true
    }
    return false
}

/**
 * 目标瓦片未就绪时，用已缓存的更细层瓦片下采样顶替，避免"缩小地图"时出现空白块。
 * 缩小时上一层（更细）的瓦片仍在 [TileLoader] 缓存里，下采样比放大父瓦片更清晰；
 * 快速捏合可能一次跨过多级，故逐级向下钻取，某层完整覆盖即提前结束。
 * 只读缓存，不触发网络。返回是否至少画了一格。
 */
private fun DrawScope.drawChildTiles(
    tile: TileRef,
    px: Float,
    py: Float,
    sizePx: Float,
    basemap: Basemap,
    loader: TileLoader,
): Boolean {
    var grid = 1
    var level = tile.z
    var drew = false
    val wx = wrapTileX(tile.x, tile.z)
    repeat(TILE_FALLBACK_CHILD_LEVELS) {
        grid = grid shl 1
        level++
        val sub = sizePx / grid
        if (sub < TILE_FALLBACK_MIN_SUBPX) return drew
        var covered = 0
        for (dy in 0 until grid) {
            for (dx in 0 until grid) {
                val child = loader.peek(tileUrl(basemap, wx * grid + dx, tile.y * grid + dy, level)) ?: continue
                drawTileBitmap(child, px + dx * sub, py + dy * sub, sub)
                covered++
            }
        }
        if (covered > 0) drew = true
        if (covered == grid * grid) return true
    }
    return drew
}

/**
 * 兜底绘制：先铺更粗父层当底（放大场景，顺便补掉子层留下的空洞），再用更细子层覆盖
 * （缩小场景更清晰）。两者都只读 [TileLoader] 缓存，不触发网络。返回是否画了底图。
 */
private fun DrawScope.drawFallbackTile(
    tile: TileRef,
    px: Float,
    py: Float,
    sizePx: Float,
    basemap: Basemap,
    loader: TileLoader,
): Boolean {
    val coarser = drawParentTile(tile, px, py, sizePx, basemap, loader)
    val finer = drawChildTiles(tile, px, py, sizePx, basemap, loader)
    return coarser || finer
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
    /**
     * 是否存在显式焦点（活跃预警或用户点选）。对齐桌面端：为 false 时「最近一次事件」只画震中 X，
     * 不抢镜头，默认视野保持全国概览 / 我的位置；为 true 时才取景震中。
     */
    hasFocus: Boolean = true,
    modifier: Modifier = Modifier,
    cameraRequest: Long = 0L,
    /** 与桌面端右侧 layers 按钮一致；详情地图不提供时隐藏该按钮。 */
    onCycleBasemap: (() -> Unit)? = null,
    topOcclusion: androidx.compose.ui.unit.Dp = 310.dp,
    /** 波前出现/结束回调：供上层收起 HUD。 */
    onWavesStarted: () -> Unit = {},
    onWavesFinished: () -> Unit = {},
    /** 是否存在活动预警：空闲自动归位时优先回到预警震中而非我的位置。 */
    warningActive: Boolean = false,
    /** 用户操作地图后，无操作多久自动回到默认视野（我的位置 / 全国概览）。 */
    idleResetMs: Long = 20_000L,
    /** 是否为当前可见页：常驻 Pager 中不可见时暂停动画循环，避免占用 UI 线程。 */
    active: Boolean = true,
) {
    val glass = LocalMapGlass.current
    var centerLat by remember { mutableDoubleStateOf(35.0) }
    var centerLon by remember { mutableDoubleStateOf(105.0) }
    var zoom by remember { mutableFloatStateOf(6f) }
    // 程序化镜头补间（对齐桌面端 moveCamera）：手势/跟随直接赋值，其余平滑过去。
    val reduceMotion = LocalReduceMotion.current
    val cameraScope = rememberCoroutineScope()
    var cameraJob by remember { mutableStateOf<Job?>(null) }
    var cameraAnimating by remember { mutableStateOf(false) }
    var lastCameraRequest by remember { mutableStateOf<Long?>(null) }
    var previousBasemapIsGcj02 by remember { mutableStateOf(basemap.isGcj02) }

    // 镜头是否锁定在焦点事件上（浮动按钮的"跟随"选中态）。默认不跟随：无定位时地图是全国概览。
    var following by remember { mutableStateOf(false) }
    // 用户是否手动控制过镜头（拖动/缩放/定位/退出跟随），避免定位到达时抢镜头。
    var userMovedCamera by remember { mutableStateOf(false) }
    var initializedLocation by remember { mutableStateOf(false) }
    // 波前是否出现过：用于识别"波前消失"的时刻，自动归位并收起 HUD。
    var wavesShown by remember { mutableStateOf(false) }
    // 手势事件不进入 Compose Snapshot；每帧写 Snapshot 会额外唤醒所有观察者。
    val interactionEvents = remember {
        MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }
    var gestureActive by remember { mutableStateOf(false) }
    // Ordinary fixes never interrupt a user's camera. Only use the first fix if idle.
    LaunchedEffect(userLat, userLon) {
        if (!initializedLocation && userLat != null && userLon != null) {
            initializedLocation = true
            if (!userMovedCamera && !hasFocus) {
                val position = if (basemap.isGcj02) CoordinateTransform.wgs84ToGcj02(userLat, userLon) else userLat to userLon
                centerLat = position.first
                centerLon = position.second
                zoom = USER_LOCATION_ZOOM
                following = false
            }
        }
    }

    val focus = focusEvent
    // 波前"影响半径"：CSIS 降到可感下限（I）时的震中距。有意取代 kanameishi 的经验式
    // clamp(50·M², 200, 2000)。震级未知（<=0）时无法反解，退回量程上限，保证波前仍可见。
    val waveFadeKm = if (focus != null && focus.magnitude > 0.0) {
        IntensityCalculator.distanceForCsis(focus.magnitude, focus.depth, CSIS_FADE_LEVEL)
    } else {
        WAVE_MAX_RADIUS_KM
    }
    // 波前半径（km，隐藏记 -1）：只有"还年轻"（发震 60 min 内）的焦点/活跃事件才计算。
    // 这两个值是高频的，只在协程（帧循环 / 相机跟随）与绘制期读取，绝不能在组合期读——
    // 否则跟随预警时每帧都会重组整块地图，镜头切换（自动跟随）动画就会被拖顿。
    val waveP = remember { mutableDoubleStateOf(-1.0) }
    val waveS = remember { mutableDoubleStateOf(-1.0) }
    LaunchedEffect(
        focusEvent?.identity,
        focusEvent?.timestamp,
        focusEvent?.magnitude,
        focusEvent?.depth,
        waveEligible,
        active,
    ) {
        val event = focusEvent
        if (!active || event == null || !waveEligible || event.timestamp <= 0) {
            waveP.value = -1.0; waveS.value = -1.0
            return@LaunchedEffect
        }
        while (true) {
            val elapsed = AppClock.now() - event.timestamp
            if (event.isCanceled || elapsed > WAVE_WINDOW_MS) {
                waveP.value = -1.0; waveS.value = -1.0
                break
            }
            if (elapsed < 0L) {
                // 发震时刻在未来（模拟源会把时刻钳到 now+55s）：先隐藏，继续等它到点，不能就此退出。
                waveP.value = -1.0; waveS.value = -1.0
            } else {
                val (p, s) = waveRadii(event.depth, elapsed / 1000.0)
                // 透明度归零即视为隐藏：半径记 -1，取景与绘制都会跳过。
                fun visibleRadius(r: Double): Double =
                    if (r > 0.0 && IntensityCalculator.waveOpacity(r, waveFadeKm) > 0.0) r else -1.0
                waveP.value = visibleRadius(p)
                waveS.value = visibleRadius(s)
            }
            // 跟随时按显示帧率刷新，波前缩放的跟随才够细腻（不在一格一格地跳）；
            // 未跟随时 100ms 足够画波前圆，避免整屏无谓重组。
            if (following) withFrameNanos { } else delay(100)
        }
    }
    // 波前半径显示值：向目标半径平滑追平（对齐桌面端 Behavior），避免目标值的台阶让圆一圈一圈地跳。
    // 目标归零（隐藏）时立即归零，不做收缩动画。同样只在绘制期读取。
    var dispPKm by remember { mutableDoubleStateOf(0.0) }
    var dispSKm by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1_000_000.0).coerceAtLeast(0.0)
            last = now
            val tp = waveP.value
            val ts = waveS.value
            if (tp <= 0.0 && ts <= 0.0 && dispPKm <= 0.0 && dispSKm <= 0.0) { delay(100); continue }
            if (gestureActive) {
                // Camera movement already invalidates the map draw. Avoid doing
                // a second full frame-rate state update for wave smoothing while
                // the user is actively pinching.
                delay(100)
                continue
            }
            val k = (dt / WAVE_SMOOTH_MS).coerceIn(0.0, 1.0)
            dispPKm = if (tp <= 0.0) 0.0 else dispPKm + (tp - dispPKm) * k
            dispSKm = if (ts <= 0.0) 0.0 else dispSKm + (ts - dispSKm) * k
        }
    }

    BoxWithConstraints(modifier = modifier.clip(RoundedCornerShape(0.dp))) {
        val mapHeight = maxHeight
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val scheme = MiuixTheme.colorScheme

        val topInsetPx = with(density) { topOcclusion.toPx() }.coerceAtMost(heightPx * 0.75f)
        val bottomInsetPx = with(density) { 64.dp.toPx() }
        val sideInsetPx = with(density) { 72.dp.toPx() }

        /** 中国版图概览镜头（大陆 + 海南 + 台湾）。整数层级，静止时瓦片 1:1 渲染。 */
        fun chinaCamera(): Camera {
            val lonMin = 73.5; val lonMax = 135.1; val latMin = 18.0; val latMax = 53.6
            val x0 = normX(lonMin); val x1 = normX(lonMax)
            val y0 = normY(latMax); val y1 = normY(latMin)
            val availableW = (widthPx - 2 * sideInsetPx).coerceAtLeast(80f)
            val availableH = (heightPx - topInsetPx - bottomInsetPx).coerceAtLeast(80f)
            val scale = minOf(availableW / (x1 - x0), availableH / (y1 - y0))
            val z = floor(ln(scale / TILE_SIZE) / LN2).toFloat().coerceIn(MIN_ZOOM, minOf(MAX_ZOOM, basemap.maxZoom.toFloat()))
            val world = TILE_SIZE * 2.0.pow(z.toDouble())
            val targetY = topInsetPx + availableH / 2.0
            val lon = wrapLon((x0 + x1) / 2.0 * 360.0 - 180.0)
            val lat = unprojLat(((y0 + y1) / 2.0 - (targetY - heightPx / 2.0) / world).coerceIn(0.0, 1.0) * worldSize(0), 0)
            return Camera(lat, lon, z)
        }

        /** 默认视野：有定位则聚焦用户，否则全国概览。 */
        fun defaultCamera(): Camera =
            if (userLat != null && userLon != null) {
                val p = if (basemap.isGcj02) CoordinateTransform.wgs84ToGcj02(userLat, userLon) else userLat to userLon
                Camera(p.first, p.second, USER_LOCATION_ZOOM)
            } else chinaCamera()

        /** 震中取景镜头：固定使用与用户定位相同的默认缩放，保留顶部遮挡修正。 */
        fun focusCamera(): Camera? {
            val event = focusEvent ?: return null
            val (lat, lon) = if (basemap.isGcj02) CoordinateTransform.wgs84ToGcj02(event.latitude, event.longitude)
            else event.latitude to event.longitude
            val ex = normX(lon)
            val ey = normY(lat)
            val availableH = (heightPx - topInsetPx - bottomInsetPx).coerceAtLeast(80f)
            val z = USER_LOCATION_ZOOM.coerceIn(MIN_ZOOM, minOf(MAX_ZOOM, basemap.maxZoom.toFloat()))
            val world = TILE_SIZE * 2.0.pow(z.toDouble())
            val targetY = topInsetPx + availableH / 2.0
            val lonC = wrapLon(ex * 360.0 - 180.0)
            val latC = unprojLat((ey - (targetY - heightPx / 2.0) / world).coerceIn(0.0, 1.0) * worldSize(0), 0)
            return Camera(latC, lonC, z)
        }

        /**
         * 平滑移动到目标镜头（对齐桌面端 moveCamera）：OutCubic 补间，经度走最短路径。
         * animate=false / 减少动态效果时立即生效（用于逐帧跟随）。
         */
        fun applyCamera(cam: Camera, durationMs: Long, animate: Boolean) {
            cameraJob?.cancel()
            val targetLon = wrapLon(centerLon + wrappedDelta((cam.lon - centerLon) / 360.0) * 360.0)
            if (!animate || reduceMotion || durationMs <= 0L) {
                cameraAnimating = false
                centerLat = cam.lat
                centerLon = targetLon
                zoom = cam.zoom
                return
            }
            val startLat = centerLat; val startLon = centerLon; val startZoom = zoom
            cameraAnimating = true
            cameraJob = cameraScope.launch {
                val startNanos = withFrameNanos { it }
                while (true) {
                    val now = withFrameNanos { it }
                    val t = ((now - startNanos) / 1_000_000.0 / durationMs).coerceIn(0.0, 1.0)
                    val e = easeOutCubic(t)
                    centerLat = startLat + (cam.lat - startLat) * e
                    centerLon = wrapLon(startLon + (targetLon - startLon) * e)
                    zoom = (startZoom + (cam.zoom - startZoom) * e).toFloat()
                    if (t >= 1.0) break
                }
                cameraAnimating = false
            }
        }

        /** 手势接管镜头：停掉补间，避免与拖动/捏合互相打架。 */
        fun stopCameraAnimation() { cameraJob?.cancel(); cameraJob = null; cameraAnimating = false }
        // 有显式焦点则平滑取景震中；否则平滑回到默认视野（我的位置 / 全国概览）。
        // 视口/遮挡变化也必须重算：Android 首帧布局未稳定时若用 0 尺寸算出的目标镜头，
        // 原实现会在动画期间跳过后续尺寸更新，导致聚焦事件不自动缩放。
        LaunchedEffect(focusCameraKey(
            event = focusEvent,
            request = cameraRequest,
            hasFocus = hasFocus,
            widthPx = widthPx,
            heightPx = heightPx,
            topOcclusionPx = topInsetPx,
        )) {
            if (widthPx <= 0f || heightPx <= 0f) return@LaunchedEffect
            if (hasFocus) {
                // 尺寸变化只在自动跟随时重取景；用户已经拖动/缩放后不能被 HUD
                // 测量变化抢回镜头。新的 request 则代表显式聚焦，允许恢复跟随。
                val initialFocus = lastCameraRequest == null && !userMovedCamera
                val explicitRefocus = lastCameraRequest != null && lastCameraRequest != cameraRequest
                if (following || initialFocus || explicitRefocus) {
                    following = true
                    lastCameraRequest = cameraRequest
                    focusCamera()?.let { applyCamera(it, CAMERA_TWEEN_MS, animate = true) }
                }
            } else if (!userMovedCamera) {
                lastCameraRequest = null
                following = false
                applyCamera(defaultCamera(), CAMERA_TWEEN_MS, animate = true)
            }
        }
        // 切换底图时保持当前地理位置；高德/Petal 使用 GCJ-02，OSM 使用 WGS-84。
        // 跟随事件时再按新 datum 重取景，保持震中和缩放都与桌面端一致。
        LaunchedEffect(basemap.id) {
            val oldIsGcj02 = previousBasemapIsGcj02
            if (oldIsGcj02 != basemap.isGcj02) {
                val position = if (basemap.isGcj02) {
                    CoordinateTransform.wgs84ToGcj02(centerLat, centerLon)
                } else {
                    CoordinateTransform.gcj02ToWgs84(centerLat, centerLon)
                }
                centerLat = position.first
                centerLon = position.second
            }
            previousBasemapIsGcj02 = basemap.isGcj02
            if (following && hasFocus) focusCamera()?.let { applyCamera(it, 0L, animate = false) }
        }
        // 波前逐帧变化时跟随（直接赋值，保证跟手）；入场补间进行中不抢镜头。
        // 用 snapshotFlow 在协程里读波前半径，避免把高频状态变成每帧重组的入口。
        LaunchedEffect(Unit) {
            snapshotFlow { waveP.value to waveS.value }.collect {
                if (following && hasFocus && !cameraAnimating) focusCamera()?.let { applyCamera(it, 0L, animate = false) }
            }
        }
        // 波前全部消失（走完/淡出/事件结束）：若仍在跟随震中，自动回到我的位置（无定位则全国概览），
        // 并通知上层收起 HUD。用户已手动操作过镜头（following=false）时不抢镜头。
        LaunchedEffect(Unit) {
            snapshotFlow { waveP.value > 0.0 || waveS.value > 0.0 }
                .distinctUntilChanged()
                .collect { hasWaves ->
                    if (hasWaves) {
                        wavesShown = true
                        onWavesStarted()
                    } else if (wavesShown) {
                        wavesShown = false
                        if (following) {
                            following = false
                            applyCamera(defaultCamera(), CAMERA_TWEEN_MS, animate = true)
                        }
                        onWavesFinished()
                    }
                }
        }
        // 空闲自动归位：用户拖动/缩放后一段时间无操作且未在跟随事件，则回到默认视野。
        val idleResetAction by rememberUpdatedState {
            if (!following) {
                // 有活动预警时归位到预警震中并重新跟随；否则回到我的位置（无定位则全国概览）。
                if (warningActive && focusEvent != null) {
                    following = true
                    focusCamera()?.let { applyCamera(it, CAMERA_TWEEN_MS, animate = true) }
                } else {
                    following = false
                    applyCamera(defaultCamera(), CAMERA_TWEEN_MS, animate = true)
                }
            }
        }
        LaunchedEffect(Unit) {
            interactionEvents.collectLatest {
                gestureActive = true
                delay(160L)
                gestureActive = false
            }
        }
        LaunchedEffect(Unit) {
            interactionEvents.collectLatest {
                delay(idleResetMs)
                idleResetAction()
            }
        }

        // 相机→网格：只在跨过瓦片边界或整数层级时得到新值。拖动/捏合时相机连续变化，
        // 但这里返回相等对象，读它的重组与下面的加载副作用都不会被触发。
        val grid by remember(widthPx, heightPx, basemap.maxZoom) {
            derivedStateOf {
                val tileZoom = tileRenderLevel(zoom, basemap.maxZoom).toFloat()
                tileGrid(centerLat, centerLon, tileZoom, widthPx, heightPx, basemap.maxZoom, TILE_PREFETCH_MARGIN)
            }
        }

        val tiles = remember(grid, basemap.id) {
            // Build a zero-margin grid as the loading priority boundary. The outer
            // ring remains useful for panning, but it must not occupy all six load
            // permits before the screen itself has any tiles.
            val tileZoom = tileRenderLevel(zoom, basemap.maxZoom).toFloat()
            val visibleGrid = tileGrid(
                centerLat, centerLon, tileZoom, widthPx, heightPx,
                basemap.maxZoom, margin = 0,
            )
            val list = ArrayList<TileRef>()
            for (tx in grid.minX..grid.maxX) {
                // 位置用未折叠的原始 tx（对齐桌面端 tileModel）：跨 ±180° 时相机原点与瓦片
                // 索引会同步平移整整 n 格，几何因此保持连续；只有取 URL 时才折叠回 [0, n)。
                val wrappedX = ((tx % grid.n) + grid.n) % grid.n
                for (ty in grid.minY..grid.maxY) {
                    if (ty < 0 || ty >= grid.n) continue
                    val priority = if (
                        tx in visibleGrid.minX..visibleGrid.maxX &&
                        ty in visibleGrid.minY..visibleGrid.maxY
                    ) 0 else 1
                    list.add(TileRef(tx, ty, grid.z, tileUrl(basemap, wrappedX, ty, grid.z), priority))
                }
            }
            list.sortBy { it.priority }
            list
        }
        // Prefetch tiles are loaded for the next pan, but drawing them every
        // frame only adds work outside the viewport. Keep loading and rendering
        // sets separate.
        val visibleTiles = remember(tiles) { tiles.filter { it.priority == 0 } }

        val images = remember { mutableStateMapOf<String, ImageBitmap>() }
        // Jobs are keyed by URL so a tile that remains visible survives a grid
        // update, while requests that moved outside the current view can be
        // cancelled immediately.
        val tileJobs = remember { mutableMapOf<String, Job>() }
        // 每个瓦片首次出现的时刻（ms），用于淡入（见绘制处的 alpha）。
        val tileAppearAt = remember { mutableStateMapOf<String, Long>() }
        // 跨层底衬：换整数层级时把旧层整层留作底衬，新层画满再撤。避免黑屏与逐格拼贴，
        // 也避免"每个缺口瓦片每帧去缓存里逐级找父/子瓦片"的探测风暴（对齐桌面端 underlayZ）。
        var underlay by remember { mutableStateOf<List<TileRef>?>(null) }
        var underlayAt by remember { mutableStateOf(0L) }
        var lastTiles by remember { mutableStateOf<List<TileRef>>(emptyList()) }
        var lastTileZ by remember { mutableStateOf(-1) }
        val latestVisibleTiles by rememberUpdatedState(visibleTiles)
        val loadScope = rememberCoroutineScope()
        // 瓦片淡入的逐帧时钟：仅在跟随时或仍有瓦片处于淡入窗口内时按帧推进，
        // 空闲时降到 100ms 轮询，避免常驻 60Hz 重组。手动缩放/平移也能平滑淡入。
        var frameNow by remember { mutableStateOf(AppClock.now()) }
        LaunchedEffect(active) {
            if (!active) return@LaunchedEffect
            while (true) {
                val fading = tileAppearAt.values.any { frameNow - it < TILE_FADE_MS.toLong() }
                if (following || cameraAnimating || fading) {
                    withFrameNanos { }
                    frameNow = AppClock.now()
                } else {
                    delay(100)
                }
                // 新层画满并淡入完成后撤掉底衬；弱网下新层迟迟不齐则由超时兜底。
                if (underlay != null) {
                    val aged = AppClock.now() - underlayAt
                    val complete = latestVisibleTiles.all { images.containsKey(it.url) }
                    if ((complete && aged >= TILE_FADE_MS.toLong()) || aged >= UNDERLAY_MAX_MS) underlay = null
                }
            }
        }
        LaunchedEffect(tiles, active) {
            if (!active) {
                tileJobs.values.toList().forEach { it.cancel() }
                tileJobs.clear()
                return@LaunchedEffect
            }
            val z = grid.z
            if (lastTileZ >= 0 && z != lastTileZ) {
                // 换层：把刚才那一层整层降级为底衬（对齐桌面端 underlayZ），过渡期内一直垫在
                // 新层之下，因此不会黑屏、也不会逐格拼贴。
                underlay = lastTiles
                underlayAt = AppClock.now()
            }
            lastTiles = tiles
            lastTileZ = z
            // 底衬位图在过渡期内不回收；撤掉底衬后，下一次瓦片集变化时自然释放。
            val wanted = HashSet<String>(tiles.size * 2)
            tiles.mapTo(wanted) { it.url }
            underlay?.forEach { wanted.add(it.url) }
            tileJobs.keys.toList().filterNot { it in wanted }.forEach { url ->
                tileJobs.remove(url)?.cancel()
            }
            images.keys.toList().forEach {
                if (it !in wanted) {
                    images.remove(it)
                    tileAppearAt.remove(it)
                }
            }
            for (tile in tiles) {
                val url = tile.url
                if (images.containsKey(url) || tileJobs.containsKey(url)) continue

                // Reattach an in-memory tile synchronously. This is what makes a
                // zoom-out to a previously visited layer feel instant even after
                // the UI's short-lived visible-image set was pruned.
                val cached = tileLoader.peek(url)
                if (cached != null) {
                    images[url] = cached
                    if (!tileAppearAt.containsKey(url)) tileAppearAt[url] = AppClock.now()
                    continue
                }

                // 任务挂在 composition 作用域上；网格变化时会取消已经离开视口的下载。
                var currentJob: Job? = null
                currentJob = loadScope.launch {
                    // 冷缓存瓦片首访可能超时/被重置：失败后退避重试，用尽次数才放弃。
                    // 放弃后本轮不再重试；瓦片移出再进入视野时由 LaunchedEffect 重新发起。
                    try {
                        var attempt = 0
                        while (true) {
                            val bmp = tileLoader.load(url)
                            if (bmp != null) {
                                currentCoroutineContext().ensureActive()
                                images[url] = bmp
                                if (!tileAppearAt.containsKey(url)) tileAppearAt[url] = AppClock.now()
                                break
                            }
                            attempt++
                            if (attempt >= TILE_MAX_ATTEMPTS) break
                            delay(TILE_RETRY_BASE_DELAY_MS shl (attempt - 1))
                        }
                    } finally {
                        if (tileJobs[url] === currentJob) tileJobs.remove(url)
                    }
                }
                tileJobs[url] = currentJob
            }
        }

        val gcjShift = basemap.isGcj02
        fun shift(lat: Double, lon: Double) =
            if (gcjShift) CoordinateTransform.wgs84ToGcj02(lat, lon) else lat to lon
        // 相机是高频状态：screenX/screenY 只在绘制/布局阶段被调用，读取不触发重组。
        // worldSize(z)·tileScale == TILE_SIZE·2^zoom，与 z 的取整无关，直接按 zoom 算。
        fun screenX(lon: Double): Float =
            (widthPx / 2.0 + wrappedDelta(normX(lon) - normX(centerLon)) * TILE_SIZE * 2.0.pow(zoom.toDouble())).toFloat()
        fun screenY(lat: Double): Float {
            val tz = tileRenderLevel(zoom, basemap.maxZoom)
            val ts = 2f.pow(zoom - tz)
            return (projY(lat, tz) * ts - projY(centerLat, tz) * ts + heightPx / 2.0).toFloat()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(scheme.surfaceContainer)
                .pointerInput(basemap.id) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        stopCameraAnimation()
                        following = false
                        userMovedCamera = true
                        interactionEvents.tryEmit(Unit)
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
                .background(scheme.surfaceContainer)
                .onGloballyPositioned { if (glass?.enabled == true) glass.sourceReady = true }) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // 相机→屏幕的连续量在这里（绘制期）求值：拖动/捏合只失效绘制，不触发重组。
                val gz = grid.z
                val gScale = 2f.pow(zoom - gz)
                val scaledTile = TILE_SIZE * gScale
                val originY = projY(centerLat, gz) * gScale - size.height / 2.0
                val tileSizePx = scaledTile + 1f

                // 底衬：换层过渡期把旧层整层铺在最下面。整层直绘（每格一次查表），
                // 比逐格向上/向下钻取缓存便宜两个数量级，也不会露黑。
                val under = underlay
                if (under != null) {
                    val uz = under.firstOrNull()?.z ?: gz
                    val uScale = 2f.pow(zoom - uz)
                    val uTile = TILE_SIZE * uScale
                    val uOriginX = projX(centerLon, uz) * uScale - size.width / 2.0
                    val uOriginY = projY(centerLat, uz) * uScale - size.height / 2.0
                    val uSize = uTile + 1f
                    for (t in under) {
                        val bmp = images[t.url] ?: continue
                        val drawX = t.x * uTile - uOriginX
                        val drawY = t.y * uTile - uOriginY
                        if (drawX + uTile < 0.0 || drawX > size.width ||
                            drawY + uTile < 0.0 || drawY > size.height
                        ) continue
                        drawTileBitmap(bmp, drawX.toFloat(), drawY.toFloat(), uSize)
                    }
                }

                // Keep the prefetch ring available when a pan enters it, but
                // skip every tile that is outside the current clip rectangle.
                for (tile in tiles) {
                    val px = tileScreenX(tile.x, centerLon, zoom, gz, size.width)
                    val py = (tile.y * scaledTile - originY).toFloat()
                    if (px + tileSizePx < 0f || px > size.width ||
                        py + tileSizePx < 0f || py > size.height
                    ) continue
                    val bmp = images[tile.url]
                    if (bmp == null) {
                        // 有底衬垫着就不做逐格兜底探测：换层瞬间缺口瓦片极多，探测会退化成风暴。
                        if (under == null) drawFallbackTile(tile, px, py, tileSizePx, basemap, tileLoader)
                        continue
                    }
                    // 新瓦片淡入，掩盖跨层级清晰度突变造成的顿挫。
                    // 底下有父瓦片/底衬可垫时才淡入（否则半透明会露出底色）；否则直接显示。
                    val appearAt = tileAppearAt[tile.url]
                    val alpha = if (appearAt != null) {
                        ((frameNow - appearAt).toFloat() / TILE_FADE_MS).coerceIn(0f, 1f)
                    } else {
                        1f
                    }
                    if (alpha < 1f && (under != null || drawFallbackTile(tile, px, py, tileSizePx, basemap, tileLoader))) {
                        drawTileBitmap(bmp, px, py, tileSizePx, alpha)
                    } else {
                        drawTileBitmap(bmp, px, py, tileSizePx)
                    }
                }

                val event = focus ?: return@Canvas
                val (fLatD, fLonD) = shift(event.latitude, event.longitude)
                val fx = screenX(fLonD)
                val fy = screenY(fLatD)

                // px per km。墨卡托保角，水平/垂直同尺度：worldPx/360° ÷ (111.32·cosφ) km/°。
                val cosLat = cos(event.latitude * PI / 180.0).coerceAtLeast(0.01)
                val pxPerKm = (TILE_SIZE * 2.0.pow(gz.toDouble()) * gScale / 360.0) / (111.32 * cosLat)

                // 波前不透明度在绘制期求值：dispPKm/dispSKm 由帧循环更新，若在组合期读会每帧重组整块地图。
                val pOpacity = if (dispPKm > 0.0) IntensityCalculator.waveOpacity(dispPKm, waveFadeKm) else 0.0
                val sOpacity = if (dispSKm > 0.0) IntensityCalculator.waveOpacity(dispSKm, waveFadeKm) else 0.0
                // S 波径向渐变填充的不透明度：只在影响半径内可见（对齐 kanameishi 的 sWaveFill）。
                val sFillOpacity = if (dispSKm > 0.0) IntensityCalculator.waveFillOpacity(dispSKm, waveFadeKm) else 0.0

                // P/S 波前圆：反解走时表得到半径（km），-1 表示不画。半径用平滑后的显示值。
                if (dispPKm > 0.0) {
                    drawCircle(
                        color = SeismicColors.P_WAVE.copy(alpha = pOpacity.toFloat()),
                        radius = (dispPKm * pxPerKm).toFloat(),
                        center = Offset(fx, fy),
                        style = Stroke(width = 2f),
                    )
                }
                // S 波径向渐变填充：中心透明、边缘着色，衬在描边之下（对齐 kanameishi 的 sWaveFill）。
                if (dispSKm > 0.0 && sFillOpacity > 0.0) {
                    val fillRadius = (dispSKm * pxPerKm).toFloat()
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
                if (dispSKm > 0.0) {
                    drawCircle(
                        color = SeismicColors.S_WAVE.copy(alpha = sOpacity.toFloat()),
                        radius = (dispSKm * pxPerKm).toFloat(),
                        center = Offset(fx, fy),
                        style = Stroke(width = 2f),
                    )
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
                val halfMarker = with(density) { 19.dp.toPx() }
                Box(
                    modifier = Modifier
                        // 相机坐标只在布局阶段读取（offset 的 lambda 在 measure/placement 执行），
                        // 否则拖动/缩放时这里每帧都会在组合期读 zoom/center，重组整块地图。
                        .offset {
                            val x = screenX(lon)
                            val y = screenY(lat)
                            IntOffset((x - halfMarker).roundToInt(), (y - halfMarker).roundToInt())
                        }
                        .size(38.dp),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(scheme.primary.copy(alpha = 0.18f)),
                    )
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(scheme.primary),
                    )
                }
            }

            } // end map-only backdrop source

            // 右下角控件与比例尺要抬到悬浮底栏之上（底栏高约 78dp + 系统导航栏内边距）。
            val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val barClearance = navBottom + 90.dp

            // Foreground controls remain sharp and are never sampled.
            Column(
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = barClearance + 62.dp)
                    .heightIn(max = (mapHeight - topOcclusion - barClearance - 70.dp).coerceAtLeast(48.dp))
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 放大（整数层级 ±1，和桌面端滚轮一致）
                MapFloatingButton(AppIcon.Plus, dark, "放大地图") {
                    following = false
                    userMovedCamera = true
                    interactionEvents.tryEmit(Unit)
                    val target = (zoom.roundToInt() + 1).toFloat()
                        .coerceAtMost(minOf(MAX_ZOOM, basemap.maxZoom.toFloat()))
                    if (target > zoom.roundToInt()) {
                        applyCamera(Camera(centerLat, centerLon, target), ZOOM_TWEEN_MS, animate = true)
                    }
                }
                // 缩小
                MapFloatingButton(AppIcon.Minus, dark, "缩小地图") {
                    following = false
                    userMovedCamera = true
                    interactionEvents.tryEmit(Unit)
                    val target = (zoom.roundToInt() - 1).toFloat().coerceAtLeast(MIN_ZOOM)
                    if (target < zoom.roundToInt()) {
                        applyCamera(Camera(centerLat, centerLon, target), ZOOM_TWEEN_MS, animate = true)
                    }
                }
                MapFloatingDivider()
                // 回到我的位置
                if (userLat != null && userLon != null) {
                    MapFloatingButton(AppIcon.Navigation, dark, "回到我的位置") {
                        following = false
                        userMovedCamera = true
                        interactionEvents.tryEmit(Unit)
                        val position = shift(userLat, userLon)
                        applyCamera(Camera(position.first, position.second, USER_LOCATION_ZOOM), CAMERA_TWEEN_MS, animate = true)
                    }
                }
                // 回到震中 / 退出跟随
                if (focus != null) {
                    MapFloatingButton(AppIcon.Locate, dark,
                        if (following) "正在跟随震中；点击退出跟随" else "恢复跟随震中",
                        selected = following) {
                        if (following) {
                            following = false
                            applyCamera(defaultCamera(), CAMERA_TWEEN_MS, animate = true)
                        } else {
                            following = true
                            focusCamera()?.let { applyCamera(it, CAMERA_TWEEN_MS, animate = true) }
                        }
                    }
                }
                val cycleBasemap = onCycleBasemap
                if (cycleBasemap != null) {
                    MapFloatingButton(AppIcon.Layers, dark, "切换底图", onClick = cycleBasemap)
                }
            }

            // 右下角动态比例尺。抽成独立 composable：它读取的相机状态只让这一小块重组，
            // 不会把整屏地图拖进来。
            MapScaleBar(
                cameraZoom = { zoom },
                cameraLat = { centerLat },
                dark = dark,
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomEnd)
                    .padding(bottom = barClearance, end = 14.dp),
            )
        }
    }
}

@Composable
private fun ColumnScope.MapFloatingDivider() {
    Box(
        Modifier
            .align(androidx.compose.ui.Alignment.CenterHorizontally)
            .width(28.dp)
            .height(1.dp)
            .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.7f)),
    )
}

/** 右下角比例尺：按 70dp 目标宽度挑选最接近的整数刻度。 */
@Composable
private fun MapScaleBar(
    cameraZoom: () -> Float,
    cameraLat: () -> Double,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    val zoom = cameraZoom()
    val centerLat = cameraLat()
    val density = LocalDensity.current
    val cosLat = cos(centerLat * PI / 180.0).coerceAtLeast(0.01)
    val pxPerKm = (TILE_SIZE * 2.0.pow(zoom.toDouble()) / 360.0) / (111.32 * cosLat)
    val targetKm = with(density) { 70.dp.toPx() } / pxPerKm
    val scaleKm = listOf(0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0)
        .minBy { kotlin.math.abs(ln(it / targetKm)) }
    val scaleBarWidth = with(density) { (scaleKm * pxPerKm).toFloat().toDp() }

    Box(
        modifier = modifier
            .mapGlass(dark, RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            com.aloys23.komiraquake.ui.components.Label(
                if (scaleKm < 1) "${(scaleKm * 1000).roundToInt()} m" else "${scaleKm.roundToInt()} km",
                glassForegroundColor(dark),
                size = 10.sp,
            )
            Spacer(Modifier.height(2.dp))
            Box(
                modifier = Modifier
                    .width(scaleBarWidth)
                    .height(3.dp)
                    .background(glassForegroundColor(dark)),
            )
        }
    }
}

@Composable
private fun MapFloatingButton(icon: AppIcon, dark: Boolean, description: String, enabled: Boolean = true,
    selected: Boolean = false, onClick: () -> Unit) {
    val tint = when {
        !enabled -> MiuixTheme.colorScheme.disabledOnSurface
        selected -> MiuixTheme.colorScheme.primary
        else -> glassForegroundColor(dark)
    }
    Box(Modifier.mapGlass(dark, CircleShape)) {
        IconButton(
            onClick = onClick, enabled = enabled, backgroundColor = Color.Transparent,
            cornerRadius = 24.dp, minWidth = 48.dp, minHeight = 48.dp,
        ) { LucideIcon(icon, tint, description) }
    }
}

/** 供设置页展示。 */
fun basemapById(id: String): Basemap = Basemaps.firstOrNull { it.id == id } ?: Petal
