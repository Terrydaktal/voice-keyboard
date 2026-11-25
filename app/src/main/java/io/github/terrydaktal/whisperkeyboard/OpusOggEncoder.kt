package io.github.terrydaktal.whisperkeyboard

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Live 16 kHz mono PCM16 -> Ogg/Opus encoder backed by Android's built-in
 * `audio/opus` MediaCodec (available since API 29) and [OggOpusMuxer].
 */
internal class OpusOggEncoder(
    private val sampleRate: Int = 16_000,
    private val channels: Int = 1,
    private val bitRate: Int = 32_000,
    private val onOggPage: (ByteArray) -> Unit,
) {
    data class Stats(
        val pcmBytesIn: Long,
        val opusPacketsOut: Long,
        val oggPagesOut: Long,
        val oggBytesOut: Long,
        val preSkip: Int,
        val eosQueued: Boolean,
        val eosDrained: Boolean,
        val inputTimeoutRetries: Long,
    )

    private val codec: MediaCodec
    private var muxer: OggOpusMuxer? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var totalSamplesFed = 0L
    private var pcmBytesIn = 0L
    private var opusPacketsOut = 0L
    private var oggPagesOut = 0L
    private var oggBytesOut = 0L
    private var resolvedPreSkip = DEFAULT_PRE_SKIP
    private var eosQueued = false
    private var eosDrained = false
    private var inputTimeoutRetries = 0L
    private var closed = false

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        codec = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        } catch (error: Exception) {
            throw IOException("Android Opus encoder could not be started.", error)
        }
    }

    fun stats(): Stats = Stats(
        pcmBytesIn = pcmBytesIn,
        opusPacketsOut = opusPacketsOut,
        oggPagesOut = oggPagesOut,
        oggBytesOut = oggBytesOut,
        preSkip = resolvedPreSkip,
        eosQueued = eosQueued,
        eosDrained = eosDrained,
        inputTimeoutRetries = inputTimeoutRetries,
    )

    fun writePcm(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size) {
        if (closed || length <= 0) return
        var pos = offset
        val end = offset + length
        while (pos < end) {
            val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)
                    ?: throw IOException("Opus encoder returned null input buffer.")
                inputBuffer.clear()
                val chunk = minOf(end - pos, inputBuffer.remaining())
                inputBuffer.put(pcm, pos, chunk)
                val ptsUs = totalSamplesFed * 1_000_000L / sampleRate
                codec.queueInputBuffer(inputIndex, 0, chunk, ptsUs, 0)
                totalSamplesFed += chunk / (2 * channels)
                pcmBytesIn += chunk
                pos += chunk
            } else {
                inputTimeoutRetries++
            }
            drainOutput(waitForEos = false)
        }
    }

    fun finish() {
        if (closed) return
        closed = true
        try {
            for (attempt in 0 until 200) {
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val ptsUs = totalSamplesFed * 1_000_000L / sampleRate
                    codec.queueInputBuffer(inputIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    eosQueued = true
                    break
                }
                inputTimeoutRetries++
                drainOutput(waitForEos = false)
            }
            if (eosQueued) {
                drainOutput(waitForEos = true)
            }
            muxer?.finish()
        } finally {
            releaseQuietly()
        }
    }

    fun abort() {
        if (closed) return
        closed = true
        releaseQuietly()
    }

    private fun drainOutput(waitForEos: Boolean) {
        var idleAttempts = 0
        while (true) {
            val timeout = if (waitForEos) TIMEOUT_US else 0L
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeout)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!waitForEos) return
                    if (++idleAttempts >= 100) return
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    idleAttempts = 0
                    val outputFormat = codec.outputFormat
                    val csd0 = outputFormat.getByteBuffer("csd-0")
                    if (muxer == null && csd0 != null) {
                        val copy = ByteArray(csd0.remaining())
                        csd0.duplicate().get(copy)
                        ensureMuxer(extractPreSkip(copy))
                    }
                }
                outputIndex >= 0 -> {
                    idleAttempts = 0
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val bytes = ByteArray(bufferInfo.size)
                        outputBuffer.get(bytes)
                        if (isConfig) {
                            if (muxer == null) ensureMuxer(extractPreSkip(bytes))
                        } else {
                            opusPacketsOut++
                            ensureMuxer(DEFAULT_PRE_SKIP).writePacket(bytes)
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (isEos) {
                        eosDrained = true
                        return
                    }
                }
            }
        }
    }

    private fun ensureMuxer(preSkip: Int): OggOpusMuxer {
        val existing = muxer
        if (existing != null) return existing
        resolvedPreSkip = preSkip
        return OggOpusMuxer(
            sampleRate = sampleRate,
            channels = channels,
            preSkip = preSkip,
            onPage = { page ->
                oggPagesOut++
                oggBytesOut += page.size
                onOggPage(page)
            },
        ).also { muxer = it }
    }

    private fun releaseQuietly() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
        private const val DEFAULT_PRE_SKIP = 312

        internal fun extractPreSkip(csd: ByteArray): Int {
            // Standard OpusHead starts with "OpusHead" (pre-skip uint16 LE at offset 10).
            if (csd.size >= 12 && matchesAscii(csd, 0, "OpusHead")) {
                return ByteBuffer.wrap(csd, 10, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
            }
            // Android AOPUSHDR CSD embeds OpusHead after an 8-byte marker + 8-byte length.
            if (csd.size >= 28 && matchesAscii(csd, 0, "AOPUSHDR") && matchesAscii(csd, 16, "OpusHead")) {
                return ByteBuffer.wrap(csd, 26, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
            }
            return DEFAULT_PRE_SKIP
        }

        private fun matchesAscii(data: ByteArray, offset: Int, text: String): Boolean {
            if (offset + text.length > data.size) return false
            for (i in text.indices) {
                if (data[offset + i].toInt() != text[i].code) return false
            }
            return true
        }
    }
}
