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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class WhisperRecordingService : Service() {
    internal enum class Phase { READY, STARTING, RECORDING, TRANSCRIBING, RESULT, FINISHED }

    internal data class Snapshot(
        val phase: Phase,
        val changedAt: Long = SystemClock.elapsedRealtimeNanos(),
        val transcript: String? = null,
        val pendingCount: Int = 0,
    )

    private class Session(
        val backend: TranscriptionBackend,
        var audio: File,
        var liveSession: LiveTranscriptionSession?,
        var persistedInQueue: Boolean = false,
        val inFlightFiles: MutableSet<String> = mutableSetOf(),
    ) {
        val stopRequested = AtomicBoolean()
        var durationMs = 0L
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var store: PendingRecordingStore
    private var session: Session? = null
    private var autoRetryDelayMs = AUTO_RETRY_INITIAL_MS
    private val autoRetryRunnable = Runnable { runAutoRetryTick() }

    override fun onCreate() {
        super.onCreate()
        current = this
        store = PendingRecordingStore.forContext(this)
        // Clean legacy cacheDir temp files from older app versions and crashed active captures.
        cacheDir.listFiles { file -> file.name.startsWith(LEGACY_AUDIO_PREFIX) && file.extension == "wav" }
            ?.forEach { it.delete() }
        store.cleanOrphanedActiveFiles()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.recording_channel), NotificationManager.IMPORTANCE_LOW),
        )
        refreshPendingCount(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> if (session == null) beginRecording()
            STOP -> requestStop()
            RETRY_OLDEST -> if (session == null) retryPending(all = false, silent = false)
            RETRY_ALL -> if (session == null) retryPending(all = true, silent = false)
            else -> if (session == null) stopSelfIfIdle()
        }
        return START_NOT_STICKY
    }

    private fun beginRecording() {
        cancelAutoRetry()
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
            audio = store.newActiveFile(),
            liveSession = liveSession,
        )
        session = active
        publish(Snapshot(Phase.RECORDING, pendingCount = visiblePendingCount()))
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
        publish(Snapshot(Phase.TRANSCRIBING, pendingCount = visiblePendingCount()))
    }

    private fun recordingFinished(active: Session, result: Result<Long>) {
        if (session !== active) {
            active.liveSession?.abort()
            if (!active.persistedInQueue) active.audio.delete()
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

        // Promote the finalized WAV into the persistent queue BEFORE network transcription
        // so an app crash after Stop can never lose the captured audio.
        val persisted = store.enqueue(active.audio, protectedNames = protectedFileNames())
        if (persisted != null) {
            active.audio = persisted
            active.persistedInQueue = true
            active.inFlightFiles.add(persisted.name)
        }

        val live = active.liveSession
        if (live != null) {
            finalizeLiveSession(active, live)
        } else {
            transcribeActiveFile(active, active.backend)
        }
    }

    private fun finalizeLiveSession(active: Session, live: LiveTranscriptionSession) {
        active.liveSession = null
        if (!promote(recording = false)) {
            live.abort()
            leaveInQueueAfterFailure(
                active,
                active.backend.formatError(this, IllegalStateException("Foreground service unavailable")),
            )
            return
        }
        publish(Snapshot(Phase.TRANSCRIBING, pendingCount = visiblePendingCount()))
        worker.execute {
            val result = runCatching { live.finish() }
            main.post { handleActiveTranscriptionResult(active, active.backend, result) }
        }
    }

    private fun transcribeActiveFile(active: Session, backend: TranscriptionBackend) {
        backend.validateBeforeStart(this)?.let { errorRes ->
            leaveInQueueAfterFailure(active, getString(errorRes))
            return
        }
        if (!promote(recording = false)) {
            leaveInQueueAfterFailure(
                active,
                backend.formatError(this, IllegalStateException("Foreground service unavailable")),
            )
            return
        }
        publish(Snapshot(Phase.TRANSCRIBING, pendingCount = visiblePendingCount()))
        worker.execute {
            val result = runCatching { backend.transcribeWav(this, active.audio, active.durationMs) }
            main.post { handleActiveTranscriptionResult(active, backend, result) }
        }
    }

    private fun handleActiveTranscriptionResult(
        active: Session,
        backend: TranscriptionBackend,
        result: Result<String>,
    ) {
        if (session !== active) return
        val text = result.getOrElse { error ->
            Log.w(TAG, "Transcription failed on ${backend.id}; keeping recording in persistent queue", error)
            leaveInQueueAfterFailure(active, backend.formatError(this, error))
            return
        }
        autoRetryDelayMs = AUTO_RETRY_INITIAL_MS
        if (text.isEmpty()) {
            // Nothing to deliver — delete the recording immediately.
            store.delete(active.audio)
            finish(message = R.string.no_speech)
        } else {
            // Defer deletion until commitText() delivers the transcript so a crash
            // or deferred insertion cannot lose either the transcript or the WAV.
            deliverTranscript(text, listOf(active.audio))
        }
    }

    private fun retryPending(all: Boolean, silent: Boolean) {
        cancelAutoRetry()
        val excluded = protectedFileNames()
        val entries = if (all) store.listPending(excluded) else listOfNotNull(store.oldest(excluded))
        if (entries.isEmpty()) {
            publish(Snapshot(Phase.FINISHED, pendingCount = visiblePendingCount()))
            stopSelfIfIdle()
            return
        }
        val backend = TranscriptionRegistry.activeBackend(this)
        backend.validateBeforeStart(this)?.let { errorRes ->
            if (!silent) Toast.makeText(this, errorRes, Toast.LENGTH_SHORT).show()
            publish(Snapshot(Phase.FINISHED, pendingCount = visiblePendingCount()))
            stopSelfIfIdle()
            return
        }
        if (!promote(recording = false)) {
            if (!silent) {
                Toast.makeText(
                    this,
                    backend.formatError(this, IllegalStateException("Foreground service unavailable")),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            publish(Snapshot(Phase.FINISHED, pendingCount = visiblePendingCount()))
            stopSelfIfIdle()
            return
        }
        val active = Session(
            backend = backend,
            audio = entries.first().file,
            liveSession = null,
            persistedInQueue = true,
            inFlightFiles = entries.mapTo(mutableSetOf()) { it.file.name },
        )
        session = active
        publish(Snapshot(Phase.TRANSCRIBING, pendingCount = visiblePendingCount()))
        worker.execute {
            val transcripts = mutableListOf<String>()
            val filesToDeleteOnDelivery = mutableListOf<File>()
            val emptySpeechFiles = mutableListOf<File>()
            var lastError: Throwable? = null
            var succeededAny = false
            for (entry in entries) {
                val res = runCatching { backend.transcribeWav(this, entry.file, entry.durationMs) }
                val text = res.getOrNull()
                if (text != null) {
                    succeededAny = true
                    if (text.isNotEmpty()) {
                        transcripts.add(text)
                        filesToDeleteOnDelivery.add(entry.file)
                    } else {
                        emptySpeechFiles.add(entry.file)
                    }
                } else {
                    lastError = res.exceptionOrNull()
                    break
                }
            }
            main.post {
                if (session !== active) return@post
                for (emptyFile in emptySpeechFiles) {
                    store.delete(emptyFile)
                }
                val joined = transcripts.joinToString(" ").trim()
                if (succeededAny && joined.isNotEmpty()) {
                    autoRetryDelayMs = AUTO_RETRY_INITIAL_MS
                    deliverTranscript(joined, filesToDeleteOnDelivery)
                } else if (succeededAny) {
                    autoRetryDelayMs = AUTO_RETRY_INITIAL_MS
                    session = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    if (!silent) Toast.makeText(this, R.string.no_speech, Toast.LENGTH_SHORT).show()
                    val remaining = visiblePendingCount()
                    publish(Snapshot(Phase.FINISHED, pendingCount = remaining))
                    if (remaining > 0) scheduleAutoRetryIfVisible() else stopSelfIfIdle()
                } else {
                    session = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    val remaining = visiblePendingCount()
                    val error = lastError
                    Log.w(TAG, "Pending retry failed on ${backend.id} (remaining=$remaining)", error)
                    if (!silent && error != null) {
                        Toast.makeText(this, backend.formatError(this, error), Toast.LENGTH_SHORT).show()
                    }
                    publish(Snapshot(Phase.FINISHED, pendingCount = remaining))
                    autoRetryDelayMs = if (silent) {
                        (autoRetryDelayMs * 2).coerceAtMost(AUTO_RETRY_MAX_MS)
                    } else {
                        AUTO_RETRY_INITIAL_MS
                    }
                    scheduleAutoRetryIfVisible()
                }
            }
        }
    }

    private fun deliverTranscript(text: String, sourceFiles: List<File>) {
        session = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        pendingDeliveryFiles = sourceFiles.toList()
        val remaining = visiblePendingCount()
        publish(Snapshot(Phase.RESULT, transcript = text, pendingCount = remaining))
    }

    private fun leaveInQueueAfterFailure(active: Session, errorMessage: String) {
        active.liveSession?.abort()
        if (!active.persistedInQueue) {
            store.enqueue(active.audio, protectedNames = protectedFileNames())
        }
        session = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        val pending = visiblePendingCount()
        Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show()
        publish(Snapshot(Phase.FINISHED, pendingCount = pending))
        autoRetryDelayMs = AUTO_RETRY_INITIAL_MS
        scheduleAutoRetryIfVisible()
    }

    private fun finish(text: String? = null, message: Int? = null) {
        session?.let { active ->
            active.liveSession?.abort()
            if (!active.persistedInQueue) active.audio.delete()
        }
        session = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (message != null) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        val remaining = visiblePendingCount()
        publish(
            Snapshot(
                phase = if (text == null) Phase.FINISHED else Phase.RESULT,
                transcript = text,
                pendingCount = remaining,
            ),
        )
        if (remaining > 0) {
            scheduleAutoRetryIfVisible()
        } else {
            stopSelfIfIdle()
        }
    }

    private fun visiblePendingCount(): Int = store.count(excluding = protectedFileNames())

    private fun scheduleAutoRetryIfVisible() {
        cancelAutoRetry()
        if (!keyboardVisible || session != null) return
        if (snapshot.phase == Phase.RESULT) return
        if (visiblePendingCount() == 0) return
        main.postDelayed(autoRetryRunnable, autoRetryDelayMs)
    }

    private fun cancelAutoRetry() {
        main.removeCallbacks(autoRetryRunnable)
    }

    private fun runAutoRetryTick() {
        if (!keyboardVisible || session != null || snapshot.phase == Phase.RESULT) return
        if (visiblePendingCount() == 0) {
            stopSelfIfIdle()
            return
        }
        retryPending(all = false, silent = true)
    }

    private fun stopSelfIfIdle() {
        if (session == null &&
            pendingDeliveryFiles.isEmpty() &&
            (!keyboardVisible || visiblePendingCount() == 0)
        ) {
            cancelAutoRetry()
            stopSelf()
        }
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
        cancelAutoRetry()
        session?.let { active ->
            active.stopRequested.set(true)
            active.liveSession?.abort()
            // Preserve persisted queue files across crashes/restarts; delete only incomplete temp files.
            if (!active.persistedInQueue) active.audio.delete()
            session = null
        }
        // Do NOT delete pendingDeliveryFiles here — if the transcript wasn't delivered yet,
        // those WAVs stay safely in pending_dictations for the next launch.
        pendingDeliveryFiles = emptyList()
        publish(Snapshot(Phase.FINISHED, pendingCount = store.count()))
        if (current === this) current = null
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val START = "start_recording"
        private const val STOP = "stop_recording"
        private const val RETRY_OLDEST = "retry_oldest"
        private const val RETRY_ALL = "retry_all"
        private const val TAG = "WhisperRecording"
        private const val LEGACY_AUDIO_PREFIX = "dictation-"
        private const val CHANNEL_ID = "whisper_recording"
        private const val NOTIFICATION_ID = 1
        private const val AUTO_RETRY_INITIAL_MS = 3_000L
        private const val AUTO_RETRY_MAX_MS = 30_000L

        private var current: WhisperRecordingService? = null
        @Volatile private var keyboardVisible = false
        @Volatile private var pendingDeliveryFiles: List<File> = emptyList()
        internal val listeners = mutableSetOf<(Snapshot) -> Unit>()
        internal val levelListeners = mutableSetOf<(Float) -> Unit>()
        internal var manualInsertRequired = false
            private set

        @Volatile internal var snapshot = Snapshot(Phase.READY)
            private set

        private fun protectedFileNames(): Set<String> = buildSet {
            current?.session?.inFlightFiles?.let { addAll(it) }
            pendingDeliveryFiles.forEach { add(it.name) }
        }

        internal fun refreshPendingCount(context: Context): Int {
            val count = PendingRecordingStore.forContext(context).count(excluding = protectedFileNames())
            if (snapshot.pendingCount != count) {
                publish(snapshot.copy(pendingCount = count))
            }
            return count
        }

        internal fun pendingSummary(context: Context): Pair<Int, Long> {
            val excluded = protectedFileNames()
            val store = PendingRecordingStore.forContext(context)
            return store.count(excluding = excluded) to store.totalBytes(excluding = excluded)
        }

        internal fun setKeyboardVisible(context: Context, visible: Boolean) {
            keyboardVisible = visible
            val pending = refreshPendingCount(context)
            val service = current
            if (visible) {
                if (pending > 0 && service != null) {
                    service.autoRetryDelayMs = AUTO_RETRY_INITIAL_MS
                    service.scheduleAutoRetryIfVisible()
                }
            } else {
                service?.cancelAutoRetry()
                service?.stopSelfIfIdle()
            }
        }

        internal fun start(context: Context) {
            if (snapshot.phase != Phase.READY && snapshot.phase != Phase.FINISHED) return
            manualInsertRequired = false
            val pending = PendingRecordingStore.forContext(context).count(excluding = protectedFileNames())
            publish(Snapshot(Phase.STARTING, pendingCount = pending))
            try {
                context.startForegroundService(Intent(context, WhisperRecordingService::class.java).setAction(START))
            } catch (error: RuntimeException) {
                Log.w(TAG, "Android did not allow microphone service startup", error)
                publish(Snapshot(Phase.FINISHED, pendingCount = pending))
                Toast.makeText(context, R.string.microphone_error, Toast.LENGTH_SHORT).show()
            }
        }

        internal fun stopFromKeyboard() {
            manualInsertRequired = false
            current?.requestStop()
        }

        internal fun retryPending(context: Context, all: Boolean = false) {
            if (snapshot.phase != Phase.READY && snapshot.phase != Phase.FINISHED) return
            val count = PendingRecordingStore.forContext(context).count(excluding = protectedFileNames())
            if (count == 0) {
                refreshPendingCount(context)
                return
            }
            manualInsertRequired = false
            publish(Snapshot(Phase.TRANSCRIBING, pendingCount = count))
            val action = if (all) RETRY_ALL else RETRY_OLDEST
            try {
                context.startForegroundService(Intent(context, WhisperRecordingService::class.java).setAction(action))
            } catch (error: RuntimeException) {
                Log.w(TAG, "Android did not allow retry service startup", error)
                publish(Snapshot(Phase.FINISHED, pendingCount = count))
                Toast.makeText(context, R.string.network_error, Toast.LENGTH_SHORT).show()
            }
        }

        internal fun discardOldestPending(context: Context) {
            val store = PendingRecordingStore.forContext(context)
            store.oldest(excluding = protectedFileNames())?.let { store.delete(it.file) }
            refreshPendingCount(context)
        }

        internal fun clearAllPending(context: Context): Int {
            val removed = PendingRecordingStore.forContext(context).clearAll(excluding = protectedFileNames())
            refreshPendingCount(context)
            return removed
        }

        internal fun deferInsertion() {
            manualInsertRequired = true
        }

        internal fun transcriptInserted() {
            if (snapshot.phase != Phase.RESULT) return
            // Auto-delete source WAVs only after commitText() has delivered the transcript.
            val delivered = pendingDeliveryFiles
            pendingDeliveryFiles = emptyList()
            for (file in delivered) {
                file.delete()
            }
            val service = current
            val remaining = service?.visiblePendingCount() ?: snapshot.pendingCount
            publish(Snapshot(Phase.FINISHED, pendingCount = remaining))
            if (remaining > 0) {
                service?.scheduleAutoRetryIfVisible()
            } else {
                service?.stopSelfIfIdle()
            }
        }

        private fun publish(next: Snapshot) {
            snapshot = next
            listeners.toList().forEach { it(next) }
        }
    }
}
