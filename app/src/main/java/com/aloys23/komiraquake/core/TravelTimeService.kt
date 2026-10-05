package com.aloys23.komiraquake.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * P/S 走时表插值。《NATIVE_PORT_SPEC》 §4.2。
 * 资源 travel_times.json 结构：{ "<table>": { depths, distances, p_times, s_times } }，
 * p_times/s_times 为 [depthIndex][distanceIndex]（秒）。
 */
object TravelTimeService {
    const val DEFAULT_TABLE = "jma2001"
    const val ASSET_PATH = "travel_times.json"

    private class Table(
        val depths: DoubleArray,
        val distances: DoubleArray,
        val pTimes: Array<DoubleArray>,
        val sTimes: Array<DoubleArray>,
    )

    private class Bracket(val lo: Int, val hi: Int, val weight: Double)

    @Volatile
    private var tables: Map<String, Table>? = null

    val isLoaded: Boolean get() = tables != null
    val availableTables: Set<String> get() = tables?.keys ?: emptySet()

    fun loadFromString(raw: String) {
        val root = JSONObject(raw)
        val parsed = HashMap<String, Table>()
        for (name in root.keys()) {
            val obj = root.getJSONObject(name)
            parsed[name] = Table(
                depths = toDoubleArray(obj.getJSONArray("depths")),
                distances = toDoubleArray(obj.getJSONArray("distances")),
                pTimes = toMatrix(obj.getJSONArray("p_times")),
                sTimes = toMatrix(obj.getJSONArray("s_times")),
            )
        }
        tables = parsed
    }

    fun estimateTravelTimes(
        depthKm: Double,
        distanceKm: Double,
        table: String = DEFAULT_TABLE,
    ): Pair<Double, Double> {
        val t = tables?.get(table) ?: return QuakeCalculator.estimateTravelTimes(distanceKm, depthKm)
        val db = bracket(t.depths, depthKm)
        val p = lerp(
            interpRow(t.distances, t.pTimes[db.lo], distanceKm),
            interpRow(t.distances, t.pTimes[db.hi], distanceKm),
            db.weight,
        )
        val s = lerp(
            interpRow(t.distances, t.sTimes[db.lo], distanceKm),
            interpRow(t.distances, t.sTimes[db.hi], distanceKm),
            db.weight,
        )
        return Pair(p, s)
    }

    fun distanceForTime(
        depthKm: Double,
        seconds: Double,
        isPWave: Boolean,
        table: String = DEFAULT_TABLE,
    ): Double {
        val t = tables?.get(table)
        if (t == null) {
            return seconds * (if (isPWave) QuakeCalculator.P_WAVE_SPEED else QuakeCalculator.S_WAVE_SPEED)
        }
        val db = bracket(t.depths, depthKm)
        val dLo = invertRow(t.distances, if (isPWave) t.pTimes[db.lo] else t.sTimes[db.lo], seconds)
        val dHi = invertRow(t.distances, if (isPWave) t.pTimes[db.hi] else t.sTimes[db.hi], seconds)
        return lerp(dLo, dHi, db.weight)
    }

    private fun interpRow(distances: DoubleArray, times: DoubleArray, d: Double): Double {
        val b = bracket(distances, d)
        return lerp(times[b.lo], times[b.hi], b.weight)
    }

    private fun invertRow(distances: DoubleArray, times: DoubleArray, seconds: Double): Double {
        if (times.isEmpty()) return 0.0
        if (seconds <= times.first()) return distances.first()
        if (seconds >= times.last()) return distances.last()
        var i = 0
        while (i < times.size - 1 && times[i + 1] < seconds) i++
        val span = times[i + 1] - times[i]
        val w = if (span <= 0.0) 0.0 else (seconds - times[i]) / span
        return lerp(distances[i], distances[i + 1], w)
    }

    private fun bracket(values: DoubleArray, v: Double): Bracket {
        if (values.isEmpty()) return Bracket(0, 0, 0.0)
        if (v <= values.first()) return Bracket(0, 0, 0.0)
        if (v >= values.last()) return Bracket(values.size - 1, values.size - 1, 0.0)
        var lo = 0
        while (lo < values.size - 1 && values[lo + 1] < v) lo++
        val hi = lo + 1
        val span = values[hi] - values[lo]
        val w = if (span <= 0.0) 0.0 else (v - values[lo]) / span
        return Bracket(lo, hi, w)
    }

    private fun lerp(a: Double, b: Double, w: Double): Double = a + (b - a) * w

    private fun toDoubleArray(arr: JSONArray): DoubleArray =
        DoubleArray(arr.length()) { arr.getDouble(it) }

    private fun toMatrix(arr: JSONArray): Array<DoubleArray> =
        Array(arr.length()) { toDoubleArray(arr.getJSONArray(it)) }
}
