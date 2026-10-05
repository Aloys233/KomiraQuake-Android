package com.aloys23.komiraquake.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SNTP 报文与 offset 计算。《NATIVE_PORT_SPEC》 §13.3。 */
class NtpMathTest {

    @Test
    fun requestPacketIsSntpV4ClientWithTimestamp() {
        val packet = NtpMath.buildRequest(1_700_000_000_123L)
        assertEquals(NtpMath.PACKET_SIZE, packet.size)
        // LI=0 VN=4 Mode=3 → 0b00100011 = 35
        assertEquals(35, packet[0].toInt() and 0xFF)
        // T1 写在高 8 字节（40..47）
        assertEquals(1_700_000_000_123L, readTimestamp(packet, 40))
        // 其余字节保持为 0
        assertEquals(0, packet[1].toInt())
        assertEquals(0, packet[39].toInt())
    }

    @Test
    fun timestampRoundTripsExactly() {
        for (epochMs in listOf(0L, 1L, 1_700_000_000_000L, 1_790_587_107_186L, 1_700_000_000_123L)) {
            assertEquals(epochMs, NtpMath.toEpochMs(NtpMath.toNtpTimestamp(epochMs)))
        }
    }

    @Test
    fun ntpEpochMapsToUnixEpoch() {
        // 1900-01-01 + 2208988800 s = 1970-01-01，fraction 0
        val ntpEpochTs = NtpMath.EPOCH_OFFSET_SECONDS shl 32
        assertEquals(0L, NtpMath.toEpochMs(ntpEpochTs))
    }

    @Test
    fun parseResponseRejectsShortPacketAndClientMode() {
        assertNull(NtpMath.parseResponse(ByteArray(20)))
        val clientMode = ByteArray(NtpMath.PACKET_SIZE)
        clientMode[0] = 35 // Mode=3
        assertNull(NtpMath.parseResponse(clientMode))
    }

    @Test
    fun parseResponseReadsServerTimestamps() {
        val packet = serverResponse(originateMs = 1_700_000_000_000L, receiveMs = 1_700_000_000_050L, transmitMs = 1_700_000_000_060L)
        val response = NtpMath.parseResponse(packet)
        assertNotNull(response)
        assertEquals(1_700_000_000_000L, response!!.originateMs)
        assertEquals(1_700_000_000_050L, response.receiveMs)
        assertEquals(1_700_000_000_060L, response.transmitMs)
    }

    @Test
    fun offsetAndDelayMatchSymmetricExchange() {
        // 本地墙钟快 30 s，单程 50 ms：offset = −30000，delay = 100
        val offset = 30_000L
        val oneWay = 50L
        val t0 = 1_700_000_000_000L
        val t1 = t0 + offset
        val t4 = t0 + 2 * oneWay + offset
        val response = NtpResponse(
            originateMs = t1,
            receiveMs = t0 + oneWay,
            transmitMs = t0 + oneWay,
        )
        val sample = NtpMath.computeSample(t1, response, t4)
        assertEquals(-offset, sample.offsetMs)
        assertEquals(2 * oneWay, sample.delayMs)
        assertTrue(sample.isValid)
    }

    @Test
    fun chooseBestReturnsNullWithoutUsableSamples() {
        assertNull(NtpMath.chooseBest(emptyList()))
        assertNull(NtpMath.chooseBest(listOf(NtpSample(100, -5)))) // delay < 0
        assertNull(NtpMath.chooseBest(listOf(NtpSample(100, 60_000)), maxDelayMs = 25_000))
    }

    @Test
    fun chooseBestTakesLowestDelayWhenFewerThanThree() {
        val best = NtpMath.chooseBest(listOf(NtpSample(40, 20), NtpSample(30, 10)))
        assertEquals(NtpSample(30, 10), best)
    }

    @Test
    fun chooseBestTakesMedianOffsetOfThreeLowestDelays() {
        val samples = listOf(
            NtpSample(offsetMs = 100, delayMs = 500),
            NtpSample(offsetMs = 9000, delayMs = 100),
            NtpSample(offsetMs = 50, delayMs = 200),
            NtpSample(offsetMs = -1, delayMs = 90_000), // 时延过大，丢弃
        )
        val best = NtpMath.chooseBest(samples, maxDelayMs = 25_000)
        assertNotNull(best)
        // 最低时延前三：100 / 200 / 500 → offset [9000, 50, 100] → 中位数 100
        assertEquals(100L, best!!.offsetMs)
        assertEquals(100L, best.delayMs)
    }

    private fun serverResponse(originateMs: Long, receiveMs: Long, transmitMs: Long): ByteArray {
        val packet = ByteArray(NtpMath.PACKET_SIZE)
        packet[0] = 36 // LI=0 VN=4 Mode=4（server）
        writeTimestamp(packet, 24, originateMs)
        writeTimestamp(packet, 32, receiveMs)
        writeTimestamp(packet, 40, transmitMs)
        return packet
    }

    private fun writeTimestamp(packet: ByteArray, offset: Int, epochMs: Long) {
        val ts = NtpMath.toNtpTimestamp(epochMs)
        for (i in 0 until 8) {
            packet[offset + i] = ((ts ushr (56 - 8 * i)) and 0xFF).toByte()
        }
    }

    private fun readTimestamp(packet: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (packet[offset + i].toLong() and 0xFF)
        }
        return NtpMath.toEpochMs(value)
    }
}
