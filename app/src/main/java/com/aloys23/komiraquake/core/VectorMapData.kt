package com.aloys23.komiraquake.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 地图矢量边界数据（.kvb 二进制）。由 tools/gen_vector_map.py 生成。
 *
 * 与桌面端 `desktop/src/core/vector_map_data.cpp` 同格式、同校验策略，是有意维护的
 * 跨端同源契约。
 *
 * 安全：assets/ 可被替换，文件可能被截断或改坏，故解析全程做边界检查 —— 任何字段的
 * 长度/偏移都在使用前校验，越界即整体失败（返回 null），不做局部恢复。
 */
class VectorMapData private constructor(
    private val buffer: ByteBuffer,
    val layers: List<VectorLayer>,
) {
    /** 一个地图要素（省 / 区县 / 洲）：名称 + 全部环（首环为外环，其余为洞）。 */
    class VectorFeature(val name: String, val polygon: List<List<Pair<Double, Double>>>)

    class VectorLayer(
        val name: String,
        val features: List<VectorFeature>,
        val bounds: GeoGeometry.GeoBounds,
        val unit: Double,
        val originLon: Double,
        val originLat: Double,
    )

    /** 已加载的要素总数，用于日志与自检。 */
    val featureCount: Int get() = layers.sumOf { it.features.size }

    private val layerIndex = layers.withIndex().associate { (i, l) -> l.name to i }

    /** 取层；不存在返回 null。 */
    fun layer(name: String): VectorLayer? = layerIndex[name]?.let { layers[it] }

    /** 按名称查找要素。找不到返回 null。 */
    fun findFeature(layerName: String, featureName: String): VectorFeature? =
        layer(layerName)?.features?.firstOrNull { it.name == featureName }

    /** 原始字节，供渲染层零拷贝读取（只读视图）。 */
    fun rawBuffer(): ByteBuffer = buffer.asReadOnlyBuffer()

    companion object {
        private val MAGIC = byteArrayOf('K'.code.toByte(), 'Q'.code.toByte(), 'V'.code.toByte(), 'B'.code.toByte())
        private const val VERSION = 1

        /** 与生成器一致：墨卡托纬度截断。 */
        private const val LAT_LIMIT = 85.05112878

        /** 图层数上限，挡住「长度字段被改成垃圾值」导致的巨额循环。 */
        private const val MAX_LAYERS = 64

        private const val MAX_FEATURES = 1_000_000

        /**
         * 单个要素允许的最大环数/顶点数。挡住长度字段被改坏后触发的巨额分配。
         * 实测最大环数 141，故留足余量。
         */
        private const val MAX_RINGS = 4096
        private const val MAX_POINTS_PER_RING = 1 shl 20

        /** 从文件加载。失败（不存在 / 魔数不符 / 越界 / 截断）返回 null。 */
        fun loadFromFile(path: String): VectorMapData? {
            val file = File(path)
            if (!file.isFile) return null
            return file.inputStream().use { loadFromBytes(it.readBytes()) }
        }

        /** 从内存加载，便于单测。 */
        fun loadFromBytes(bytes: ByteArray): VectorMapData? {
            if (bytes.size < 12) return null
            for (i in 0 until 4) {
                if (bytes[i] != MAGIC[i]) return null
            }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            // 必须跳过魔数再读头部：只校验不前进会让后续字段整体错位。
            buf.position(4)
            val version = buf.short.toInt() and 0xFFFF
            val layerCount = buf.short.toInt() and 0xFFFF
            buf.int // reserved
            if (version != VERSION || layerCount == 0 || layerCount > MAX_LAYERS) return null

            val layers = ArrayList<VectorLayer>(layerCount)
            for (li in 0 until layerCount) {
                val name = buf.readString() ?: return null
                val minLon = buf.readFinite() ?: return null
                val minLat = buf.readFinite() ?: return null
                val maxLon = buf.readFinite() ?: return null
                val maxLat = buf.readFinite() ?: return null
                val originLon = buf.readFinite() ?: return null
                val originLat = buf.readFinite() ?: return null
                val step = buf.int
                val featureCount = buf.int
                if (step <= 0 || featureCount < 0 || featureCount > MAX_FEATURES) return null
                val unit = step * 1e-5

                // bbox 表（featureCount × 4 个 i32）。跳过但需校验长度，避免后续偏移错位。
                if (!buf.canRead(featureCount.toLong() * 16)) return null
                buf.position(buf.position() + featureCount * 16)

                // offset 表（featureCount+1 个 u32）
                if (!buf.canRead(featureCount.toLong() * 4 + 4)) return null
                val offsets = IntArray(featureCount + 1)
                for (i in offsets.indices) offsets[i] = buf.int

                // 偏移必须单调不减，且特征区整体在文件内。
                val featureBase = buf.position()
                for (i in 0 until featureCount) {
                    if (offsets[i] > offsets[i + 1]) return null
                }
                if (featureCount > 0) {
                    val span = offsets[featureCount]
                    if (span < 0 || featureBase.toLong() + span > buf.limit()) return null
                }

                val features = ArrayList<VectorFeature>(featureCount)
                for (fi in 0 until featureCount) {
                    val blockEnd = featureBase + offsets[fi + 1]
                    buf.position(featureBase + offsets[fi])
                    val fname = buf.readString() ?: return null
                    val ringCount = buf.short.toInt() and 0xFFFF
                    if (ringCount > MAX_RINGS) return null

                    val polygon = ArrayList<List<Pair<Double, Double>>>(ringCount)
                    for (ri in 0 until ringCount) {
                        val pointCount = buf.int
                        // 三角形才有面积；上限挡住被改坏的长度字段。
                        if (pointCount < 3 || pointCount > MAX_POINTS_PER_RING) return null
                        // 读前先确认这一环的字节数在本块内。
                        if (pointCount.toLong() * 8 > (blockEnd - buf.position())) return null

                        val ring = ArrayList<Pair<Double, Double>>(pointCount)
                        for (pi in 0 until pointCount) {
                            val qx = buf.int
                            val qy = buf.int
                            val lon = qx * unit + originLon
                            val lat = (qy * unit + originLat).coerceIn(-LAT_LIMIT, LAT_LIMIT)
                            // (lat, lon) 顺序 —— 与 GeoGeometry 的取值口径一致。
                            ring.add(lat to lon)
                        }
                        polygon.add(ring)
                    }
                    features.add(VectorFeature(fname, polygon))
                }

                // 游标复位到本层特征区末尾，继续读下一层。
                if (featureCount > 0) buf.position(featureBase + offsets[featureCount])
                layers.add(
                    VectorLayer(
                        name = name,
                        features = features,
                        bounds = GeoGeometry.GeoBounds(minLat, minLon, maxLat, maxLon),
                        unit = unit,
                        originLon = originLon,
                        originLat = originLat,
                    ),
                )
            }
            return VectorMapData(buf, layers)
        }

        private fun ByteBuffer.canRead(bytes: Long): Boolean =
            position() >= 0 && bytes >= 0 && bytes <= (limit() - position()).toLong()

        /** 读 u8 长度前缀的 UTF-8 串；越界或长度离谱返回 null。 */
        private fun ByteBuffer.readString(): String? {
            if (!canRead(1)) return null
            val len = get().toInt() and 0xFF
            if (len == 0) return ""
            if (!canRead(len.toLong())) return null
            val bytes = ByteArray(len)
            get(bytes)
            return String(bytes, Charsets.UTF_8)
        }

        /** 读 f64 并拒绝 NaN/Inf —— 垃圾数据会污染后续所有几何计算。 */
        private fun ByteBuffer.readFinite(): Double? {
            if (!canRead(8)) return null
            val v = double
            return if (v.isFinite()) v else null
        }
    }
}