package com.aloys23.komiraquake.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.abs

/** HTTP 备用授时端点：JSON 字段名两端点不同。 */
private data class HttpTimeSource(val url: String, val label: String, val field: String)

/**
 * 网络授时服务：SNTP v4（UDP/123）为主，HTTP JSON 为备。《NATIVE_PORT_SPEC》 §13.2 / §13.3 / §13.5。
 *
 * 只负责取时间并把结果写入 [AppClock]；地球物理计算不在这里。
 * 测量 `T1`/`T4` 一律用**本地原始墙钟**，故 `offset` 恒为 `serverTime − localWall`，
 * 与设置页展示的"系统时间偏差"含义一致。
 */
class NtpTimeService(
    private val scope: CoroutineScope,
    private val okHttp: OkHttpClient,
) {
    private var job: Job? = null

    @Volatile private var running = false

    /** 自定义 SNTP 主机名；留空时仅用内置列表。 */
    @Volatile private var customServer: String = ""

    fun setCustomServer(host: String) {
        customServer = host
    }

    private fun sntpHosts(): List<String> {
        val custom = customServer.trim().removePrefix("ntp://").trim('/').trim()
        if (custom.isEmpty()) return SNTP_SERVERS
        return buildList {
            add(custom)
            SNTP_SERVERS.filterTo(this) { it != custom }
        }
    }

    fun start() {
        if (running) return
        running = true
        job = scope.launch {
            delay(INITIAL_DELAY_MS)
            while (isActive && running) {
                if (!AppClock.isEnabled()) {
                    delay(NORMAL_INTERVAL_MS)
                    continue
                }
                delay(calibrate())
            }
        }
    }

    fun stop() {
        running = false
        job?.cancel()
        job = null
    }

    /** 立即校准一次（不打断周期调度）。 */
    fun refresh() {
        if (!AppClock.isEnabled()) return
        scope.launch { calibrate() }
    }

    /** @return 距下次尝试的等待时长（ms）。 */
    private suspend fun calibrate(): Long {
        val sample = sampleSntp() ?: sampleHttp()
        if (sample == null) {
            // 全部失败：保留上次锚定值（优于错误的系统钟），仅刷新状态供 UI 显示。
            AppClock.refresh()
            return FAST_RETRY_MS
        }
        AppClock.applyOffset(sample.offsetMs, sample.delayMs, sample.label)
        return if (abs(sample.offsetMs) > OFFSET_FAST_THRESHOLD_MS) FAST_RETRY_MS else NORMAL_INTERVAL_MS
    }

    private data class Calibration(val offsetMs: Long, val delayMs: Long, val label: String)

    /** SNTP：每服务器 2 次、最多 3 台；拿到 3 个有效样本即提前结束。自定义服务器排在最前。 */
    private suspend fun sampleSntp(): Calibration? = withContext(Dispatchers.IO) {
        val samples = ArrayList<NtpSample>()
        var lastHost = ""
        for (host in sntpHosts().take(MAX_SERVERS)) {
            repeat(SAMPLES_PER_SERVER) {
                val sample = queryOnce(host)
                if (sample != null) {
                    samples.add(sample)
                    lastHost = host
                }
            }
            if (samples.size >= TARGET_SAMPLES) break
        }
        val best = NtpMath.chooseBest(samples, MAX_DELAY_MS) ?: return@withContext null
        Calibration(best.offsetMs, best.delayMs, "SNTP $lastHost")
    }

    private fun queryOnce(host: String): NtpSample? = try {
        DatagramSocket().use { socket ->
            socket.soTimeout = TIMEOUT_MS.toInt()
            val address = InetAddress.getByName(host)
            val t1 = System.currentTimeMillis()
            val request = NtpMath.buildRequest(t1)
            socket.send(DatagramPacket(request, request.size, address, NtpMath.port()))
            val buffer = ByteArray(NtpMath.PACKET_SIZE)
            socket.receive(DatagramPacket(buffer, buffer.size))
            val t4 = System.currentTimeMillis()
            val parsed = NtpMath.parseResponse(buffer)
            if (parsed == null) null else NtpMath.computeSample(t1, parsed, t4)
        }
    } catch (_: Exception) {
        null
    }

    /** HTTP 备用：`offset = serverMs − wallMid`，RTT 用单调时钟量测。 */
    private suspend fun sampleHttp(): Calibration? = withContext(Dispatchers.IO) {
        for (source in HTTP_SOURCES) {
            val monoStart = AppClock.elapsedMs()
            val wallStart = System.currentTimeMillis()
            val serverMs = fetchHttpTime(source) ?: continue
            val wallEnd = System.currentTimeMillis()
            val delay = AppClock.elapsedMs() - monoStart
            val wallMid = wallStart + (wallEnd - wallStart) / 2
            val sample = NtpSample(serverMs - wallMid, delay)
            if (!sample.isValid || sample.delayMs > MAX_DELAY_MS) continue
            return@withContext Calibration(sample.offsetMs, sample.delayMs, "HTTP ${source.label}")
        }
        null
    }

    private fun fetchHttpTime(source: HttpTimeSource): Long? = try {
        val request = Request.Builder()
            .url(source.url)
            .header("Cache-Control", "no-cache")
            .build()
        okHttp.newCall(request).execute().use { response ->
            val body = if (response.isSuccessful) response.body?.string() else null
            val value = body?.let { JSONObject(it).optLong(source.field, 0L) } ?: 0L
            if (value > 0L) value else null
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val TIMEOUT_MS = 5_000L
        private const val INITIAL_DELAY_MS = 2_000L
        private const val NORMAL_INTERVAL_MS = 10L * 60L * 1000L
        private const val FAST_RETRY_MS = 60L * 1000L
        private const val OFFSET_FAST_THRESHOLD_MS = 3_000L
        private const val SAMPLES_PER_SERVER = 2
        private const val MAX_SERVERS = 3
        private const val TARGET_SAMPLES = 3
        private const val MAX_DELAY_MS = 5L * TIMEOUT_MS

        private val SNTP_SERVERS = listOf(
            "ntp.aliyun.com",
            "ntp1.aliyun.com",
            "ntp.tencent.com",
            "pool.ntp.org",
            "time.apple.com",
        )

        private val HTTP_SOURCES = listOf(
            HttpTimeSource("https://api.wolfx.jp/ntp.json", "api.wolfx.jp", "timestamp"),
        )
    }
}
