package io.github.terrydaktal.whisperkeyboard

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OggOpusMuxerTest {
    @Test
    fun emitsValidBosTagsAudioAndEosPagesWithCorrectCrcs() {
        val pages = mutableListOf<ByteArray>()
        val muxer = OggOpusMuxer(
            sampleRate = 16_000,
            channels = 1,
            preSkip = 312,
            packetsPerPage = 5,
            serialNumber = 0x12345678,
        ) { pages.add(it) }

        // 7 SILK 20ms single-frame packets (config=1 -> toc = (1 << 3) | 0 = 0x08 -> 960 samples @ 48kHz)
        repeat(7) { idx ->
            val pkt = ByteArray(40) { if (it == 0) 0x08 else (idx + it).toByte() }
            muxer.writePacket(pkt)
        }
        muxer.finish()

        // Expected: page 0 (OpusHead BOS), page 1 (OpusTags), page 2 (5 packets), page 3 (2 packets EOS)
        assertEquals(4, pages.size)

        // Verify OggS magic, sequence numbers, and CRCs on every page
        pages.forEachIndexed { seq, page ->
            assertEquals("OggS", String(page, 0, 4, Charsets.US_ASCII))
            assertEquals(0, page[4].toInt())
            val headerBuf = ByteBuffer.wrap(page, 0, 27).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0x12345678, headerBuf.getInt(14))
            assertEquals(seq, headerBuf.getInt(18))

            val storedCrc = headerBuf.getInt(22)
            val copy = page.copyOf()
            copy[22] = 0
            copy[23] = 0
            copy[24] = 0
            copy[25] = 0
            assertEquals("CRC mismatch on page $seq", storedCrc, OggOpusMuxer.oggCrc32(copy))
        }

        // Page 0: BOS flag + OpusHead payload
        assertEquals(0x02, pages[0][5].toInt() and 0xFF)
        assertEquals(0L, ByteBuffer.wrap(pages[0], 6, 8).order(ByteOrder.LITTLE_ENDIAN).long)
        assertEquals("OpusHead", String(pages[0], 28, 8, Charsets.US_ASCII))

        // Page 1: OpusTags
        assertEquals(0x00, pages[1][5].toInt() and 0xFF)
        assertEquals("OpusTags", String(pages[1], 28, 8, Charsets.US_ASCII))

        // Page 2: 5 * 960 = 4800 granule samples
        assertEquals(0x00, pages[2][5].toInt() and 0xFF)
        assertEquals(4800L, ByteBuffer.wrap(pages[2], 6, 8).order(ByteOrder.LITTLE_ENDIAN).long)

        // Page 3: EOS flag + 7 * 960 = 6720 granule samples
        assertEquals(0x04, pages[3][5].toInt() and 0xFF)
        assertEquals(6720L, ByteBuffer.wrap(pages[3], 6, 8).order(ByteOrder.LITTLE_ENDIAN).long)
    }

    @Test
    fun packetSamples48kDecodesSilkHybridCeltAndMultiFramePackets() {
        // SILK 10ms (config 0, 1 frame) = 480
        assertEquals(480L, OggOpusMuxer.packetSamples48k(byteArrayOf(0x00)))
        // SILK 20ms (config 1, 1 frame) = 960
        assertEquals(960L, OggOpusMuxer.packetSamples48k(byteArrayOf(0x08)))
        // SILK 20ms, 2 frames (code 1) = 1920
        assertEquals(1920L, OggOpusMuxer.packetSamples48k(byteArrayOf(0x09)))
        // SILK 60ms (config 3, 1 frame) = 2880
        assertEquals(2880L, OggOpusMuxer.packetSamples48k(byteArrayOf(0x18)))
        // Hybrid 20ms (config 13, 1 frame) = 960
        assertEquals(960L, OggOpusMuxer.packetSamples48k(byteArrayOf((13 shl 3).toByte())))
        // CELT 20ms (config 19, code 3 with 3 frames) = 2880
        assertEquals(2880L, OggOpusMuxer.packetSamples48k(byteArrayOf(((19 shl 3) or 3).toByte(), 3)))
    }

    @Test
    fun supportsPacketsLargerThan255Bytes() {
        val pages = mutableListOf<ByteArray>()
        val muxer = OggOpusMuxer(packetsPerPage = 1) { pages.add(it) }
        val bigPacket = ByteArray(300) { if (it == 0) 0x08 else 0x5A }
        muxer.writePacket(bigPacket)
        muxer.finish()

        assertEquals(3, pages.size)
        val audioPage = pages[2]
        assertEquals(2, audioPage[26].toInt() and 0xFF) // 2 lacing segments: 255 + 45
        assertEquals(255, audioPage[27].toInt() and 0xFF)
        assertEquals(45, audioPage[28].toInt() and 0xFF)
        assertTrue(audioPage.size == 27 + 2 + 300)
    }
}
