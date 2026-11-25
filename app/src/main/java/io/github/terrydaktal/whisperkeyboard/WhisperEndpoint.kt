package io.github.terrydaktal.whisperkeyboard

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import org.json.JSONObject

internal object WhisperEndpoint {
    private const val URL_KEY = "transcribe_url"

    fun savedAddress(context: Context): String =
        preferences(context).getString(URL_KEY, "")?.trim().orEmpty()

    fun isConfigured(context: Context): Boolean = parse(savedAddress(context)) != null

    fun save(context: Context, url: URL) {
        preferences(context).edit().putString(URL_KEY, url.toExternalForm()).apply()
    }

    fun clear(context: Context) {
        preferences(context).edit().remove(URL_KEY).apply()
    }

    fun parse(address: String): URL? = runCatching {
        val trimmed = address.trim()
        require(trimmed.isNotEmpty())
        val uri = URI(trimmed)
        require(uri.scheme == "http" || uri.scheme == "https")
        require(uri.host != null && uri.userInfo == null && uri.fragment == null)
        require(uri.port == -1 || uri.port in 1..65535)
        val path = if (uri.path.isNullOrEmpty() || uri.path == "/") "/transcribe_raw" else uri.path
        URI(uri.scheme, null, uri.host, uri.port, path, uri.query, null).toURL()
    }.getOrNull()

    private fun preferences(context: Context) =
        context.getSharedPreferences("whisper_keyboard", Context.MODE_PRIVATE)

    fun transcribe(endpoint: URL, recording: File, durationMs: Long, opId: String = ""): String {
        val startedNanos = System.nanoTime()
        val safeUrl = DiagnosticFlightRecorder.sanitizeUrlForLog(endpoint.toExternalForm())
        DictationDiagnostics.counters.whisperCallsStarted.incrementAndGet()
        DictationDiagnostics.record(
            category = "whisper",
            event = "request_start",
            opId = opId,
            fields = mapOf(
                "url" to safeUrl,
                "audioBytes" to recording.length(),
                "durationMs" to durationMs,
            ),
        )
        val connection = endpoint.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8_000
            connection.readTimeout = 180_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "audio/wav")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Client-Recording-Duration-Ms", durationMs.toString())
            connection.setFixedLengthStreamingMode(recording.length())

            recording.inputStream().use { audio ->
                connection.outputStream.use { request -> audio.copyTo(request) }
            }

            val status = connection.responseCode
            val elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L
            if (status !in 200..299) {
                val err = WhisperHttpException(status)
                DictationDiagnostics.counters.whisperCallsFailed.incrementAndGet()
                DictationDiagnostics.recordError(
                    stage = "whisper_http",
                    code = "HTTP_$status",
                    error = err,
                    opId = opId,
                    extraFields = mapOf("url" to safeUrl, "elapsedMs" to elapsedMs),
                )
                throw err
            }
            val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val text = JSONObject(json).opt("text") as? String
                ?: throw IOException("Whisper response is missing the transcript.")
            val trimmed = text.trim()
            DictationDiagnostics.counters.whisperCallsSucceeded.incrementAndGet()
            DictationDiagnostics.record(
                category = "whisper",
                event = "response_ok",
                opId = opId,
                fields = mapOf(
                    "httpStatus" to status,
                    "elapsedMs" to elapsedMs,
                    "transcriptLen" to trimmed.length,
                ),
            )
            return trimmed
        } catch (error: Throwable) {
            if (error !is WhisperHttpException) {
                val elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L
                DictationDiagnostics.counters.whisperCallsFailed.incrementAndGet()
                DictationDiagnostics.recordError(
                    stage = "whisper_io",
                    code = error.javaClass.simpleName,
                    error = error,
                    opId = opId,
                    extraFields = mapOf("url" to safeUrl, "elapsedMs" to elapsedMs),
                )
            }
            throw error
        } finally {
            connection.disconnect()
        }
    }
}

internal class WhisperHttpException(val status: Int) : IOException("Whisper returned HTTP $status")
