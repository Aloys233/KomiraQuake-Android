package com.aloys23.komiraquake.core

import org.json.JSONObject

/** IP 定位接口返回的行政区（仅省 + 市；接口不含经纬度）。 */
data class IpGeoRegion(val province: String, val city: String)

/**
 * 解析 `https://api.aloys23.link/api/v1/network/location` 响应，取 `location.province/city`。
 * 无 `location` 字段（非中国大陆 / anycast IP）或非法 JSON 时返回 null。
 */
object IpGeoParser {
    fun parse(body: String): IpGeoRegion? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val location = root.optJSONObject("location") ?: return null
        val province = location.optString("province").trim()
        val city = location.optString("city").trim()
        if (province.isEmpty() && city.isEmpty()) return null
        return IpGeoRegion(province, city)
    }
}

/**
 * 「省 + 市」映射到城市中心坐标（高德 GCJ-02，城市级近似；不转换坐标系）。
 * 资源 `china_cities.json`，与桌面端 `core/ip_geo_lookup` 的归一化与回退顺序保持一致。
 */
object CityCoordTable {
    const val ASSET_PATH = "china_cities.json"

    // 与 tools/gen_china_cities.py 的归一化规则保持一致。
    private val provinceSuffixes = listOf(
        "维吾尔自治区", "壮族自治区", "回族自治区", "特别行政区", "自治区", "省", "市",
    )
    private val citySuffixes = listOf("自治州", "地区", "盟", "市", "特别行政区")

    private var provinces: Map<String, Pair<Double, Double>>? = null
    private var cities: Map<String, Pair<Double, Double>>? = null
    private var citiesUnique: Map<String, Pair<Double, Double>>? = null

    val isLoaded: Boolean get() = cities != null

    fun loadFromString(raw: String): Boolean {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        val parsedProvinces = readMap(root.optJSONObject("provinces"))
        val parsedCities = readMap(root.optJSONObject("cities"))
        val parsedUnique = readMap(root.optJSONObject("cities_unique"))
        if (parsedProvinces.isEmpty() && parsedCities.isEmpty()) return false
        provinces = parsedProvinces
        cities = parsedCities
        citiesUnique = parsedUnique
        return true
    }

    /** 归一化多级回退：`省市` 精确 → 市名唯一 → 省级中心。命中返回 (lat, lon)。 */
    fun resolve(province: String, city: String): Pair<Double, Double>? {
        val p = provinces ?: return null
        val c = cities ?: return null
        val np = normalize(province, provinceSuffixes)
        val nc = normalize(city, citySuffixes)
        if (nc.isNotEmpty()) {
            c["$np|$nc"]?.let { return it }
            citiesUnique?.get(nc)?.let { return it }
        }
        if (np.isNotEmpty()) p[np]?.let { return it }
        return null
    }

    private fun readMap(obj: JSONObject?): Map<String, Pair<Double, Double>> {
        if (obj == null) return emptyMap()
        val out = HashMap<String, Pair<Double, Double>>()
        for (key in obj.keys()) {
            val arr = obj.optJSONArray(key) ?: continue
            if (arr.length() != 2) continue
            val lat = arr.optDouble(0, Double.NaN)
            val lon = arr.optDouble(1, Double.NaN)
            if (!lat.isNaN() && !lon.isNaN()) out[key] = lat to lon
        }
        return out
    }

    private fun normalize(name: String, suffixes: List<String>): String {
        val trimmed = name.trim()
        for (suffix in suffixes) {
            if (trimmed.endsWith(suffix) && trimmed.length > suffix.length)
                return trimmed.dropLast(suffix.length)
        }
        return trimmed
    }
}
