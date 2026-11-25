package io.github.terrydaktal.whisperkeyboard

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RamblerS3ProtoTest {
    @Test
    fun headerRequestIncludesGeminiV3FieldsAndNoProfanityMasking() {
        val header = RamblerS3Proto.headerRequest(
            locale = "en-GB",
            androidRelease = "16",
            deviceModel = "NX809J",
        )
        val ascii = String(header, Charsets.ISO_8859_1)
        assertTrue(ascii.contains("beyond-a2a-recognizer"))
        assertTrue(ascii.contains("gboard_gemini_v3_streaming_voice_edit_mul"))
        assertTrue(ascii.contains("en-GB"))
        assertTrue(ascii.contains("gboard"))
        assertTrue(ascii.contains("NX809J"))

        // Verify Laapa (#91 = { #2=true, #4=true }) bytes are present:
        // tag(91, 2) = (91 << 3) | 2 = 730 -> varint [0xDA, 0x05], len 4, [0x10, 0x01, 0x20, 0x01]
        val laapaNeedle = byteArrayOf(0xDA.toByte(), 0x05, 0x04, 0x10, 0x01, 0x20, 0x01)
        assertTrue("Missing Laapa enable flags", containsSubarray(header, laapaNeedle))

        // Verify profanity_filter = 0: tag(7, 0) = 0x38, val 0x00
        val profanityOff = byteArrayOf(0x38, 0x00)
        assertTrue("Missing profanity_filter=0", containsSubarray(header, profanityOff))
    }

    @Test
    fun chunkRequestContainsOnlyAudioDataExtension() {
        val sample = byteArrayOf(1, 2, 3, 4, 5)
        val chunk = RamblerS3Proto.chunkRequest(sample)

        // EXT_AUDIO_DATA = 293101 -> tag (293101 << 3) | 2 = 2344810 -> varint [0xEA, 0x8E, 0x8F, 0x01]
        assertEquals(0xEA.toByte(), chunk[0])
        assertEquals(0x8E.toByte(), chunk[1])
        assertEquals(0x8F.toByte(), chunk[2])
        assertEquals(0x01.toByte(), chunk[3])

        // EXT_AUDIO_INFO = 293100 -> tag [0xE2, 0x8E, 0x8F, 0x01] MUST NOT be present on audio chunks.
        val audioInfoTag = byteArrayOf(0xE2.toByte(), 0x8E.toByte(), 0x8F.toByte(), 0x01)
        assertFalse("Chunk must not re-send S3AudioInfo", containsSubarray(chunk, audioInfoTag))
    }

    @Test
    fun endRequestSetsFields2And3True() {
        val end = RamblerS3Proto.endRequest()
        assertArrayEquals(byteArrayOf(0x10, 0x01, 0x18, 0x01), end)
    }

    @Test
    fun grpcFrameSplitterHandlesFragmentedAndConcatenatedFrames() {
        val msg1 = "hello".toByteArray()
        val msg2 = "world-longer-payload".toByteArray()
        val stream = RamblerS3Proto.grpcFrame(msg1) + RamblerS3Proto.grpcFrame(msg2)

        val splitter = RamblerS3Proto.GrpcFrameSplitter()
        val collected = mutableListOf<ByteArray>()
        // Feed 1 byte at a time to exercise partial header & payload buffering
        for (b in stream) {
            collected.addAll(splitter.feed(byteArrayOf(b)))
        }

        assertEquals(2, collected.size)
        assertArrayEquals(msg1, collected[0])
        assertArrayEquals(msg2, collected[1])
    }

    @Test
    fun decodeResponseExtractsFinalTranscriptAndErrors() {
        val hyp = tagLen(1, "Hello world".toByteArray())
        val combined = tagLen(3, hyp)
        val recEvent = byteArrayOf(0x08, 0x01) + tagLen(3, combined)
        val recognizerEvent = tagLen(1, recEvent) + tagLen(3, "en-GB".toByteArray())
        val raw = byteArrayOf(0x08, 0x00) + tagLen(RamblerS3Proto.EXT_RECOGNIZER_EVENT, recognizerEvent)

        val event = RamblerS3Proto.decodeResponse(raw)
        assertEquals(0L, event.status)
        assertEquals("en-GB", event.language)
        assertEquals(1L, event.recStatus)
        assertEquals(listOf("Hello world"), event.finals)

        val acc = RamblerS3Proto.TranscriptAccumulator()
        acc.accept(event)
        acc.accept(event) // deduplicated
        assertEquals("Hello world", acc.finalText())
    }

    @Test
    fun extractPreSkipHandlesOpusHeadAndAndroidAopusHdr() {
        val opusHead = ByteArray(19)
        "OpusHead".toByteArray().copyInto(opusHead, 0)
        opusHead[10] = 0x38 // 312 LE = 0x0138
        opusHead[11] = 0x01
        assertEquals(312, OpusOggEncoder.extractPreSkip(opusHead))

        val aopusHdr = ByteArray(35)
        "AOPUSHDR".toByteArray().copyInto(aopusHdr, 0)
        opusHead.copyInto(aopusHdr, 16)
        aopusHdr[26] = 0x80.toByte() // 384 LE = 0x0180
        aopusHdr[27] = 0x01
        assertEquals(384, OpusOggEncoder.extractPreSkip(aopusHdr))
    }

    @Test
    fun liveS3StreamingWhenEnvVarProvided() {
        val oggPath = System.getenv("RAMBLER_LIVE_TEST_OGG") ?: return
        val oggBytes = File(oggPath).readBytes()
        val queue = LinkedBlockingQueue<ByteArray>()
        val sentAudio = AtomicBoolean(false)

        val feeder = Thread {
            var offset = 0
            while (offset < oggBytes.size) {
                val end = minOf(offset + 2048, oggBytes.size)
                queue.put(oggBytes.copyOfRange(offset, end))
                offset = end
                Thread.sleep(20)
            }
            queue.put(ByteArray(0))
        }
        feeder.start()

        val text = RamblerS3Client(locale = "en-GB").streamLive(queue, sentAudio)
        feeder.join(5000)

        assertTrue("Expected audio chunks to be sent", sentAudio.get())
        assertTrue("Expected non-empty live transcript from Google S3", text.isNotBlank())
        println("LIVE_S3_TRANSCRIPT=$text")
    }

    private fun tagLen(fieldNumber: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarint(out, (fieldNumber.toLong() shl 3) or 2L)
        writeVarint(out, payload.size.toLong())
        out.write(payload)
        return out.toByteArray()
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7FL).toInt()
            v = v ushr 7
            if (v != 0L) out.write(b or 0x80) else {
                out.write(b)
                return
            }
        }
    }

    private fun containsSubarray(haystack: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
