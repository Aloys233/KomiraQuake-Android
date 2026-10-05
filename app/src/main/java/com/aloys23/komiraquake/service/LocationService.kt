package com.aloys23.komiraquake.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import com.aloys23.komiraquake.core.CityCoordTable
import com.aloys23.komiraquake.core.IpGeoParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

enum class LocationSource { UNKNOWN, NATIVE, IP_FALLBACK, MANUAL }
enum class LocationStatus { IDLE, LOCATING, AVAILABLE, SERVICE_DISABLED, PERMISSION_DENIED, FAILED }

data class LocationState(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val name: String = "未设置定位",
    val source: LocationSource = LocationSource.UNKNOWN,
    val status: LocationStatus = LocationStatus.IDLE,
) {
    val hasLocation: Boolean get() = latitude != null && longitude != null
}

/**
 * 定位：系统 GPS/网络定位优先，失败回退 IP 定位。《NATIVE_PORT_SPEC》 §11。
 * 定位结果持久化：手动与 IP 定位都落盘，构造时恢复；有记录就不再自动 IP。
 */
class LocationService(
    private val context: Context,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(LocationState())
    val state: kotlinx.coroutines.flow.StateFlow<LocationState> = _state

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    init {
        restore()
    }

    /** 启动时恢复上次定位（手动或 IP）。 */
    fun restore() {
        val latitude = prefs.getString(KEY_LATITUDE, null)?.toDoubleOrNull() ?: return
        val longitude = prefs.getString(KEY_LONGITUDE, null)?.toDoubleOrNull() ?: return
        val name = prefs.getString(KEY_NAME, null) ?: "未设置定位"
        val source = prefs.getString(KEY_SOURCE, null)
            ?.let { runCatching { LocationSource.valueOf(it) }.getOrNull() }
            ?: LocationSource.MANUAL
        _state.value = LocationState(latitude, longitude, name, source, LocationStatus.AVAILABLE)
    }

    fun requestCurrentPosition() {
        scope.launch {
            _state.value = _state.value.copy(status = LocationStatus.LOCATING)
            val native = requestNativePosition()
            if (native != null) {
                setState(native)
                return@launch
            }
            val ip = requestIpPosition()
            if (ip != null) setState(ip)
            else _state.value = _state.value.copy(status = LocationStatus.FAILED)
        }
    }

    fun setManual(latitude: Double, longitude: Double, label: String? = null) {
        setState(
            LocationState(
                latitude = latitude,
                longitude = longitude,
                name = if (label.isNullOrBlank()) "手动定位 · $latitude, $longitude" else "手动定位 · $label",
                source = LocationSource.MANUAL,
                status = LocationStatus.AVAILABLE,
            ),
        )
    }

    private fun setState(state: LocationState) {
        _state.value = state
        state.latitude?.let { prefs.edit().putString(KEY_LATITUDE, it.toString()).apply() }
        state.longitude?.let { prefs.edit().putString(KEY_LONGITUDE, it.toString()).apply() }
        prefs.edit()
            .putString(KEY_NAME, state.name)
            .putString(KEY_SOURCE, state.source.name)
            .apply()
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private suspend fun requestNativePosition(): LocationState? {
        val lm = locationManager ?: return null
        if (!hasPermission()) {
            _state.value = _state.value.copy(status = LocationStatus.PERMISSION_DENIED)
            return null
        }
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
            !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        ) {
            _state.value = _state.value.copy(status = LocationStatus.SERVICE_DISABLED)
            return null
        }

        // 先用最近一次已知位置，快速给出结果
        val last = bestLastKnown(lm)
        if (last != null) {
            return LocationState(
                latitude = last.latitude,
                longitude = last.longitude,
                name = "设备定位 (GPS)",
                source = LocationSource.NATIVE,
                status = LocationStatus.AVAILABLE,
            )
        }

        val fresh = withTimeoutOrNull(15_000) { awaitSingleUpdate(lm) } ?: return null
        return LocationState(
            latitude = fresh.latitude,
            longitude = fresh.longitude,
            name = "设备定位 (GPS)",
            source = LocationSource.NATIVE,
            status = LocationStatus.AVAILABLE,
        )
    }

    private fun bestLastKnown(lm: LocationManager): Location? {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        var best: Location? = null
        for (p in providers) {
            if (!lm.isProviderEnabled(p)) continue
            val loc = try {
                lm.getLastKnownLocation(p)
            } catch (_: SecurityException) {
                null
            }
            if (loc != null && (best == null || loc.time > best.time)) best = loc
        }
        return best
    }

    private suspend fun awaitSingleUpdate(lm: LocationManager): Location? =
        suspendCancellableCoroutine { cont ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    lm.removeUpdates(this)
                    if (cont.isActive) cont.resume(location)
                }

                @Deprecated("Deprecated in Android")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
            }
            try {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 0L, 0f, listener, Looper.getMainLooper(),
                )
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper(),
                )
            } catch (_: SecurityException) {
                if (cont.isActive) cont.resume(null)
            }
            cont.invokeOnCancellation { lm.removeUpdates(listener) }
        }

    private suspend fun requestIpPosition(): LocationState? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.aloys23.link/api/v1/network/location")
                .header("Accept", "application/json")
                .header("User-Agent", "komiraquake/2.0")
                .build()
            val ipClient = client.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            ipClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val region = IpGeoParser.parse(body) ?: return@withContext null
                // 接口只给省市，坐标由内置城市表映射，精度为城市级。
                val coord = CityCoordTable.resolve(region.province, region.city)
                    ?: return@withContext null
                val label = (region.province + region.city).ifEmpty { "城市级" }
                LocationState(
                    latitude = coord.first,
                    longitude = coord.second,
                    name = "IP 定位 · $label",
                    source = LocationSource.IP_FALLBACK,
                    status = LocationStatus.AVAILABLE,
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val PREFS = "komira_location"
        private const val KEY_LATITUDE = "latitude"
        private const val KEY_LONGITUDE = "longitude"
        private const val KEY_NAME = "name"
        private const val KEY_SOURCE = "source"
    }
}
