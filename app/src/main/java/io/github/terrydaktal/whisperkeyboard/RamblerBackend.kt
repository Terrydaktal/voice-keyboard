package io.github.terrydaktal.whisperkeyboard

import android.content.Context
import java.io.File

internal object RamblerBackend : TranscriptionBackend {
    override val id: String = "RAMBLER"

    override fun validateBeforeStart(context: Context): Int? = null

    override fun startLiveSession(context: Context): LiveTranscriptionSession {
        val delegate = RamblerLiveSession(DictationSettings.locale(context))
        return object : LiveTranscriptionSession {
            override fun writePcm(buffer: ByteArray, count: Int) = delegate.writePcm(buffer, count)
            override fun finish(): String = delegate.finish()
            override fun abort() = delegate.abort()
        }
    }

    override fun transcribeWav(context: Context, audio: File, durationMs: Long): String =
        RamblerLiveSession.transcribeWavFile(audio, DictationSettings.locale(context))

    override fun formatError(context: Context, error: Throwable): String = when (error) {
        is RamblerHttpException -> context.getString(R.string.rambler_http_error, error.status)
        else -> context.getString(R.string.rambler_network_error)
    }
}
