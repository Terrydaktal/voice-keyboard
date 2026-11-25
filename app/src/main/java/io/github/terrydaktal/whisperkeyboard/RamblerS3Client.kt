package io.github.terrydaktal.whisperkeyboard

import java.io.IOException
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
) {
    /**
     * Streams live Ogg/Opus chunks from [oggQueue] until an empty [ByteArray] EOF sentinel
     * is received, returning the final joined transcript.
     */
    fun streamLive(
        oggQueue: BlockingQueue<ByteArray>,
        sentAudioFlag: AtomicBoolean? = null,
    ): String = executeBidi { sink ->
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
            writeOggChunk(sink, chunk)
        }

        sink.write(RamblerS3Proto.grpcFrame(RamblerS3Proto.endRequest()))
        sink.flush()
    }

    /**
     * Transcribes an already-buffered sequence of Ogg/Opus chunks in one HTTP/2 S3Bidi call.
     */
    fun transcribeChunks(chunks: List<ByteArray>): String = executeBidi { sink ->
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
                writeOggChunk(sink, chunk)
            }
        }

        sink.write(RamblerS3Proto.grpcFrame(RamblerS3Proto.endRequest()))
        sink.flush()
    }

    private fun writeOggChunk(sink: BufferedSink, chunk: ByteArray) {
        var offset = 0
        while (offset < chunk.size) {
            val len = minOf(MAX_CHUNK_BYTES, chunk.size - offset)
            val payload = RamblerS3Proto.chunkRequest(chunk, offset, len)
            sink.write(RamblerS3Proto.grpcFrame(payload))
            offset += len
        }
        sink.flush()
    }

    private fun executeBidi(writerAction: (BufferedSink) -> Unit): String {
        val writerError = AtomicReference<Throwable?>(null)
        val writerFinished = AtomicBoolean(false)
        val writerFutureRef = AtomicReference<java.util.concurrent.Future<*>?>(null)

        val body = object : RequestBody() {
            override fun contentType(): MediaType = GRPC_MEDIA_TYPE
            override fun isDuplex(): Boolean = true
            override fun isOneShot(): Boolean = true

            override fun writeTo(sink: BufferedSink) {
                // OkHttp duplex mode expects writeTo() to return immediately so it can
                // read response headers concurrently while this background thread writes.
                val future = writerExecutor.submit {
                    try {
                        writerAction(sink)
                    } catch (error: Throwable) {
                        writerError.compareAndSet(null, error)
                    } finally {
                        runCatching { sink.close() }
                        writerFinished.set(true)
                    }
                }
                writerFutureRef.set(future)
            }
        }

        val request = Request.Builder()
            .url(RamblerS3Proto.URL)
            .header("te", "trailers")
            .header("x-goog-api-key", RamblerS3Proto.API_KEY)
            .header("x-android-package", RamblerS3Proto.ANDROID_PACKAGE)
            .header("x-android-cert", RamblerS3Proto.ANDROID_CERT)
            .post(body)
            .build()

        val accumulator = RamblerS3Proto.TranscriptAccumulator()
        val splitter = RamblerS3Proto.GrpcFrameSplitter()

        val call = client.newCall(request)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw RamblerHttpException(response.code)
                }
                val source = response.body?.source()
                    ?: throw IOException("Rambler S3 returned an empty response body.")
                val readBuffer = ByteArray(8192)
                while (true) {
                    val count = source.read(readBuffer)
                    if (count == -1) break
                    for (frame in splitter.feed(readBuffer, count)) {
                        val event = RamblerS3Proto.decodeResponse(frame)
                        if (event.isEmpty) continue
                        val errCode = event.errorCode
                        if (errCode != null && errCode != 0L) {
                            throw IOException("Rambler S3 error $errCode: ${event.errorDescription.orEmpty()}")
                        }
                        accumulator.accept(event)
                    }
                }
            }
        } finally {
            writerFutureRef.get()?.let { future ->
                if (!writerFinished.get()) {
                    // Wait briefly for graceful close, then cancel if stuck.
                    runCatching { future.get(3, TimeUnit.SECONDS) }
                    if (!future.isDone) future.cancel(true)
                }
            }
        }

        val err = writerError.get()
        if (err != null && accumulator.finalText().isEmpty()) {
            if (err is IOException) throw err
            throw IOException("Rambler stream write failed: ${err.message}", err)
        }
        return accumulator.finalText()
    }

    companion object {
        private const val MAX_CHUNK_BYTES = 8192
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
