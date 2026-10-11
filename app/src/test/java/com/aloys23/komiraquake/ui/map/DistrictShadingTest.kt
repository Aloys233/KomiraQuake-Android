package com.aloys23.komiraquake.ui.map

import com.aloys23.komiraquake.core.IntensityCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 区县 EEW 烈度着色。与桌面端 `desktop/tests/core_engine_test.cpp` 的对应段口径一致。
 */
class DistrictShadingTest {
    private fun renderer(): VectorMapRenderer {
        val stream = javaClass.classLoader!!.getResourceAsStream(VectorMapRenderer.ASSET_PATH)
            ?: error("找不到 assets/${VectorMapRenderer.ASSET_PATH}")
        return VectorMapRenderer.loadFromBytes(stream.readBytes())!!
    }

    @Test
    fun shadingIsEmptyWithoutEvent() {
        val r = renderer()
        // 震级 <= 0 或影响半径为 0 时不着色。
        assertTrue(VectorMapRenderer.computeDistrictIntensity(r, 0.0, 10.0, 39.9, 116.4).isEmpty())
        assertTrue(VectorMapRenderer.computeDistrictIntensity(r, -3.0, 10.0, 39.9, 116.4).isEmpty())
    }

    @Test
    fun shadingCoversEpicentralAreaOnly() {
        val r = renderer()
        // 强震在唐山：应有若干区县被着色，但不可能覆盖全部 1011 个。
        val shaded = VectorMapRenderer.computeDistrictIntensity(r, 7.0, 10.0, 39.63, 118.18)
        assertTrue("shaded=${shaded.size}", shaded.isNotEmpty())
        assertTrue("shaded=${shaded.size}", shaded.size < 1011)
    }

    @Test
    fun intensityDecreasesWithDistance() {
        val r = renderer()
        // 同一次地震、两个远离震中的查询点：越远，着色的最高烈度越低。
        fun peak(magnitude: Double, lat: Double, lon: Double) =
            VectorMapRenderer.computeDistrictIntensity(r, magnitude, 10.0, lat, lon).values.maxOrNull() ?: 0.0

        val nearShanghai = peak(6.5, 39.63, 118.18)
        val farFromEpicenter = peak(6.5, 21.5, 108.0)
        assertTrue(
            "near=$nearShanghai far=$farFromEpicenter",
            nearShanghai > farFromEpicenter,
        )
    }

    @Test
    fun biggerEarthquakeCoversMoreDistricts() {
        val r = renderer()
        val small = VectorMapRenderer.computeDistrictIntensity(r, 5.0, 10.0, 39.63, 118.18).size
        val big = VectorMapRenderer.computeDistrictIntensity(r, 7.5, 10.0, 39.63, 118.18).size
        assertTrue("small=$small big=$big", big > small)
    }

    @Test
    fun shadedValuesMatchCsisFormula() {
        val r = renderer()
        val shaded = VectorMapRenderer.computeDistrictIntensity(r, 7.0, 10.0, 39.63, 118.18)
        // 每个着色值都应落在「该震级下距震中 0..影响半径」的烈度区间内。
        val radiusKm = IntensityCalculator.distanceForCsis(7.0, 10.0, 1.0)
        assertTrue("radius=$radiusKm", radiusKm > 0.0)
        val atEpicenter = IntensityCalculator.rawCsis(7.0, 0.0, 10.0)
        for ((name, raw) in shaded) {
            assertTrue("$name raw=$raw", raw in 0.5..atEpicenter + 1e-6)
        }
    }

    @Test
    fun viewportCullingKeepsVisibleFeatures() {
        // 回归：纬度方向的重叠判定曾写反（用 maxLat>=top 而非 minLat<=northLat），
        // 导致视口内的要素被全部裁掉、矢量图一片空白。
        // 北京 (39.9,116.4) 在 1280x800、z4 的视口内，必须判定为可见。
        val bjLat = 39.9042
        val bjLon = 116.4074
        val visible = VectorMapRenderer.viewportContainsForTest(
            centerLat = bjLat, centerLon = bjLon, zoom = 4f, width = 1280f, height = 800f,
            minLat = 39.4, minLon = 115.9, maxLat = 40.4, maxLon = 116.9,
        )
        assertTrue("Beijing must be inside its own viewport", visible)

        // 视线中心在南半球时，北京应被裁掉。
        val faraway = VectorMapRenderer.viewportContainsForTest(
            centerLat = -33.9, centerLon = 116.4, zoom = 4f, width = 1280f, height = 800f,
            minLat = 39.4, minLon = 115.9, maxLat = 40.4, maxLon = 116.9,
        )
        assertFalse("Beijing must be culled when camera is over Australia", faraway)
    }

    @Test
    fun rendererExposesAllLayers() {
        val r = renderer()
        assertNotNull(r.world)
        assertNotNull(r.province)
        assertNotNull(r.district)
        assertEquals(1011, r.district!!.features.size)
    }

    @Test
    fun worldLayerIncludesChinaAndJapan() {
        // 回归：上游 global 文件剔除了中日台（kanameishi 用单独图层渲染），
        // 纯矢量模式下它们曾是「洞」——日本整块不见。生成器已并入溶解后的陆地。
        val names = renderer().world!!.features.map { it.name }.toSet()
        assertTrue("world layer must include China land", "中国" in names)
        assertTrue("world layer must include Japan land", "日本" in names)
    }

    @Test
    fun viewportWrapsAcrossAntimeridian() {
        // 回归：相机经度被夹在 ±180，但视口可越界；数据只含 [-180,180] 一份几何。
        // 视口跨日界线时，另一侧要素必须靠 ±360 副本画出来，否则半幅地图消失。
        // centerLon=179、z4、1280x800 时视口经度约为 [123, 235]：
        // 堪察加(~160E) 命中 0 号副本，阿拉斯加(~-150) 命中 +360 号副本。
        val kamchatka = VectorMapRenderer.visibleLonCopiesForTest(
            centerLat = 45.0, centerLon = 179.0, zoom = 4f, width = 1280f, height = 800f,
            minLat = 50.0, minLon = 158.0, maxLat = 62.0, maxLon = 165.0,
        )
        assertTrue("Kamchatka must be visible at shift 0: $kamchatka", kamchatka.contains(0.0))

        val alaska = VectorMapRenderer.visibleLonCopiesForTest(
            centerLat = 45.0, centerLon = 179.0, zoom = 4f, width = 1280f, height = 800f,
            minLat = 55.0, minLon = -165.0, maxLat = 70.0, maxLon = -140.0,
        )
        assertTrue("Alaska must be visible via +360: $alaska", alaska.contains(360.0))

        // 远离日界线的要素只该命中 0 号副本，不该被重复画。
        val china = VectorMapRenderer.visibleLonCopiesForTest(
            centerLat = 35.0, centerLon = 105.0, zoom = 4f, width = 1280f, height = 800f,
            minLat = 20.0, minLon = 100.0, maxLat = 45.0, maxLon = 120.0,
        )
        assertEquals(listOf(0.0), china)
    }

    @Test
    fun noEventMeansNothingToDraw() {
        // 对齐 kanameishi 的 eewBaseMapDefaultStroke/Fill（alpha=0）：
        // 没有事件时 districtIntensity 为空，区县层既不描边也不填色，
        // 地图上不会出现铺满 1011 条描边的网格。
        val r = renderer()
        assertTrue(r.districtIntensity.isEmpty())
        // 有事件时才有值，且值域与 CSIS 配色档位一致。
        r.districtIntensity = VectorMapRenderer.computeDistrictIntensity(r, 7.0, 10.0, 39.63, 118.18)
        assertTrue(r.districtIntensity.isNotEmpty())
        // 只有烈度 >= 0.5 的区县会出现在表里 —— 低于可感下限的不该被点亮。
        assertTrue(r.districtIntensity.values.all { it >= 0.5 })
    }
}
