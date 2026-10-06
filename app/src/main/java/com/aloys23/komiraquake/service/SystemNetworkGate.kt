package com.aloys23.komiraquake.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.aloys23.komiraquake.core.NetworkGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * 基于系统回调的网络可达性。
 *
 * 用 `registerNetworkCallback` 而非 `registerDefaultNetworkCallback`：默认网络回调在
 * WiFi→蜂窝切换的瞬间会先 `onLost` 再 `onAvailable`，而按网络请求注册则能正确表达
 * 「仍有可用网络」，避免切网期间被误判为断网而挂起重连。
 *
 * 无 [android.permission.ACCESS_NETWORK_STATE] 或注册失败时退化为 [AlwaysOnline]，
 * 即完全退回改动前的固定退避行为，不引入新的失败模式。
 */
class SystemNetworkGate(context: Context) : NetworkGate {

    private val connectivity =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _online = MutableStateFlow(true)
    override val online: StateFlow<Boolean> = _online.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null

    /** @return 是否成功注册回调；false 时本实例等价于 [NetworkGate.AlwaysOnline]。 */
    fun start(): Boolean {
        val manager = connectivity ?: return false
        if (callback != null) return true
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val registered = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { _online.value = true }
            override fun onLost(network: Network) { recompute(manager) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        }
        return runCatching { manager.registerNetworkCallback(request, registered) }
            .onSuccess { callback = registered }
            .onFailure { recompute(manager) }
            .isSuccess
            .also { if (it) recompute(manager) }
    }

    fun stop() {
        val registered = callback ?: return
        callback = null
        runCatching { connectivity?.unregisterNetworkCallback(registered) }
    }

    /**
     * `onLost` 只说明这一张网断了，可能还有蜂窝等其它网可用，因此重新查询整体连通性
     * 而不是直接置为离线。
     */
    private fun recompute(manager: ConnectivityManager) {
        val active = manager.activeNetwork
        _online.value = active != null && manager.getNetworkCapabilities(active)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    override suspend fun awaitOnline(): Boolean {
        if (_online.value) return false
        _online.first { it }
        return true
    }
}
