package io.github.terrydaktal.whisperkeyboard

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Minimal RFC 7845 / RFC 3533 Ogg/Opus streaming muxer.
 *
 * Wraps raw Opus packets (e.g. from Android's MediaCodec audio/opus encoder) into
 * standards-compliant Ogg pages flushed every [packetsPerPage] packets (~100 ms at
 * 20 ms/frame) for low-latency streaming to Google's S3 beyond-a2a-recognizer.
 */
internal class OggOpusMuxer(
    private val sampleRate: Int = 16_000,
    private val channels: Int = 1,
    private val preSkip: Int = 312,
    private val packetsPerPage: Int = 5,
    private val serialNumber: Int = Random.nextInt(),
    private val onPage: (ByteArray) -> Unit,
) {
    private var headersWritten = false
    private var finished = false
    private var sequenceNumber = 0
    private var granulePosition = 0L
    private val bufferedPackets = ArrayList<Pair<ByteArray, Long>>(packetsPerPage + 1)

    fun writePacket(packet: ByteArray) {
        if (finished || packet.isEmpty()) return
        ensureHeaders()
        val duration48k = packetSamples48k(packet)
        bufferedPackets.add(packet to duration48k)
        // Keep at least 1 packet buffered so finish() always sets EOS on a real audio page.
        if (bufferedPackets.size > packetsPerPage) {
            flushPackets(packetsPerPage, isEos = false)
        }
    }

    fun finish() {
        if (finished) return
        finished = true
        if (!headersWritten) {
            ensureHeaders()
        }
        if (bufferedPackets.isNotEmpty()) {
            flushPackets(bufferedPackets.size, isEos = true)
        } else {
            emitPage(headerType = FLAG_EOS, granule = granulePosition, packets = emptyList())
        }
    }

    private fun ensureHeaders() {
        if (headersWritten) return
        headersWritten = true
        emitPage(headerType = FLAG_BOS, granule = 0L, packets = listOf(opusHead()))
        emitPage(headerType = 0, granule = 0L, packets = listOf(opusTags()))
    }

    private fun flushPackets(count: Int, isEos: Boolean) {
        val batch = ArrayList<ByteArray>(count)
        repeat(count) {
            val (pkt, duration) = bufferedPackets.removeAt(0)
            granulePosition += duration
            batch.add(pkt)
        }
        emitPage(
            headerType = if (isEos) FLAG_EOS else 0,
            granule = granulePosition,
            packets = batch,
        )
    }

    private fun opusHead(): ByteArray = ByteBuffer.allocate(19)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put("OpusHead".toByteArray(Charsets.US_ASCII))
        .put(1.toByte()) // version
        .put(channels.toByte())
        .putShort(preSkip.toShort())
        .putInt(sampleRate)
        .putShort(0.toShort()) // output gain
        .put(0.toByte()) // channel mapping family 0 (mono/stereo)
        .array()

    private fun opusTags(): ByteArray {
        val vendor = "AndroidOpus".toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(8 + 4 + vendor.size + 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("OpusTags".toByteArray(Charsets.US_ASCII))
            .putInt(vendor.size)
            .put(vendor)
            .putInt(0) // user comment count
            .array()
    }

    private fun emitPage(headerType: Int, granule: Long, packets: List<ByteArray>) {
        val lacing = ByteArrayOutputStream()
        var payloadBytes = 0
        for (packet in packets) {
            var remaining = packet.size
            while (remaining >= 255) {
                lacing.write(255)
                remaining -= 255
            }
            lacing.write(remaining)
            payloadBytes += packet.size
        }
        val segmentTable = lacing.toByteArray()
        require(segmentTable.size <= 255) { "Ogg page exceeds 255 segments (${segmentTable.size})" }

        val pageSize = 27 + segmentTable.size + payloadBytes
        val page = ByteArray(pageSize)
        val header = ByteBuffer.wrap(page, 0, 27).order(ByteOrder.LITTLE_ENDIAN)
        header.put('O'.code.toByte())
        header.put('g'.code.toByte())
        header.put('g'.code.toByte())
        header.put('S'.code.toByte())
        header.put(0.toByte()) // stream_structure_version
        header.put(headerType.toByte())
        header.putLong(granule)
        header.putInt(serialNumber)
        header.putInt(sequenceNumber++)
        header.putInt(0) // CRC placeholder
        header.put(segmentTable.size.toByte())

        System.arraycopy(segmentTable, 0, page, 27, segmentTable.size)
        var offset = 27 + segmentTable.size
        for (packet in packets) {
            System.arraycopy(packet, 0, page, offset, packet.size)
            offset += packet.size
        }

        val crc = oggCrc32(page)
        page[22] = (crc and 0xFF).toByte()
        page[23] = ((crc ushr 8) and 0xFF).toByte()
        page[24] = ((crc ushr 16) and 0xFF).toByte()
        page[25] = ((crc ushr 24) and 0xFF).toByte()

        onPage(page)
    }

    companion object {
        private const val FLAG_BOS = 0x02
        private const val FLAG_EOS = 0x04

        private val CRC_TABLE = IntArray(256) { index ->
            var r = index shl 24
            repeat(8) {
                r = if ((r and Int.MIN_VALUE) != 0) (r shl 1) xor 0x04C11DB7 else (r shl 1)
            }
            r
        }

        internal fun oggCrc32(data: ByteArray): Int {
            var crc = 0
            for (b in data) {
                val idx = ((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF
                crc = (crc shl 8) xor CRC_TABLE[idx]
            }
            return crc
        }

        /**
         * Decodes the total duration of a raw RFC 6716 Opus packet in 48 kHz granule samples.
         */
        internal fun packetSamples48k(packet: ByteArray): Long {
            if (packet.isEmpty()) return 960L
            val toc = packet[0].toInt() and 0xFF
            val config = toc ushr 3
            val frameSamples = when {
                config < 12 -> when (config and 0x03) {
                    0 -> 480L // 10 ms
                    1 -> 960L // 20 ms
                    2 -> 1920L // 40 ms
                    else -> 2880L // 60 ms
                }
                config < 16 -> if ((config and 0x01) == 0) 480L else 960L
                else -> when (config and 0x03) {
                    0 -> 120L // 2.5 ms
                    1 -> 240L // 5 ms
                    2 -> 480L // 10 ms
                    else -> 960L // 20 ms
                }
            }
            val frameCount = when (toc and 0x03) {
                0 -> 1
                1, 2 -> 2
                else -> if (packet.size >= 2) (packet[1].toInt() and 0x3F).coerceAtLeast(1) else 1
            }
            return frameSamples * frameCount
        }
    }
}
