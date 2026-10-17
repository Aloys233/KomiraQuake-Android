package com.aloys23.komiraquake.ui.map

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import com.aloys23.komiraquake.core.CoordinateTransform
import com.aloys23.komiraquake.core.GeoGeometry
import com.aloys23.komiraquake.core.IntensityCalculator
import com.aloys23.komiraquake.core.SeismicColors
import com.aloys23.komiraquake.core.VectorMapData
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 矢量地图渲染：在 [DrawScope] 里画世界陆地 / 省界 / 区县三层。
 *
 * 与桌面端 `desktop/src/map/vector_map_item.cpp` 同口径：
 * - 墨卡托投影满足 projX(lon,z) = normX(lon)·256·2^z，故整数 zoom 的投影结果整体缩放
 *   即得任意连续 zoom 的**精确**结果（无插值误差）；
 * - GCJ-02 纠偏只在叠加到 GCJ-02 栅格底图时施加，且只作用于中国层
 *   （`CoordinateTransform.outOfChina` 是矩形 bbox，全球层纠偏会误伤蒙古/印度）；
 * - 洞（多环）用 [PathFillType.EvenOdd] 镂空 —— 175 个区县是多环，填洞会让底图出现错误色块。
 */
class VectorMapRenderer(private val data: VectorMapData?) {

    val world = data?.layer("world")
    val province = data?.layer("province")
    val district = data?.layer("district")

    /** 区县名 -> CSIS 烈度（原始值）。空则区县不着色。 */
    var districtIntensity: Map<String, Double> = emptyMap()

    /** 整数 zoom 的投影几何缓存；连续缩放只变换 Canvas，不重建 Path。 */
    private data class CachedFeature(
        val bounds: GeoGeometry.GeoBounds,
        val path: Path,
        val hasGeometry: Boolean,
    )

    private data class GeometryCacheKey(
        val layerName: String,
        val gcjShift: Boolean,
        val applyGcj: Boolean,
    )

    private val geometryCaches = HashMap<GeometryCacheKey, List<CachedFeature>>()

    /**
     * @param gcjShift 是否对 WGS-84 顶点施加 GCJ-02 纠偏（叠加到 GCJ-02 栅格底图时为 true）。
     * @param vectorOnly 纯矢量底图：世界陆地需要填充底色与海岸描边；叠加模式只画中国图层。
     */
    fun DrawScope.draw(
        centerLat: Double,
        centerLon: Double,
        zoom: Float,
        gcjShift: Boolean,
        vectorOnly: Boolean,
        landFill: Color,
        coastStroke: Color,
        provinceStroke: Color,
    ) {
        val frame = Frame(centerLat, centerLon, zoom)
        val view = ViewBounds(frame, size.width, size.height)

        // ---- 世界陆地 ------------------------------------------------
        // 叠加模式下世界层完全透明，不进入逐帧裁剪/绘制路径。
        if (vectorOnly) {
            world?.let { layer ->
                drawLayer(
                    layer, frame, view, gcjShift = false, applyGcj = false,
                    fill = landFill, stroke = coastStroke, strokeWidthPx = 1f,
                    fillOf = null,
                )
            }
        }

        // ---- 中国省界 ------------------------------------------------
        // 全国概览时省界本来就是透明的，直接跳过整层。
        if (provinceStroke != Color.Transparent) {
            province?.let { layer ->
                drawLayer(
                    layer, frame, view, gcjShift, applyGcj = true,
                    fill = Color.Transparent, stroke = provinceStroke, strokeWidthPx = 1f,
                    fillOf = null,
                )
            }
        }

        // ---- 区县（EEW 时按烈度着色）---------------------------------
        // 没有受影响区县时，区县层完全透明，避免每帧遍历 1011 个要素。
        if (districtIntensity.isNotEmpty()) {
            district?.let { layer ->
                drawLayer(
                    layer, frame, view, gcjShift, applyGcj = true,
                    fill = Color.Transparent, stroke = DISTRICT_STROKE, strokeWidthPx = 1f,
                    fillOf = { name ->
                        districtIntensity[name]?.let { intensityFill(it) } ?: Color.Transparent
                    },
                    // 平时整层完全透明（对齐 kanameishi eewBaseMapDefaultStroke 的 alpha=0）。
                    strokeOnlyShaded = true,
                )
            }
        }
    }

    /**
     * 相机 -> 屏幕的映射。整数 zoom 的世界像素为基准，连续缩放只乘一个 scale，
     * 与桌面端的整数 zoom 预投影缓存同一口径。
     */
    private class Frame(centerLat: Double, centerLon: Double, zoom: Float) {
        val centerLat = centerLat
        val centerLon = centerLon
        // 与桌面端一致：几何只按一个**固定参考层级**投影一次，绘制时整体乘 2^(zoom-kRefZoom)。
        // 曾经按 round(zoom) 缓存 —— 每跨过一个整数层级就要重建全部 Path（34 万点），
        // 捏合缩放时正好在动画中途卡一下，而且访问过的层级会一直囤在内存里。
        val gz: Int = kRefZoom
        val scale: Double = 2.0.pow(zoom.toDouble() - kRefZoom)
        val size: Double = worldSize(kRefZoom)
        val originX: Double = projX(centerLon, gz) * scale
        val originY: Double = projY(centerLat, gz) * scale

        fun sx(lon: Double, w: Double): Double = projX(lon, gz) * scale - originX + w / 2.0
        fun sy(lat: Double, h: Double): Double = projY(lat, gz) * scale - originY + h / 2.0
    }

    /**
     * 视口在经纬度上的范围，用于按包围盒裁剪。
     *
     * 纬度方向容易写错：屏幕 y 向下增大而纬度减小，故 `northLat`（屏幕上方）
     * 纬度**更大**。重叠判定是 `minLat <= northLat && maxLat >= southLat` ——
     * 与经度的 `min/max` 用法相反。写反会把视口内的要素全部裁掉，地图一片空白。
     */
    private class ViewBounds(frame: Frame, w: Float, h: Float) {
        // sx(lon) = (projX(lon) - projX(center)) * scale + w/2，故屏幕左/右沿对应的
        // 整数 zoom 世界像素是  projX(center) ∓ w/(2*scale)。frame.originX 就是
        // projX(center)*scale，除以 scale 即得基准 —— 必须带上它，否则边界会落在
        // 世界原点附近、把真实视口整个错开（表现同样是全裁、地图空白）。
        private val baseX = frame.originX / frame.scale
        private val baseY = frame.originY / frame.scale
        private val halfW = w / 2.0 / frame.scale
        private val halfH = h / 2.0 / frame.scale

        private val westLon = unprojLon(baseX - halfW, frame.size)
        private val eastLon = unprojLon(baseX + halfW, frame.size)
        private val northLat = unprojLat(baseY - halfH, frame.size)
        private val southLat = unprojLat(baseY + halfH, frame.size)

        fun contains(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): Boolean =
            maxLon >= westLon && minLon <= eastLon && minLat <= northLat && maxLat >= southLat

        /** 视口是否跨过 ±180°：只有跨了才需要画 ±360° 副本，绝大多数帧都是 false。 */
        val crossesAntimeridian: Boolean = westLon < -180.0 || eastLon > 180.0

        /**
         * 数据只含 [-180,180] 的一份几何；相机经度被夹在 ±180，但视口可越界。
         * 视口跨越日界线时，把要素按 -360/0/+360 三份分别判定，命中的副本都要画，
         * 缺的那半幅才补得回来（与栅格瓦片的取模平移一致）。
         */
        fun visibleLonCopies(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<Double> {
            val out = ArrayList<Double>(1)
            for (k in -1..1) {
                val shift = k * 360.0
                if (contains(minLat, minLon + shift, maxLat, maxLon + shift)) out.add(shift)
            }
            return out
        }
    }

    private fun DrawScope.drawLayer(
        layer: VectorMapData.VectorLayer,
        frame: Frame,
        view: ViewBounds,
        gcjShift: Boolean,
        applyGcj: Boolean,
        fill: Color,
        stroke: Color,
        strokeWidthPx: Float,
        fillOf: ((String) -> Color)?,
        /** 描边是否只对「有自身填色」的要素生效。区县层据此实现 kanameishi 的平时全透明。 */
        strokeOnlyShaded: Boolean = false,
    ) {
        val w = size.width.toDouble()
        val h = size.height.toDouble()
        val geometries = geometryCache(layer, gcjShift, applyGcj)
        val centerX = projX(frame.centerLon, frame.gz)
        val centerY = projY(frame.centerLat, frame.gz)
        val offsetX = -centerX * frame.scale + w / 2.0
        val offsetY = -centerY * frame.scale + h / 2.0
        val localStrokeWidth = if (frame.scale > 0.0) strokeWidthPx / frame.scale.toFloat() else strokeWidthPx
        val transformCache = arrayOfNulls<android.graphics.Matrix>(3)
        fun transformFor(shift: Double): android.graphics.Matrix {
            val slot = when (shift.toInt()) {
                -360 -> 0
                360 -> 2
                else -> 1
            }
            return transformCache[slot] ?: android.graphics.Matrix().also { matrix ->
                matrix.setValues(floatArrayOf(
                    frame.scale.toFloat(), 0f,
                        (offsetX + shift / 360.0 * frame.size * frame.scale).toFloat(),
                    0f, frame.scale.toFloat(), offsetY.toFloat(),
                    0f, 0f, 1f,
                ))
                transformCache[slot] = matrix
            }
        }

        val nativeCanvas = drawContext.canvas.nativeCanvas
        // 同一份经度副本的变换只 concat 一次：旧实现每个要素都 save/concat/restore，
        // 区县着色时一帧要几百次。按「副本」复用，绘制顺序与逐要素处理完全一致。
        var appliedShift = Double.NaN
        var savedState = -1
        try {
            for ((index, feature) in geometries.withIndex()) {
                // 视口裁剪：包围盒落在可视区外的要素整条跳过
                //（世界层在 z>=8 时 95% 的要素都在屏外，不裁会白跑一遍 7 万顶点）。
                val b = feature.bounds
                val crosses = view.crossesAntimeridian
                if (!crosses && !view.contains(b.minLat, b.minLon, b.maxLat, b.maxLon)) continue

                val source = layer.features[index]
                val own = fillOf?.invoke(source.name) ?: Color.Transparent
                val featureFill = if (fill != Color.Transparent) fill else own
                // 区县层：仅对已着色者描边（#bbbbbb，见 kanameishi eewBaseMapActiveStroke）。
                // 平时 districtIntensity 为空，于是整层既无描边也无填充 —— 与 kanameishi 一致。
                val shaded = own != Color.Transparent
                val wantsStroke =
                    stroke != Color.Transparent && strokeWidthPx > 0f && (!strokeOnlyShaded || shaded)

                // -360 / 0 / +360：跨日界线时两侧都补齐。不跨时只有 0 一份，
                // 用 IntRange 循环而不是为每个要素分配一个 List。
                for (k in if (crosses) -1..1 else 0..0) {
                    val shift = k * 360.0
                    if (!view.contains(b.minLat, b.minLon + shift, b.maxLat, b.maxLon + shift)) continue
                    // Explicitly reproduce the old per-vertex formula:
                    // screen = world * scale + offset. Using Canvas.translate/scale
                    // here is order-dependent and made vectors drift while zooming.
                    if (shift != appliedShift) {
                        if (savedState >= 0) nativeCanvas.restoreToCount(savedState)
                        savedState = nativeCanvas.save()
                        nativeCanvas.concat(transformFor(shift))
                        appliedShift = shift
                    }
                    if (feature.hasGeometry && featureFill != Color.Transparent) {
                        drawPath(feature.path, featureFill)
                    }
                    if (feature.hasGeometry && wantsStroke) {
                        drawPath(feature.path, stroke, style = Stroke(width = localStrokeWidth))
                    }
                }
            }
        } finally {
            if (savedState >= 0) nativeCanvas.restoreToCount(savedState)
        }
    }

    private fun geometryCache(
        layer: VectorMapData.VectorLayer,
        gcjShift: Boolean,
        applyGcj: Boolean,
    ): List<CachedFeature> {
        val key = GeometryCacheKey(layer.name, gcjShift, applyGcj)
        return geometryCaches.getOrPut(key) {
            layer.features.map { feature ->
                val path = Path().apply { fillType = PathFillType.EvenOdd }
                var any = false
                for (ring in feature.polygon) {
                    if (ring.size < 3) continue
                    for (i in 0 until ring.size) {
                        var lat = ring.lat(i)
                        var lon = ring.lon(i)
                        if (gcjShift && applyGcj) {
                            val shifted = CoordinateTransform.wgs84ToGcj02(lat, lon)
                            lat = shifted.first
                            lon = shifted.second
                        }
                        val x = projX(lon, kRefZoom).toFloat()
                        val y = projY(lat, kRefZoom).toFloat()
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    any = true
                }
                CachedFeature(GeoGeometry.boundsOf(feature.polygon), path, any)
            }
        }
    }

    companion object {
        const val ASSET_PATH = "map_bounds.kvb"

        /** 从 assets 加载；缺失或损坏时返回 null（地图退回纯栅格）。 */
        fun load(context: Context): VectorMapRenderer? = try {
            context.assets.open(ASSET_PATH).use {
                VectorMapRenderer(VectorMapData.loadFromBytes(it.readBytes()))
            }
        } catch (_: Exception) {
            null
        }

        /** 从字节构造，供 JVM 单测使用（不依赖 Context）。 */
        fun loadFromBytes(bytes: ByteArray): VectorMapRenderer? =
            VectorMapRenderer(VectorMapData.loadFromBytes(bytes))

        /**
         * 供单测直接验证视口裁剪。纬度的 min/max 用法与经度相反，极易写错，
         * 故把判定单独暴露出来覆盖测试（写反会让矢量图整体空白）。
         */
        fun viewportContainsForTest(
            centerLat: Double, centerLon: Double, zoom: Float, width: Float, height: Float,
            minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        ): Boolean {
            val frame = Frame(centerLat, centerLon, zoom)
            return ViewBounds(frame, width, height).contains(minLat, minLon, maxLat, maxLon)
        }

        /**
         * 供单测直接验证经度环绕：返回该要素需要绘制的经度副本偏移（-360/0/+360 的子集）。
         * 跨日界线的视口两端都应各有一份，否则会出现「半幅地图消失」。
         */
        fun visibleLonCopiesForTest(
            centerLat: Double, centerLon: Double, zoom: Float, width: Float, height: Float,
            minLat: Double, minLon: Double, maxLat: Double, maxLon: Double,
        ): List<Double> {
            val frame = Frame(centerLat, centerLon, zoom)
            return ViewBounds(frame, width, height).visibleLonCopies(minLat, minLon, maxLat, maxLon)
        }

        /**
         * 算出「区县名 -> CSIS 烈度」。纯计算，供工作线程调用。
         *
         * 影响半径剪枝是关键：超出该距离烈度已低于可感下限，直接跳过，
         * 避免对 1011 个区县全量算点到多边形距离（低端机约 15-40 ms）。
         *
         * 「区内取 0」是**有意的保守选择**：地震预警里显示更高烈度属于安全侧，
         * 而区内采样平均既更贵、又落在不安全侧。
         */
        fun computeDistrictIntensity(
            renderer: VectorMapRenderer,
            magnitude: Double,
            depthKm: Double,
            lat: Double,
            lon: Double,
        ): Map<String, Double> {
            val layer = renderer.district ?: return emptyMap()
            if (magnitude <= 0.0) return emptyMap()
            val radiusKm = IntensityCalculator.distanceForCsis(magnitude, depthKm, 1.0)
            if (radiusKm <= 0.0) return emptyMap()

            val out = HashMap<String, Double>()
            for (feature in layer.features) {
                val b = GeoGeometry.boundsOf(feature.polygon)
                // 包围盒离震中就超过影响半径的区县必不受影响，先按保守上界剪掉。
                if (b.maxDistanceKm(lat, lon) > radiusKm) continue
                val dist = GeoGeometry.distanceToPolygonKm(feature.polygon, lat, lon)
                val raw = IntensityCalculator.rawCsis(magnitude, dist, depthKm)
                // 低于可感下限不着色，否则整片中国会被灰色铺满。
                if (raw >= 0.5) out[feature.name] = raw
            }
            return out
        }
    }
}

/**
 * CSIS 烈度色，**满不透明**（对齐 kanameishi 的 classNameColors + fillOpacity: 1）。
 * 等级靠「底色 + #bbbbbb 描边」区分，不靠透明度。
 */
private fun intensityFill(raw: Double): Color =
    Color(SeismicColors.intensityColor(raw).toInt() or (0xFF shl 24))

/** 区县已着色者的描边色：kanameishi eewBaseMapActiveStroke = #bbbbbb。 */
private val DISTRICT_STROKE = Color(0xFFBBBBBB)

/** 几何投影的固定参考层级（对齐桌面端 VectorMapItem::kRefZoom）。 */
private const val kRefZoom = 10

private fun unprojLon(x: Double, size: Double) = x / size * 360.0 - 180.0

private fun unprojLat(y: Double, size: Double): Double {
    val n = Math.PI - 2.0 * Math.PI * y / size
    return 180.0 / Math.PI * Math.atan(0.5 * (Math.exp(n) - Math.exp(-n)))
}
