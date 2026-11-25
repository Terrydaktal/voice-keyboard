package io.github.terrydaktal.whisperkeyboard

import android.content.Context
import java.io.File

internal interface LiveTranscriptionSession {
    fun writePcm(buffer: ByteArray, count: Int)
    fun finish(): String
    fun abort()
}

internal interface TranscriptionBackend {
    val id: String
    fun validateBeforeStart(context: Context): Int?
    fun startLiveSession(context: Context, opId: String = ""): LiveTranscriptionSession?
    fun transcribeWav(context: Context, audio: File, durationMs: Long, opId: String = ""): String
    fun formatError(context: Context, error: Throwable): String
}

internal object WhisperBackend : TranscriptionBackend {
    override val id: String = "WHISPER"

    override fun validateBeforeStart(context: Context): Int? =
        if (WhisperEndpoint.isConfigured(context)) null else R.string.address_invalid

    override fun startLiveSession(context: Context, opId: String): LiveTranscriptionSession? = null

    override fun transcribeWav(context: Context, audio: File, durationMs: Long, opId: String): String {
        val endpoint = WhisperEndpoint.parse(WhisperEndpoint.savedAddress(context))
            ?: throw IllegalStateException("Whisper server URL is not configured.")
        return WhisperEndpoint.transcribe(endpoint, audio, durationMs, opId)
    }

    override fun formatError(context: Context, error: Throwable): String = when (error) {
        is WhisperHttpException -> context.getString(R.string.whisper_http_error, error.status)
        is IllegalStateException -> context.getString(R.string.address_invalid)
        else -> context.getString(R.string.network_error)
    }
}

internal object TranscriptionRegistry {
    fun activeBackend(context: Context): TranscriptionBackend = when (DictationSettings.backend(context)) {
        DictationBackend.RAMBLER -> RamblerBackend
        DictationBackend.WHISPER -> WhisperBackend
    }
}
