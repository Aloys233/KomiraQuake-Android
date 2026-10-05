package com.aloys23.komiraquake.core

/** 一次 NTP 采样：`offset = serverTime − localWall`（ms），`delay` = 往返时延（ms）。 */
data class NtpSample(val offsetMs: Long, val delayMs: Long) {
    val isValid: Boolean get() = delayMs >= 0L
}

/** 从 SNTP 响应解析出的三个时间戳（epoch ms）。 */
data class NtpResponse(
    /** T1 回显（客户端发送时刻）。 */
    val originateMs: Long,
    /** T2 服务端接收时刻。 */
    val receiveMs: Long,
    /** T3 服务端发送时刻。 */
    val transmitMs: Long,
)

/**
 * SNTP v4 报文与 offset 计算的纯函数实现。《NATIVE_PORT_SPEC》 §13.3。
 * 不涉及任何网络 I/O，便于单测。
 */
object NtpMath {
    /** 1900-01-01 → 1970-01-01 的秒数（NTP 纪元）。 */
    const val EPOCH_OFFSET_SECONDS = 2_208_988_800L

    const val PACKET_SIZE = 48
    private const val NTP_PORT = 123
    private const val TWO_POW_32 = 4_294_967_296.0

    /** LI=0 / VN=4 / Mode=3（client），并把 `T1` 写入字节 40..47。 */
    fun buildRequest(transmitEpochMs: Long): ByteArray {
        val packet = ByteArray(PACKET_SIZE)
        packet[0] = (((4 shl 3)) or 3).toByte()
        writeTimestamp(packet, 40, transmitEpochMs)
        return packet
    }

    /** 解析 48 字节响应；长度不足或模式不是服务端（4/5）时返回 null。 */
    fun parseResponse(packet: ByteArray): NtpResponse? {
        if (packet.size < PACKET_SIZE) return null
        val mode = packet[0].toInt() and 0x07
        if (mode != 4 && mode != 5) return null
        return NtpResponse(
            originateMs = readTimestamp(packet, 24),
            receiveMs = readTimestamp(packet, 32),
            transmitMs = readTimestamp(packet, 40),
        )
    }

    /**
     * 标准 offset/delay 公式。[t1Ms] 为客户端发送、[t4Ms] 为客户端接收（本地墙钟）。
     *
     * `offset = ((T2 − T1) + (T3 − T4)) / 2`，`delay = (T4 − T1) − (T3 − T2)`
     */
    fun computeSample(t1Ms: Long, response: NtpResponse, t4Ms: Long): NtpSample {
        val offset = ((response.receiveMs - t1Ms) + (response.transmitMs - t4Ms)) / 2
        val delay = (t4Ms - t1Ms) - (response.transmitMs - response.receiveMs)
        return NtpSample(offset, delay)
    }

    /**
     * 多采样筛选：丢弃无效或时延过大的样本，按 delay 升序取最优；
     * 样本数 ≥ 3 时取最低时延前三者 offset 的**中位数**（抗单点抖动）。
     */
    fun chooseBest(samples: List<NtpSample>, maxDelayMs: Long = 5L * 5_000L): NtpSample? {
        val usable = samples.filter { it.isValid && it.delayMs <= maxDelayMs }
        if (usable.isEmpty()) return null
        val byDelay = usable.sortedBy { it.delayMs }
        if (byDelay.size < 3) return byDelay.first()
        val medianOffset = byDelay.take(3).map { it.offsetMs }.sorted()[1]
        return NtpSample(medianOffset, byDelay.first().delayMs)
    }

    /** epoch ms → 64 位 NTP 时间戳（高 32 秒 + 低 32 小数）。 */
    fun toNtpTimestamp(epochMs: Long): Long {
        val shifted = epochMs + EPOCH_OFFSET_SECONDS * 1000L
        val seconds = Math.floorDiv(shifted, 1000L)
        val millis = shifted - seconds * 1000L
        val fraction = (millis.toDouble() / 1000.0 * TWO_POW_32).toLong() and 0xFFFF_FFFFL
        return (seconds shl 32) or fraction
    }

    /** 64 位 NTP 时间戳 → epoch ms。 */
    fun toEpochMs(ntpTimestamp: Long): Long {
        val seconds = ntpTimestamp ushr 32
        val fraction = ntpTimestamp and 0xFFFF_FFFFL
        // 四舍五入而非截断：与 toNtpTimestamp 往返无损。
        val millis = Math.round(fraction.toDouble() / TWO_POW_32 * 1000.0)
        return (seconds - EPOCH_OFFSET_SECONDS) * 1000L + millis
    }

    /** 服务端 UDP 端口。 */
    fun port(): Int = NTP_PORT

    private fun writeTimestamp(packet: ByteArray, offset: Int, epochMs: Long) {
        val timestamp = toNtpTimestamp(epochMs)
        for (i in 0 until 8) {
            packet[offset + i] = ((timestamp ushr (56 - 8 * i)) and 0xFF).toByte()
        }
    }

    private fun readTimestamp(packet: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (packet[offset + i].toLong() and 0xFF)
        }
        return toEpochMs(value)
    }
}
