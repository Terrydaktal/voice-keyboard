package io.github.terrydaktal.whisperkeyboard

import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Manages one live-streaming Rambler dictation session across 1..N rolling S3 segments.
 *
 * Mirrors desktop `stream-daemon.py`:
 * - Streams live 16 kHz PCM -> Ogg/Opus -> HTTP/2 `S3Bidi` while the user speaks.
 * - Rolls cleanly to a fresh segment after [SEGMENT_SOFT_MS] (180 s) on a brief
 *   silence pause (>= 300 ms), or unconditionally at [SEGMENT_HARD_MS] (240 s),
 *   staying well under Google S3's ~5-minute stream cap.
 * - Keeps an in-memory Ogg backup per segment for automatic one-shot retry if a
 *   live stream drops mid-segment.
 */
internal class RamblerLiveSession(
    private val locale: String = RamblerS3Proto.DEFAULT_LOCALE,
) {
    private val segments = mutableListOf<Segment>()
    private var activeSegment: Segment
    private var quietDurationMs = 0L
    private var closed = false

    init {
        activeSegment = Segment(0, locale)
        segments.add(activeSegment)
    }

    /**
     * Non-blocking: copies the PCM chunk into the active segment's encoder queue and
     * rolls segments if needed. Safe to call directly from the AudioRecord capture loop.
     */
    fun writePcm(buffer: ByteArray, count: Int) {
        if (closed || count <= 0) return
        val copy = buffer.copyOf(count)
        activeSegment.writePcm(copy)

        val ageMs = activeSegment.ageMs()
        if (ageMs >= SEGMENT_SOFT_MS) {
            val chunkMs = count * 1_000L / BYTES_PER_SECOND
            if (pcmRms(copy) < SILENCE_RMS_THRESHOLD) {
                quietDurationMs += chunkMs
            } else {
                quietDurationMs = 0L
            }
            if (quietDurationMs >= SILENCE_PAUSE_MS || ageMs >= SEGMENT_HARD_MS) {
                val reason = if (quietDurationMs >= SILENCE_PAUSE_MS) "silence_pause" else "hard_limit"
                Log.d(TAG, "Rolling Rambler S3 segment ${activeSegment.index} after ${ageMs}ms ($reason)")
                activeSegment.closeInput()
                activeSegment = Segment(segments.size, locale)
                segments.add(activeSegment)
                quietDurationMs = 0L
            }
        }
    }

    /**
     * Closes the active segment, waits for all segments to finish transcribing in order,
     * and returns the joined transcript. Throws [IOException] if any segment failed.
     */
    fun finish(): String {
        if (!closed) {
            closed = true
            activeSegment.closeInput()
        }
        val parts = mutableListOf<String>()
        var firstError: Throwable? = null
        for (segment in segments) {
            segment.awaitDone()
            val err = segment.error
            if (err != null && firstError == null) {
                firstError = err
            }
            val text = segment.text.trim()
            if (text.isNotEmpty()) parts.add(text)
        }
        if (firstError != null) {
            if (firstError is IOException) throw firstError
            throw IOException("Rambler transcription failed: ${firstError.message}", firstError)
        }
        return parts.joinToString(" ").trim()
    }

    fun abort() {
        if (closed) return
        closed = true
        for (segment in segments) {
            segment.closeInput()
        }
    }

    private class Segment(
        val index: Int,
        private val locale: String,
    ) {
        private val startedAtNanos = System.nanoTime()
        private val pcmQueue = LinkedBlockingQueue<ByteArray>()
        private val oggQueue = LinkedBlockingQueue<ByteArray>()
        private val oggBackup = Collections.synchronizedList(mutableListOf<ByteArray>())
        private val inputClosed = AtomicBoolean(false)
        private val encoderDoneLatch = CountDownLatch(1)
        private val doneLatch = CountDownLatch(1)

        @Volatile var text: String = ""
        @Volatile var error: Throwable? = null
        @Volatile private var hasPcm = false

        init {
            executor.execute(::runEncoderLoop)
            executor.execute(::runS3Loop)
        }

        fun ageMs(): Long = (System.nanoTime() - startedAtNanos) / 1_000_000L

        fun writePcm(pcm: ByteArray) {
            if (inputClosed.get() || pcm.isEmpty()) return
            hasPcm = true
            pcmQueue.offer(pcm)
        }

        fun closeInput() {
            if (inputClosed.compareAndSet(false, true)) {
                pcmQueue.offer(EOF)
            }
        }

        fun awaitDone() {
            doneLatch.await(120, TimeUnit.SECONDS)
        }

        private fun runEncoderLoop() {
            var encoder: OpusOggEncoder? = null
            try {
                encoder = OpusOggEncoder { page ->
                    oggBackup.add(page)
                    oggQueue.offer(page)
                }
                while (true) {
                    val pcm = pcmQueue.take()
                    if (pcm.isEmpty()) break
                    encoder.writePcm(pcm)
                }
                if (hasPcm) {
                    encoder.finish()
                } else {
                    encoder.abort()
                }
            } catch (exc: Throwable) {
                Log.w(TAG, "Segment $index Opus encoder failed", exc)
                error = exc
                encoder?.abort()
            } finally {
                oggQueue.offer(EOF)
                encoderDoneLatch.countDown()
            }
        }

        private fun runS3Loop() {
            val sentAudio = AtomicBoolean(false)
            try {
                val client = newClient()
                try {
                    text = client.streamLive(oggQueue, sentAudio)
                } catch (firstExc: Throwable) {
                    if (!sentAudio.get() && !inputClosed.get()) {
                        Log.d(TAG, "Segment $index reopening stale HTTP/2 connection", firstExc)
                        RamblerS3Client.resetClient()
                        text = newClient().streamLive(oggQueue, sentAudio)
                    } else {
                        throw firstExc
                    }
                }
            } catch (liveExc: Throwable) {
                Log.w(TAG, "Segment $index live S3 stream failed; retrying buffered Ogg", liveExc)
                // Wait for the encoder to finish so oggBackup contains the complete segment.
                encoderDoneLatch.await(30, TimeUnit.SECONDS)
                val snapshot = synchronized(oggBackup) { oggBackup.toList() }
                if (hasPcm && snapshot.isNotEmpty() && error == null) {
                    try {
                        RamblerS3Client.resetClient()
                        text = newClient().transcribeChunks(snapshot)
                    } catch (retryExc: Throwable) {
                        Log.w(TAG, "Segment $index buffered S3 retry failed", retryExc)
                        error = retryExc
                    }
                } else if (error == null) {
                    error = liveExc
                }
            } finally {
                doneLatch.countDown()
            }
        }

        private fun newClient(): RamblerS3Client = RamblerS3Client(
            locale = locale,
            androidRelease = Build.VERSION.RELEASE ?: "16",
            deviceModel = Build.MODEL ?: "Android",
        )
    }

    companion object {
        private const val TAG = "RamblerLiveSession"
        private const val SAMPLE_RATE = 16_000
        private const val BYTES_PER_SECOND = SAMPLE_RATE * 2
        private const val SEGMENT_SOFT_MS = 180_000L
        private const val SEGMENT_HARD_MS = 240_000L
        private const val SILENCE_RMS_THRESHOLD = 400.0
        private const val SILENCE_PAUSE_MS = 300L
        private const val WAV_HEADER_BYTES = 44L
        private val EOF = ByteArray(0)
        private val executor = Executors.newCachedThreadPool()

        /**
         * Transcribes a saved 16 kHz mono WAV file using Rambler (used for Retry).
         * Splits recordings longer than [SEGMENT_HARD_MS] into consecutive S3 segments.
         */
        fun transcribeWavFile(wavFile: File, locale: String = RamblerS3Proto.DEFAULT_LOCALE): String {
            val maxSegmentBytes = (SEGMENT_HARD_MS / 1_000L) * BYTES_PER_SECOND
            val transcripts = mutableListOf<String>()
            val client = RamblerS3Client(
                locale = locale,
                androidRelease = Build.VERSION.RELEASE ?: "16",
                deviceModel = Build.MODEL ?: "Android",
            )
            FileInputStream(wavFile).use { input ->
                var skipped = 0L
                while (skipped < WAV_HEADER_BYTES) {
                    val n = input.skip(WAV_HEADER_BYTES - skipped)
                    if (n <= 0) break
                    skipped += n
                }
                val buf = ByteArray(4096)
                var eof = false
                while (!eof) {
                    val oggPages = mutableListOf<ByteArray>()
                    var segmentBytes = 0L
                    val encoder = OpusOggEncoder { page -> oggPages.add(page) }
                    try {
                        while (segmentBytes < maxSegmentBytes) {
                            val toRead = minOf(buf.size.toLong(), maxSegmentBytes - segmentBytes).toInt()
                            val count = input.read(buf, 0, toRead)
                            if (count <= 0) {
                                eof = true
                                break
                            }
                            encoder.writePcm(buf, 0, count)
                            segmentBytes += count
                        }
                        if (segmentBytes > 0L) {
                            encoder.finish()
                        } else {
                            encoder.abort()
                        }
                    } catch (exc: Throwable) {
                        encoder.abort()
                        throw exc
                    }
                    if (oggPages.isNotEmpty()) {
                        val part = client.transcribeChunks(oggPages).trim()
                        if (part.isNotEmpty()) transcripts.add(part)
                    }
                }
            }
            return transcripts.joinToString(" ").trim()
        }

        internal fun pcmRms(pcm: ByteArray, count: Int = pcm.size): Double {
            val samples = count / 2
            if (samples <= 0) return 0.0
            var squares = 0.0
            var i = 0
            while (i < samples * 2) {
                val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
                squares += sample.toDouble() * sample
                i += 2
            }
            return sqrt(squares / samples)
        }
    }
}
