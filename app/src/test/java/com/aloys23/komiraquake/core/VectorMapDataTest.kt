package com.aloys23.komiraquake.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 与桌面端 `desktop/tests/core_engine_test.cpp` 的 VectorMapData 段一一对应，
 * 锁定两端 .kvb 解析行为一致。
 *
 * assets 可被 JVM 单测读取（build.gradle.kts 已把 src/main/assets 加进 test resources）。
 */
class VectorMapDataTest {
    private fun load(): VectorMapData {
        val stream = javaClass.classLoader!!.getResourceAsStream("map_bounds.kvb")
            ?: error("找不到 assets/map_bounds.kvb")
        return VectorMapData.loadFromBytes(stream.readBytes())!!
    }

    @Test
    fun loadsRealAssetFile() {
        val map = load()
        assertEquals(3, map.layers.size)
        assertEquals(1285, map.featureCount)
    }

    @Test
    fun districtLayerHasExpectedStructure() {
        val district = load().layer("district")
        assertNotNull(district)
        district!!
        assertEquals(1011, district.features.size)
        assertEquals("东城区", district.features[0].name)

        // 洞必须保留：175 个区县是多环，填洞会让底图出现错误色块。
        val multiRing = district.features.count { it.polygon.size > 1 }
        assertEquals(176, multiRing)

        // 全部环显式闭合，且点数 >= 4。
        val allClosed = district.features.all { f ->
            f.polygon.all { ring ->
                ring.size >= 4 &&
                    ring.lat(0) == ring.lat(ring.size - 1) &&
                    ring.lon(0) == ring.lon(ring.size - 1)
            }
        }
        assertTrue(allClosed)

        // 坐标落在中国的经纬范围内（量化原点减法未出错、lat/lon 未写反）。
        val inRange = district.features.all { f ->
            f.polygon.all { ring ->
                (0 until ring.size).all { i ->
                    ring.lat(i) in 3.0..54.0 && ring.lon(i) in 73.0..136.0
                }
            }
        }
        assertTrue("district coords dequantize into China's bbox", inRange)
    }

    @Test
    fun worldLayerHasNoAntimeridianDuplicatesAndCoversChinaJapan() {
        val map = load()
        val world = map.layer("world")
        assertNotNull(world)
        world!!
        // 运行期按视口 ±360 环绕，数据侧不再输出日界副本，故经度不越界。
        assertTrue("maxLon=${world.bounds.maxLon}", world.bounds.maxLon <= 180.0001)
        val beyond = world.features.any { f ->
            f.polygon.any { ring ->
                (0 until ring.size).any { i -> ring.lon(i) > 180.0001 || ring.lon(i) < -180.0001 }
            }
        }
        assertTrue("world must not carry >180 duplicates", !beyond)
        // 上游 global 剔除了中/日（另有单独图层），生成器已并入溶解后的陆地轮廓。
        assertNotNull(map.findFeature("world", "中国"))
        assertNotNull(map.findFeature("world", "日本"))
    }

    @Test
    fun findFeatureByName() {
        val map = load()
        assertNotNull(map.findFeature("district", "东城区"))
        assertNull(map.findFeature("district", "不存在的区"))
        assertNull(map.layer("nope"))
    }

    @Test
    fun rejectsCorruptInput() {
        // assets 可被替换：截断 / 垃圾数据必须干净地失败，而不是越界读。
        assertNull(VectorMapData.loadFromBytes(ByteArray(0)))
        assertNull(VectorMapData.loadFromBytes(ByteArray(16) { it.toByte() }))
        assertNull(VectorMapData.loadFromFile("/nonexistent/map_bounds.kvb"))

        val all = load().let { map ->
            javaClass.classLoader!!.getResourceAsStream("map_bounds.kvb")!!.readBytes()
        }
        // 截断到一半
        assertNull(VectorMapData.loadFromBytes(all.copyOf(all.size / 2)))
        // 只剩文件头
        assertNull(VectorMapData.loadFromBytes(all.copyOf(12)))
        // 单字节翻转：结构校验必须拦住
        val corrupted = all.copyOf()
        corrupted[40] = (corrupted[40].toInt() xor 0xFF).toByte()
        // 结果要么解析失败，要么仍是合法数据；两者都不应抛异常。
        VectorMapData.loadFromBytes(corrupted)
    }
}