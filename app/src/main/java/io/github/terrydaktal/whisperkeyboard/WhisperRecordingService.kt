package io.github.terrydaktal.whisperkeyboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class WhisperRecordingService : Service() {
    internal enum class Phase { READY, STARTING, RECORDING, TRANSCRIBING, RETRY, RESULT, FINISHED }

    internal data class Snapshot(
        val phase: Phase,
        val changedAt: Long = SystemClock.elapsedRealtimeNanos(),
        val transcript: String? = null,
    )

    private class Session(
        val backend: TranscriptionBackend,
        val audio: File,
        var liveSession: LiveTranscriptionSession?,
    ) {
        val stopRequested = AtomicBoolean()
        var durationMs = 0L
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var session: Session? = null

    override fun onCreate() {
        super.onCreate()
        current = this
        cacheDir.listFiles { file -> file.name.startsWith(AUDIO_PREFIX) && file.extension == "wav" }
            ?.forEach { it.delete() }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.recording_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> if (session == null) beginRecording()
            STOP -> requestStop()
            else -> if (session == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun beginRecording() {
        if (!promote(recording = true)) {
            finish(message = R.string.microphone_error)
            return
        }
        val backend = TranscriptionRegistry.activeBackend(this)
        backend.validateBeforeStart(this)?.let { errorRes ->
            finish(message = errorRes)
            return
        }
        val liveSession = backend.startLiveSession(this)
        val active = Session(
            backend = backend,
            audio = File(cacheDir, "${AUDIO_PREFIX}${UUID.randomUUID()}.wav"),
            liveSession = liveSession,
        )
        session = active
        publish(Snapshot(Phase.RECORDING))
        worker.execute {
            val result = runCatching {
                WavRecording.record(
                    file = active.audio,
                    shouldStop = active.stopRequested::get,
                    onLevel = { level ->
                        main.post {
                            if (session === active && snapshot.phase == Phase.RECORDING) {
                                levelListeners.toList().forEach { it(level) }
                            }
                        }
                    },
                    onPcmChunk = liveSession?.let { live ->
                        { buf, count -> live.writePcm(buf, count) }
                    },
                )
            }
            main.post { recordingFinished(active, result) }
        }
    }

    private fun requestStop() {
        if (snapshot.phase != Phase.RECORDING) return
        session?.stopRequested?.set(true)
        publish(Snapshot(Phase.TRANSCRIBING))
    }

    private fun recordingFinished(active: Session, result: Result<Long>) {
        if (session !== active) {
            active.liveSession?.abort()
            active.audio.delete()
            return
        }
        val duration = result.getOrElse {
            Log.w(TAG, "Microphone capture failed", it)
            active.liveSession?.abort()
            finish(message = R.string.microphone_error)
            return
        }
        if (duration == 0L) {
            active.liveSession?.abort()
            finish(message = R.string.no_speech)
            return
        }
        active.durationMs = duration
        val live = active.liveSession
        if (live != null) {
            finalizeLiveSession(active, live)
        } else {
            transcribeSavedAudio(active, active.backend)
        }
    }

    private fun finalizeLiveSession(active: Session, live: LiveTranscriptionSession) {
        active.liveSession = null
        if (!promote(recording = false)) {
            live.abort()
            retryAvailable(active.backend.formatError(this, IllegalStateException("Foreground service unavailable")))
            return
        }
        publish(Snapshot(Phase.TRANSCRIBING))
        worker.execute {
            val result = runCatching { live.finish() }
            main.post { handleTranscriptionResult(active, active.backend, result) }
        }
    }

    private fun transcribeSavedAudio(active: Session, backend: TranscriptionBackend) {
        backend.validateBeforeStart(this)?.let { errorRes ->
            retryAvailable(getString(errorRes))
            return
        }
        if (!promote(recording = false)) {
            retryAvailable(backend.formatError(this, IllegalStateException("Foreground service unavailable")))
            return
        }
        publish(Snapshot(Phase.TRANSCRIBING))
        worker.execute {
            val result = runCatching { backend.transcribeWav(this, active.audio, active.durationMs) }
            main.post { handleTranscriptionResult(active, backend, result) }
        }
    }

    private fun handleTranscriptionResult(
        active: Session,
        backend: TranscriptionBackend,
        result: Result<String>,
    ) {
        if (session !== active) return
        val text = result.getOrElse { error ->
            Log.w(TAG, "Transcription failed on ${backend.id}", error)
            retryAvailable(backend.formatError(this, error))
            return
        }
        if (text.isEmpty()) finish(message = R.string.no_speech) else finish(text)
    }

    private fun retryAvailable(message: String? = null) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        publish(Snapshot(Phase.RETRY))
        if (message != null) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun finish(text: String? = null, message: Int? = null) {
        session?.liveSession?.abort()
        session?.audio?.delete()
        session = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (message != null) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        publish(Snapshot(if (text == null) Phase.FINISHED else Phase.RESULT, transcript = text))
    }

    private fun promote(recording: Boolean): Boolean {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_microphone)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(if (recording) R.string.recording else R.string.transcribing))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (recording) {
            val stop = PendingIntent.getService(
                this, 0, Intent(this, WhisperRecordingService::class.java).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            notification.addAction(
                Notification.Action.Builder(null, getString(R.string.stop_recording), stop).build(),
            )
        }
        return try {
            if (recording && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, notification.build())
            } else {
                val type = if (recording) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                startForeground(NOTIFICATION_ID, notification.build(), type)
            }
            true
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not start the foreground dictation service", error)
            false
        }
    }

    override fun onDestroy() {
        session?.let {
            it.stopRequested.set(true)
            it.liveSession?.abort()
            it.audio.delete()
            session = null
            publish(Snapshot(Phase.FINISHED))
        }
        if (current === this) current = null
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val START = "start_recording"
        private const val STOP = "stop_recording"
        private const val TAG = "WhisperRecording"
        private const val AUDIO_PREFIX = "dictation-"
        private const val CHANNEL_ID = "whisper_recording"
        private const val NOTIFICATION_ID = 1
        private var current: WhisperRecordingService? = null
        internal val listeners = mutableSetOf<(Snapshot) -> Unit>()
        internal val levelListeners = mutableSetOf<(Float) -> Unit>()
        internal var manualInsertRequired = false
            private set

        @Volatile internal var snapshot = Snapshot(Phase.READY)
            private set

        internal fun start(context: Context) {
            if (snapshot.phase != Phase.READY && snapshot.phase != Phase.FINISHED) return
            manualInsertRequired = false
            publish(Snapshot(Phase.STARTING))
            try {
                context.startForegroundService(Intent(context, WhisperRecordingService::class.java).setAction(START))
            } catch (error: RuntimeException) {
                Log.w(TAG, "Android did not allow microphone service startup", error)
                publish(Snapshot(Phase.FINISHED))
                Toast.makeText(context, R.string.microphone_error, Toast.LENGTH_SHORT).show()
            }
        }

        internal fun stopFromKeyboard() {
            manualInsertRequired = false
            current?.requestStop()
        }

        internal fun retry() {
            if (snapshot.phase != Phase.RETRY) return
            val service = current ?: return
            val active = service.session ?: return
            manualInsertRequired = false
            service.transcribeSavedAudio(active, TranscriptionRegistry.activeBackend(service))
        }

        internal fun deferInsertion() {
            manualInsertRequired = true
        }

        internal fun transcriptInserted() {
            if (snapshot.phase == Phase.RESULT) publish(Snapshot(Phase.FINISHED))
        }

        private fun publish(next: Snapshot) {
            snapshot = next
            listeners.toList().forEach { it(next) }
        }
    }
}
