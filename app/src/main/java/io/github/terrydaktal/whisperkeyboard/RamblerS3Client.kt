package io.github.terrydaktal.whisperkeyboard

import java.io.IOException
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink

/**
 * HTTP/2 gRPC client for Google's S3 `S3Bidi` streaming endpoint.
 *
 * Uses OkHttp's HTTP/2 full-duplex [RequestBody] so live Ogg/Opus audio chunks
 * stream upstream in real time while S3 `RecognizerEvent` responses arrive downstream.
 */
internal class RamblerS3Client(
    private val locale: String = RamblerS3Proto.DEFAULT_LOCALE,
    private val androidRelease: String = "16",
    private val deviceModel: String = "NX809J",
    private val client: OkHttpClient = sharedClient(),
    private val opId: String = "",
    private val segmentIndex: Int = 0,
    private val endpointUrl: String = RamblerS3Proto.URL,
) {
    /**
     * Streams live Ogg/Opus chunks from [oggQueue] until an empty [ByteArray] EOF sentinel
     * is received, returning the final joined transcript.
     */
    fun streamLive(
        oggQueue: BlockingQueue<ByteArray>,
        sentAudioFlag: AtomicBoolean? = null,
    ): String = executeBidi(mode = "live") { sink, stats ->
        sink.write(
            RamblerS3Proto.grpcFrame(
                RamblerS3Proto.headerRequest(
                    locale = locale,
                    androidRelease = androidRelease,
                    deviceModel = deviceModel,
                ),
            ),
        )
        sink.flush()

        while (true) {
            val chunk = oggQueue.take()
            if (chunk.isEmpty()) break
            sentAudioFlag?.set(true)
            writeOggChunk(sink, chunk, stats)
        }

        sink.write(RamblerS3Proto.grpcFrame(RamblerS3Proto.endRequest()))
        sink.flush()
        stats.endRequestSent.set(true)
    }

    /**
     * Transcribes an already-buffered sequence of Ogg/Opus chunks in one HTTP/2 S3Bidi call.
     */
    fun transcribeChunks(chunks: List<ByteArray>): String = executeBidi(mode = "buffered") { sink, stats ->
        sink.write(
            RamblerS3Proto.grpcFrame(
                RamblerS3Proto.headerRequest(
                    locale = locale,
                    androidRelease = androidRelease,
                    deviceModel = deviceModel,
                ),
            ),
        )
        sink.flush()

        for (chunk in chunks) {
            if (chunk.isNotEmpty()) {
                writeOggChunk(sink, chunk, stats)
            }
        }

        sink.write(RamblerS3Proto.grpcFrame(RamblerS3Proto.endRequest()))
        sink.flush()
        stats.endRequestSent.set(true)
    }

    private fun writeOggChunk(sink: BufferedSink, chunk: ByteArray, stats: CallStats) {
        var offset = 0
        while (offset < chunk.size) {
            val len = minOf(MAX_CHUNK_BYTES, chunk.size - offset)
            val payload = RamblerS3Proto.chunkRequest(chunk, offset, len)
            sink.write(RamblerS3Proto.grpcFrame(payload))
            offset += len
            stats.grpcAudioFramesOut.incrementAndGet()
        }
        sink.flush()
        stats.oggChunksOut.incrementAndGet()
        stats.oggBytesOut.addAndGet(chunk.size.toLong())
        val nowMs = System.nanoTime() / 1_000_000L
        val prevBeat = stats.lastHeartbeatMonoMs.get()
        if (nowMs - prevBeat >= PROGRESS_HEARTBEAT_MS &&
            stats.lastHeartbeatMonoMs.compareAndSet(prevBeat, nowMs)
        ) {
            DictationDiagnostics.updateActiveSegmentSummary(
                "seg=$segmentIndex oggOut=${stats.oggBytesOut.get()}B framesIn=${stats.framesIn.get()} " +
                    "partials=${stats.partialsIn.get()} finals=${stats.finalsIn.get()}",
            )
            DictationDiagnostics.record(
                category = "s3",
                event = "stream_progress",
                opId = opId,
                fields = mapOf(
                    "seg" to segmentIndex,
                    "elapsedMs" to (nowMs - stats.startedMonoMs),
                    "oggChunksOut" to stats.oggChunksOut.get(),
                    "oggBytesOut" to stats.oggBytesOut.get(),
                    "framesIn" to stats.framesIn.get(),
                    "partialsIn" to stats.partialsIn.get(),
                    "finalsIn" to stats.finalsIn.get(),
                ),
            )
        }
    }

    private class CallStats(val startedMonoMs: Long) {
        val oggChunksOut = AtomicLong(0L)
        val oggBytesOut = AtomicLong(0L)
        val grpcAudioFramesOut = AtomicLong(0L)
        val endRequestSent = AtomicBoolean(false)
        val rawBytesIn = AtomicLong(0L)
        val framesIn = AtomicLong(0L)
        val nonEmptyEventsIn = AtomicLong(0L)
        val partialsIn = AtomicLong(0L)
        val finalsIn = AtomicLong(0L)
        val resultsIn = AtomicLong(0L)
        val fallbacksIn = AtomicLong(0L)
        val lastRecStatus = AtomicReference<Long?>(null)
        val lastS3Status = AtomicReference<Long?>(null)
        val endpointerCount = AtomicInteger(0)
        val lastEndpointer = AtomicReference<Long?>(null)
        val firstResponseMs = AtomicLong(-1L)
        val lastHeartbeatMonoMs = AtomicLong(startedMonoMs)
    }

    private fun executeBidi(mode: String, writerAction: (BufferedSink, CallStats) -> Unit): String {
        val startedMonoMs = System.nanoTime() / 1_000_000L
        val stats = CallStats(startedMonoMs)
        val writerError = AtomicReference<Throwable?>(null)
        val writerFinished = AtomicBoolean(false)
        val writerFutureRef = AtomicReference<java.util.concurrent.Future<*>?>(null)

        DictationDiagnostics.counters.s3CallsStarted.incrementAndGet()
        DictationDiagnostics.record(
            category = "s3",
            event = "call_open",
            opId = opId,
            fields = mapOf(
                "seg" to segmentIndex,
                "mode" to mode,
                "locale" to locale,
            ),
        )

        val body = object : RequestBody() {
            override fun contentType(): MediaType = GRPC_MEDIA_TYPE
            override fun isDuplex(): Boolean = true
            override fun isOneShot(): Boolean = true

            override fun writeTo(sink: BufferedSink) {
                // OkHttp duplex mode expects writeTo() to return immediately so it can
                // read response headers concurrently while this background thread writes.
                val future = writerExecutor.submit {
                    try {
                        writerAction(sink, stats)
                    } catch (error: Throwable) {
                        writerError.compareAndSet(null, error)
                        DictationDiagnostics.recordError(
                            stage = "s3_writer",
                            code = error.javaClass.simpleName,
                            error = error,
                            opId = opId,
                            extraFields = mapOf(
                                "seg" to segmentIndex,
                                "mode" to mode,
                                "oggBytesOut" to stats.oggBytesOut.get(),
                                "endRequestSent" to stats.endRequestSent.get(),
                            ),
                        )
                    } finally {
                        runCatching { sink.close() }
                        writerFinished.set(true)
                    }
                }
                writerFutureRef.set(future)
            }
        }

        val request = Request.Builder()
            .url(endpointUrl)
            .header("te", "trailers")
            .header("x-goog-api-key", RamblerS3Proto.API_KEY)
            .header("x-android-package", RamblerS3Proto.ANDROID_PACKAGE)
            .header("x-android-cert", RamblerS3Proto.ANDROID_CERT)
            .post(body)
            .build()

        val accumulator = RamblerS3Proto.TranscriptAccumulator()
        val splitter = RamblerS3Proto.GrpcFrameSplitter()

        var httpCode = -1
        var protocol = ""
        var headerGrpcStatus: String? = null
        var headerGrpcMessage: String? = null
        var trailerGrpcStatus: String? = null
        var trailerGrpcMessage: String? = null
        var readerSawEof = false

        val call = client.newCall(request)
        try {
            call.execute().use { response ->
                httpCode = response.code
                protocol = response.protocol.toString()
                headerGrpcStatus = response.header("grpc-status")
                headerGrpcMessage = response.header("grpc-message")
                val headersMs = (System.nanoTime() / 1_000_000L) - startedMonoMs
                DictationDiagnostics.record(
                    category = "s3",
                    event = "response_headers",
                    opId = opId,
                    fields = mapOf(
                        "seg" to segmentIndex,
                        "mode" to mode,
                        "httpCode" to httpCode,
                        "protocol" to protocol,
                        "headersMs" to headersMs,
                        "headerGrpcStatus" to (headerGrpcStatus ?: ""),
                    ),
                )
                if (!response.isSuccessful) {
                    throw RamblerHttpException(response.code)
                }
                val source = response.body?.source()
                    ?: throw IOException("Rambler S3 returned an empty response body.")
                val readBuffer = ByteArray(8192)
                while (true) {
                    val count = source.read(readBuffer)
                    if (count == -1) {
                        readerSawEof = true
                        break
                    }
                    if (stats.firstResponseMs.get() < 0L) {
                        stats.firstResponseMs.set((System.nanoTime() / 1_000_000L) - startedMonoMs)
                    }
                    stats.rawBytesIn.addAndGet(count.toLong())
                    for (frame in splitter.feed(readBuffer, count)) {
                        stats.framesIn.incrementAndGet()
                        val event = RamblerS3Proto.decodeResponse(frame)
                        if (event.isEmpty) continue
                        stats.nonEmptyEventsIn.incrementAndGet()
                        event.status?.let { stats.lastS3Status.set(it) }
                        event.recStatus?.let { stats.lastRecStatus.set(it) }
                        event.endpointer?.let {
                            stats.endpointerCount.incrementAndGet()
                            stats.lastEndpointer.set(it)
                        }
                        if (event.partials.isNotEmpty()) stats.partialsIn.incrementAndGet()
                        if (event.finals.isNotEmpty()) stats.finalsIn.incrementAndGet()
                        if (event.results.isNotEmpty()) stats.resultsIn.incrementAndGet()
                        if (event.finalFallback != null || event.resultFallback != null) {
                            stats.fallbacksIn.incrementAndGet()
                        }
                        val errCode = event.errorCode
                        if (errCode != null && errCode != 0L) {
                            throw RamblerS3ErrorException(errCode, event.errorDescription.orEmpty())
                        }
                        accumulator.accept(event)
                    }
                }
                val trailers = runCatching { response.trailers() }.getOrNull()
                if (trailers != null) {
                    trailerGrpcStatus = trailers["grpc-status"]
                    trailerGrpcMessage = trailers["grpc-message"]
                }
            }
        } catch (error: Throwable) {
            val elapsedMs = (System.nanoTime() / 1_000_000L) - startedMonoMs
            DictationDiagnostics.counters.s3CallsFailed.incrementAndGet()
            DictationDiagnostics.recordError(
                stage = "s3_call",
                code = when (error) {
                    is RamblerHttpException -> "HTTP_${error.status}"
                    is RamblerS3ErrorException -> "S3_${error.errorCode}"
                    else -> error.javaClass.simpleName
                },
                error = error,
                opId = opId,
                extraFields = mapOf(
                    "seg" to segmentIndex,
                    "mode" to mode,
                    "httpCode" to httpCode,
                    "protocol" to protocol,
                    "elapsedMs" to elapsedMs,
                    "oggBytesOut" to stats.oggBytesOut.get(),
                    "endRequestSent" to stats.endRequestSent.get(),
                    "framesIn" to stats.framesIn.get(),
                    "partialsIn" to stats.partialsIn.get(),
                    "finalsIn" to stats.finalsIn.get(),
                ),
            )
            throw error
        } finally {
            writerFutureRef.get()?.let { future ->
                if (!writerFinished.get()) {
                    // Wait briefly for graceful close, then cancel if stuck.
                    runCatching { future.get(3, TimeUnit.SECONDS) }
                    if (!future.isDone) future.cancel(true)
                }
            }
        }

        val elapsedMs = (System.nanoTime() / 1_000_000L) - startedMonoMs
        val effectiveGrpcStatus = trailerGrpcStatus ?: headerGrpcStatus
        val effectiveGrpcMessage = trailerGrpcMessage ?: headerGrpcMessage
        val text = accumulator.finalText()
        val writerErr = writerError.get()

        val summaryFields = linkedMapOf<String, Any?>(
            "seg" to segmentIndex,
            "mode" to mode,
            "httpCode" to httpCode,
            "protocol" to protocol,
            "elapsedMs" to elapsedMs,
            "firstRespMs" to stats.firstResponseMs.get(),
            "oggChunksOut" to stats.oggChunksOut.get(),
            "oggBytesOut" to stats.oggBytesOut.get(),
            "endRequestSent" to stats.endRequestSent.get(),
            "writerDone" to writerFinished.get(),
            "readerEof" to readerSawEof,
            "rawBytesIn" to stats.rawBytesIn.get(),
            "framesIn" to stats.framesIn.get(),
            "eventsIn" to stats.nonEmptyEventsIn.get(),
            "partialsIn" to stats.partialsIn.get(),
            "finalsIn" to stats.finalsIn.get(),
            "resultsIn" to stats.resultsIn.get(),
            "fallbacksIn" to stats.fallbacksIn.get(),
            "lastS3Status" to (stats.lastS3Status.get()?.toString() ?: ""),
            "lastRecStatus" to (stats.lastRecStatus.get()?.toString() ?: ""),
            "lastEndpointer" to (stats.lastEndpointer.get()?.toString() ?: ""),
            "grpcStatus" to (effectiveGrpcStatus ?: ""),
            "grpcMsg" to (effectiveGrpcMessage ?: ""),
            "transcriptLen" to text.length,
        )

        val grpcError = validateGrpcStatus(effectiveGrpcStatus, effectiveGrpcMessage)
        if (grpcError != null && text.isEmpty()) {
            DictationDiagnostics.counters.s3CallsFailed.incrementAndGet()
            DictationDiagnostics.recordError(
                stage = "s3_grpc_status",
                code = "GRPC_${grpcError.grpcStatus}",
                error = grpcError,
                opId = opId,
                extraFields = summaryFields,
            )
            throw grpcError
        }

        if (writerErr != null && text.isEmpty()) {
            val wrapped = if (writerErr is IOException) writerErr
            else IOException("Rambler stream write failed: ${writerErr.message}", writerErr)
            DictationDiagnostics.counters.s3CallsFailed.incrementAndGet()
            DictationDiagnostics.recordError(
                stage = "s3_writer_failed",
                code = writerErr.javaClass.simpleName,
                error = wrapped,
                opId = opId,
                extraFields = summaryFields,
            )
            throw wrapped
        }

        // If the server half-closed the response stream BEFORE we even sent endRequest()
        // and returned no transcript, treat it as a stream failure so RamblerLiveSession
        // retries the complete buffered Ogg instead of silently treating it as "no speech".
        if (mode == "live" && readerSawEof && !stats.endRequestSent.get() && text.isEmpty()) {
            val premature = IOException(
                "Rambler S3 closed response before end_of_data (framesIn=${stats.framesIn.get()}, " +
                    "oggBytesOut=${stats.oggBytesOut.get()}, grpcStatus=${effectiveGrpcStatus ?: "none"})",
            )
            DictationDiagnostics.counters.s3CallsFailed.incrementAndGet()
            DictationDiagnostics.recordError(
                stage = "s3_premature_eof",
                code = "PREMATURE_EOF",
                error = premature,
                opId = opId,
                extraFields = summaryFields,
            )
            throw premature
        }

        if (text.isEmpty() && stats.oggBytesOut.get() > 0L) {
            DictationDiagnostics.counters.s3EmptyResponses.incrementAndGet()
        }
        DictationDiagnostics.counters.s3CallsSucceeded.incrementAndGet()
        DictationDiagnostics.record(
            category = "s3",
            event = "call_close",
            opId = opId,
            level = if (text.isEmpty() && stats.oggBytesOut.get() > 0L) {
                DiagnosticFlightRecorder.LEVEL_WARN
            } else {
                DiagnosticFlightRecorder.LEVEL_INFO
            },
            fields = summaryFields,
        )
        return text
    }

    companion object {
        private const val MAX_CHUNK_BYTES = 8192
        private const val PROGRESS_HEARTBEAT_MS = 10_000L
        private val GRPC_MEDIA_TYPE = "application/grpc".toMediaType()
        private val writerExecutor: ExecutorService = Executors.newCachedThreadPool()

        @Volatile
        private var cachedClient: OkHttpClient? = null

        fun sharedClient(): OkHttpClient = cachedClient ?: synchronized(this) {
            cachedClient ?: buildClient().also { cachedClient = it }
        }

        fun resetClient(): OkHttpClient = synchronized(this) {
            cachedClient?.connectionPool?.evictAll()
            buildClient().also { cachedClient = it }
        }

        internal fun validateGrpcStatus(status: String?, message: String?): RamblerGrpcException? {
            val trimmed = status?.trim().orEmpty()
            if (trimmed.isEmpty() || trimmed == "0") return null
            return RamblerGrpcException(trimmed, message?.trim().orEmpty())
        }

        private fun buildClient(): OkHttpClient = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}

internal class RamblerHttpException(val status: Int) : IOException("Rambler returned HTTP $status")

internal class RamblerGrpcException(
    val grpcStatus: String,
    val grpcMessage: String,
) : IOException(
    if (grpcMessage.isNotEmpty()) "Rambler gRPC status $grpcStatus: $grpcMessage"
    else "Rambler gRPC status $grpcStatus",
)

internal class RamblerS3ErrorException(
    val errorCode: Long,
    val errorDescription: String,
) : IOException("Rambler S3 error $errorCode: $errorDescription")
