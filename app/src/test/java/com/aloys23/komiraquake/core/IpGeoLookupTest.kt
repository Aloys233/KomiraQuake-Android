package com.aloys23.komiraquake.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IpGeoLookupTest {
    @Before
    fun setUp() {
        CityCoordTable.loadFromString(
            """
            {
              "provinces": {"四川": [30.65, 104.07], "北京": [39.9, 116.4]},
              "cities": {"四川|广安": [30.45, 106.63]},
              "cities_unique": {"广安": [30.45, 106.63]}
            }
            """.trimIndent(),
        )
    }

    @Test
    fun resolvesExactThenByCityThenByProvince() {
        assertTrue(CityCoordTable.isLoaded)
        // 省市精确（两侧都归一化后缀）。
        val exact = CityCoordTable.resolve("四川省", "广安市")!!
        assertEquals(30.45, exact.first, 1e-9)
        assertEquals(106.63, exact.second, 1e-9)
        // 市名命中但省份不同 → 唯一市名回退。
        val byCity = CityCoordTable.resolve("未知省", "广安市")!!
        assertEquals(30.45, byCity.first, 1e-9)
        // 市查不到 → 省级中心回退（直辖市走这条）。
        val byProvince = CityCoordTable.resolve("北京市", "北京市")!!
        assertEquals(39.9, byProvince.first, 1e-9)
        // 完全无匹配。
        assertNull(CityCoordTable.resolve("不存在省", "不存在市"))
    }

    @Test
    fun parsesRegionFromResponse() {
        val cn = IpGeoParser.parse(
            """{"ip":"1.2.3.4","location":{"country":"中国","province":"四川省","city":"广安市"}}""",
        )!!
        assertEquals("四川省", cn.province)
        assertEquals("广安市", cn.city)
        // anycast / 非 CN：无 location 字段。
        assertNull(IpGeoParser.parse("""{"ip":"1.1.1.1","asn":{"number":13335}}"""))
        assertNull(IpGeoParser.parse("not json"))
    }

    @Test
    fun rejectsEmptyTable() {
        assertFalse(CityCoordTable.loadFromString("{}"))
    }
}
