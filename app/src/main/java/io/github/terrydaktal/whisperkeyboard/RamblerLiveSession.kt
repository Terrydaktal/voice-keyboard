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
import java.util.concurrent.atomic.AtomicLong
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
 *   live stream drops mid-segment OR returns an unexpected empty response after audio was sent.
 */
internal class RamblerLiveSession(
    private val locale: String = RamblerS3Proto.DEFAULT_LOCALE,
    private val opId: String = "",
) {
    private val segments = mutableListOf<Segment>()
    private var activeSegment: Segment
    private var quietDurationMs = 0L
    private var closed = false
    private val sessionStartedMonoMs = System.nanoTime() / 1_000_000L

    init {
        activeSegment = Segment(0, locale, opId)
        segments.add(activeSegment)
        DictationDiagnostics.record(
            category = "rambler",
            event = "session_open",
            opId = opId,
            fields = mapOf("locale" to locale),
        )
    }

    /**
     * Non-blocking: copies the PCM chunk into the active segment's encoder queue and
     * rolls segments if needed. Safe to call directly from the AudioRecord capture loop.
     */
    fun writePcm(buffer: ByteArray, count: Int) {
        if (closed || count <= 0) return
        val copy = buffer.copyOf(count)
        val rms = pcmRms(copy)
        activeSegment.writePcm(copy, rms)

        val ageMs = activeSegment.ageMs()
        if (ageMs >= SEGMENT_SOFT_MS) {
            val chunkMs = count * 1_000L / BYTES_PER_SECOND
            if (rms < SILENCE_RMS_THRESHOLD) {
                quietDurationMs += chunkMs
            } else {
                quietDurationMs = 0L
            }
            if (quietDurationMs >= SILENCE_PAUSE_MS || ageMs >= SEGMENT_HARD_MS) {
                val reason = if (quietDurationMs >= SILENCE_PAUSE_MS) "silence_pause" else "hard_limit"
                Log.d(TAG, "Rolling Rambler S3 segment ${activeSegment.index} after ${ageMs}ms ($reason)")
                DictationDiagnostics.record(
                    category = "rambler",
                    event = "segment_roll",
                    opId = opId,
                    fields = mapOf(
                        "fromSeg" to activeSegment.index,
                        "toSeg" to segments.size,
                        "ageMs" to ageMs,
                        "reason" to reason,
                    ),
                )
                activeSegment.closeInput()
                activeSegment = Segment(segments.size, locale, opId)
                segments.add(activeSegment)
                quietDurationMs = 0L
            }
        }
    }

    /**
     * Closes the active segment, waits for all segments to finish transcribing in order,
     * and returns the joined transcript. Throws [IOException] if any segment failed or timed out.
     */
    fun finish(): String {
        if (!closed) {
            closed = true
            activeSegment.closeInput()
        }
        val parts = mutableListOf<String>()
        var firstError: Throwable? = null
        for (segment in segments) {
            val completed = segment.awaitDone(SEGMENT_AWAIT_TIMEOUT_S)
            if (!completed && segment.error == null) {
                val timeoutErr = IOException(
                    "Rambler segment ${segment.index} timed out after ${SEGMENT_AWAIT_TIMEOUT_S}s waiting for S3",
                )
                segment.error = timeoutErr
                DictationDiagnostics.recordError(
                    stage = "rambler_segment_timeout",
                    code = "SEGMENT_TIMEOUT",
                    error = timeoutErr,
                    opId = opId,
                    extraFields = mapOf(
                        "seg" to segment.index,
                        "pcmBytes" to segment.pcmBytesIn.get(),
                        "oggPagesBackup" to segment.oggBackupCount(),
                    ),
                )
            }
            val err = segment.error
            if (err != null && firstError == null) {
                firstError = err
            }
            val text = segment.text.trim()
            if (text.isNotEmpty()) parts.add(text)
        }
        val elapsedMs = (System.nanoTime() / 1_000_000L) - sessionStartedMonoMs
        DictationDiagnostics.updateActiveSegmentSummary("")
        if (firstError != null) {
            DictationDiagnostics.record(
                category = "rambler",
                event = "session_finish_error",
                opId = opId,
                level = DiagnosticFlightRecorder.LEVEL_ERROR,
                fields = mapOf(
                    "segments" to segments.size,
                    "elapsedMs" to elapsedMs,
                    "errClass" to firstError.javaClass.simpleName,
                ),
            )
            if (firstError is IOException) throw firstError
            throw IOException("Rambler transcription failed: ${firstError.message}", firstError)
        }
        val joined = parts.joinToString(" ").trim()
        DictationDiagnostics.record(
            category = "rambler",
            event = "session_finish_ok",
            opId = opId,
            level = if (joined.isEmpty()) DiagnosticFlightRecorder.LEVEL_WARN else DiagnosticFlightRecorder.LEVEL_INFO,
            fields = mapOf(
                "segments" to segments.size,
                "elapsedMs" to elapsedMs,
                "transcriptLen" to joined.length,
            ),
        )
        return joined
    }

    fun abort() {
        if (closed) return
        closed = true
        DictationDiagnostics.updateActiveSegmentSummary("")
        DictationDiagnostics.record(
            category = "rambler",
            event = "session_abort",
            opId = opId,
            fields = mapOf("segments" to segments.size),
        )
        for (segment in segments) {
            segment.closeInput()
        }
    }

    private class Segment(
        val index: Int,
        private val locale: String,
        private val opId: String,
    ) {
        private val startedAtNanos = System.nanoTime()
        private val pcmQueue = LinkedBlockingQueue<ByteArray>()
        private val oggQueue = LinkedBlockingQueue<ByteArray>()
        private val oggBackup = Collections.synchronizedList(mutableListOf<ByteArray>())
        private val inputClosed = AtomicBoolean(false)
        private val encoderDoneLatch = CountDownLatch(1)
        private val doneLatch = CountDownLatch(1)

        val pcmBytesIn = AtomicLong(0L)
        private val pcmChunksIn = AtomicLong(0L)
        private val recentRms = IntArray(TAIL_RMS_WINDOW_CHUNKS)
        private var recentRmsPos = 0
        private var recentRmsCount = 0
        @Volatile private var maxRms = 0.0
        @Volatile var text: String = ""
        @Volatile var error: Throwable? = null
        @Volatile private var hasPcm = false

        init {
            executor.execute(::runEncoderLoop)
            executor.execute(::runS3Loop)
        }

        fun ageMs(): Long = (System.nanoTime() - startedAtNanos) / 1_000_000L

        fun oggBackupCount(): Int = synchronized(oggBackup) { oggBackup.size }

        fun writePcm(pcm: ByteArray, rms: Double) {
            if (inputClosed.get() || pcm.isEmpty()) return
            hasPcm = true
            pcmChunksIn.incrementAndGet()
            pcmBytesIn.addAndGet(pcm.size.toLong())
            if (rms > maxRms) maxRms = rms
            synchronized(recentRms) {
                recentRms[recentRmsPos] = rms.toInt()
                recentRmsPos = (recentRmsPos + 1) % recentRms.size
                if (recentRmsCount < recentRms.size) recentRmsCount++
            }
            pcmQueue.offer(pcm)
        }

        private fun tailRmsSummary(): Pair<Int, Int> = synchronized(recentRms) {
            if (recentRmsCount == 0) return 0 to 0
            var sum = 0L
            var peak = 0
            for (i in 0 until recentRmsCount) {
                val v = recentRms[i]
                sum += v
                if (v > peak) peak = v
            }
            (sum / recentRmsCount).toInt() to peak
        }

        fun closeInput() {
            if (inputClosed.compareAndSet(false, true)) {
                pcmQueue.offer(EOF)
            }
        }

        fun awaitDone(timeoutSeconds: Long): Boolean = doneLatch.await(timeoutSeconds, TimeUnit.SECONDS)

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
                val encStats = encoder.stats()
                val (tailAvgRms, tailMaxRms) = tailRmsSummary()
                DictationDiagnostics.record(
                    category = "encoder",
                    event = "segment_done",
                    opId = opId,
                    level = if (hasPcm && (!encStats.eosDrained || encStats.oggPagesOut <= 2L)) {
                        DiagnosticFlightRecorder.LEVEL_WARN
                    } else {
                        DiagnosticFlightRecorder.LEVEL_INFO
                    },
                    fields = mapOf(
                        "seg" to index,
                        "pcmChunks" to pcmChunksIn.get(),
                        "pcmBytes" to encStats.pcmBytesIn,
                        "maxRms" to maxRms.toInt(),
                        "tailAvgRms" to tailAvgRms,
                        "tailMaxRms" to tailMaxRms,
                        "opusPackets" to encStats.opusPacketsOut,
                        "oggPages" to encStats.oggPagesOut,
                        "oggBytes" to encStats.oggBytesOut,
                        "preSkip" to encStats.preSkip,
                        "eosQueued" to encStats.eosQueued,
                        "eosDrained" to encStats.eosDrained,
                        "inRetries" to encStats.inputTimeoutRetries,
                    ),
                )
            } catch (exc: Throwable) {
                Log.w(TAG, "Segment $index Opus encoder failed", exc)
                error = exc
                DictationDiagnostics.recordError(
                    stage = "opus_encoder",
                    code = exc.javaClass.simpleName,
                    error = exc,
                    opId = opId,
                    extraFields = mapOf("seg" to index, "pcmBytes" to pcmBytesIn.get()),
                )
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
                var liveText: String
                try {
                    liveText = client.streamLive(oggQueue, sentAudio)
                } catch (firstExc: Throwable) {
                    if (!sentAudio.get() && !inputClosed.get()) {
                        Log.d(TAG, "Segment $index reopening stale HTTP/2 connection", firstExc)
                        DictationDiagnostics.record(
                            category = "s3",
                            event = "reopen_stale_h2",
                            opId = opId,
                            level = DiagnosticFlightRecorder.LEVEL_WARN,
                            fields = mapOf("seg" to index, "errClass" to firstExc.javaClass.simpleName),
                        )
                        RamblerS3Client.resetClient()
                        liveText = newClient().streamLive(oggQueue, sentAudio)
                    } else {
                        throw firstExc
                    }
                }
                if (liveText.isNotEmpty()) {
                    text = liveText
                } else {
                    // Live stream returned empty without throwing; if real audio was captured,
                    // retry once with the complete buffered Ogg before accepting empty speech.
                    encoderDoneLatch.await(30, TimeUnit.SECONDS)
                    val snapshot = synchronized(oggBackup) { oggBackup.toList() }
                    if (hasPcm && snapshot.size > 2 && maxRms >= SILENCE_RMS_THRESHOLD / 2.0 && error == null) {
                        DictationDiagnostics.counters.s3BufferedRetries.incrementAndGet()
                        DictationDiagnostics.record(
                            category = "rambler",
                            event = "live_empty_retrying_buffered",
                            opId = opId,
                            level = DiagnosticFlightRecorder.LEVEL_WARN,
                            fields = mapOf(
                                "seg" to index,
                                "oggPages" to snapshot.size,
                                "pcmBytes" to pcmBytesIn.get(),
                                "maxRms" to maxRms.toInt(),
                            ),
                        )
                        RamblerS3Client.resetClient()
                        text = newClient().transcribeChunks(snapshot)
                    }
                }
            } catch (liveExc: Throwable) {
                Log.w(TAG, "Segment $index live S3 stream failed; retrying buffered Ogg", liveExc)
                // Wait for the encoder to finish so oggBackup contains the complete segment.
                val encDone = encoderDoneLatch.await(30, TimeUnit.SECONDS)
                val snapshot = synchronized(oggBackup) { oggBackup.toList() }
                if (hasPcm && snapshot.isNotEmpty() && error == null) {
                    DictationDiagnostics.counters.s3BufferedRetries.incrementAndGet()
                    DictationDiagnostics.record(
                        category = "rambler",
                        event = "live_failed_retrying_buffered",
                        opId = opId,
                        level = DiagnosticFlightRecorder.LEVEL_WARN,
                        fields = mapOf(
                            "seg" to index,
                            "encDone" to encDone,
                            "oggPages" to snapshot.size,
                            "liveErrClass" to liveExc.javaClass.simpleName,
                        ),
                    )
                    try {
                        RamblerS3Client.resetClient()
                        text = newClient().transcribeChunks(snapshot)
                    } catch (retryExc: Throwable) {
                        Log.w(TAG, "Segment $index buffered S3 retry failed", retryExc)
                        DictationDiagnostics.recordError(
                            stage = "rambler_buffered_retry",
                            code = retryExc.javaClass.simpleName,
                            error = retryExc,
                            opId = opId,
                            extraFields = mapOf("seg" to index, "oggPages" to snapshot.size),
                        )
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
            opId = opId,
            segmentIndex = index,
        )
    }

    companion object {
        private const val TAG = "RamblerLiveSession"
        private const val SAMPLE_RATE = 16_000
        private const val BYTES_PER_SECOND = SAMPLE_RATE * 2
        private const val SEGMENT_SOFT_MS = 180_000L
        private const val SEGMENT_HARD_MS = 240_000L
        private const val SEGMENT_AWAIT_TIMEOUT_S = 120L
        private const val SILENCE_RMS_THRESHOLD = 400.0
        private const val SILENCE_PAUSE_MS = 300L
        private const val WAV_HEADER_BYTES = 44L
        private const val TAIL_RMS_WINDOW_CHUNKS = 50 // ~2.0 s at 40 ms/chunk
        private val EOF = ByteArray(0)
        private val executor = Executors.newCachedThreadPool()

        /**
         * Transcribes a saved 16 kHz mono WAV file using Rambler (used for Retry).
         * Splits recordings longer than [SEGMENT_HARD_MS] into consecutive S3 segments.
         */
        fun transcribeWavFile(
            wavFile: File,
            locale: String = RamblerS3Proto.DEFAULT_LOCALE,
            opId: String = "",
        ): String {
            val maxSegmentBytes = (SEGMENT_HARD_MS / 1_000L) * BYTES_PER_SECOND
            val transcripts = mutableListOf<String>()
            var segIndex = 0
            DictationDiagnostics.record(
                category = "rambler",
                event = "wav_retry_start",
                opId = opId,
                fields = mapOf(
                    "file" to wavFile.name,
                    "wavBytes" to wavFile.length(),
                    "locale" to locale,
                ),
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
                        val client = RamblerS3Client(
                            locale = locale,
                            androidRelease = Build.VERSION.RELEASE ?: "16",
                            deviceModel = Build.MODEL ?: "Android",
                            opId = opId,
                            segmentIndex = segIndex++,
                        )
                        val part = client.transcribeChunks(oggPages).trim()
                        if (part.isNotEmpty()) transcripts.add(part)
                    }
                }
            }
            val joined = transcripts.joinToString(" ").trim()
            DictationDiagnostics.record(
                category = "rambler",
                event = "wav_retry_done",
                opId = opId,
                fields = mapOf(
                    "file" to wavFile.name,
                    "segments" to segIndex,
                    "transcriptLen" to joined.length,
                ),
            )
            return joined
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
