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
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class WhisperRecordingService : Service() {
    internal enum class Phase { READY, STARTING, RECORDING, TRANSCRIBING, RESULT, FINISHED }

    internal data class Snapshot(
        val phase: Phase,
        val changedAt: Long = SystemClock.elapsedRealtimeNanos(),
        val transcript: String? = null,
        val pendingCount: Int = 0,
        val opId: String = "",
    )

    private class Session(
        val opId: String,
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
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "voice-kb-worker") }
    private lateinit var store: PendingRecordingStore
    private var session: Session? = null
    private var autoRetryDelayMs = AUTO_RETRY_INITIAL_MS
    private val autoRetryRunnable = Runnable { runAutoRetryTick() }

    override fun onCreate() {
        super.onCreate()
        DictationDiagnostics.init(this)
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
        DictationDiagnostics.record(
            category = "lifecycle",
            event = "recording_service_create",
            fields = mapOf("pendingCount" to visiblePendingCount()),
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun dump(fd: FileDescriptor?, writer: PrintWriter?, args: Array<out String>?) {
        if (writer == null) return
        DictationDiagnostics.dumpToWriter(writer, this, "WhisperRecordingService")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action.orEmpty()
        DictationDiagnostics.record(
            category = "lifecycle",
            event = "service_command",
            opId = session?.opId.orEmpty(),
            fields = mapOf(
                "action" to action.ifEmpty { "null" },
                "hasSession" to (session != null),
                "phase" to snapshot.phase.name,
            ),
        )
        when (intent?.action) {
            START -> if (session == null) beginRecording()
            STOP -> requestStop(source = "notification")
            RETRY_OLDEST -> if (session == null) retryPending(all = false, silent = false)
            RETRY_ALL -> if (session == null) retryPending(all = true, silent = false)
            else -> if (session == null) stopSelfIfIdle()
        }
        return START_NOT_STICKY
    }

    private fun beginRecording() {
        cancelAutoRetry()
        val opId = DictationDiagnostics.nextOpId("rec")
        DictationDiagnostics.counters.sessionsStarted.incrementAndGet()
        if (!promote(recording = true, opId = opId)) {
            DictationDiagnostics.counters.sessionsFailed.incrementAndGet()
            finish(message = R.string.microphone_error, reason = "promote_recording_failed", opId = opId)
            return
        }
        val backend = TranscriptionRegistry.activeBackend(this)
        backend.validateBeforeStart(this)?.let { errorRes ->
            DictationDiagnostics.counters.sessionsFailed.incrementAndGet()
            DictationDiagnostics.record(
                category = "state",
                event = "backend_validation_failed",
                opId = opId,
                level = DiagnosticFlightRecorder.LEVEL_WARN,
                fields = mapOf("backend" to backend.id),
            )
            finish(message = errorRes, reason = "backend_validation_failed", opId = opId)
            return
        }
        val liveSession = backend.startLiveSession(this, opId)
        val active = Session(
            opId = opId,
            backend = backend,
            audio = store.newActiveFile(),
            liveSession = liveSession,
        )
        session = active
        DictationDiagnostics.record(
            category = "state",
            event = "recording_started",
            opId = opId,
            fields = mapOf(
                "backend" to backend.id,
                "liveStreaming" to (liveSession != null),
                "activeFile" to active.audio.name,
            ),
        )
        publish(
            Snapshot(Phase.RECORDING, pendingCount = visiblePendingCount(), opId = opId),
            reason = "recording_started",
            backendId = backend.id,
        )
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

    private fun requestStop(source: String = "keyboard") {
        if (snapshot.phase != Phase.RECORDING) {
            DictationDiagnostics.record(
                category = "state",
                event = "stop_ignored",
                opId = session?.opId.orEmpty(),
                level = DiagnosticFlightRecorder.LEVEL_WARN,
                fields = mapOf("source" to source, "phase" to snapshot.phase.name),
            )
            return
        }
        val opId = session?.opId.orEmpty()
        DictationDiagnostics.record(
            category = "state",
            event = "stop_requested",
            opId = opId,
            fields = mapOf("source" to source),
        )
        session?.stopRequested?.set(true)
        publish(
            Snapshot(Phase.TRANSCRIBING, pendingCount = visiblePendingCount(), opId = opId),
            reason = "stop_requested_$source",
            backendId = session?.backend?.id.orEmpty(),
        )
    }

    private fun recordingFinished(active: Session, result: Result<Long>) {
        if (session !== active) {
            DictationDiagnostics.counters.sessionsAborted.incrementAndGet()
            DictationDiagnostics.record(
                category = "state",
                event = "recording_discarded_stale_session",
                opId = active.opId,
                level = DiagnosticFlightRecorder.LEVEL_WARN,
            )
            active.liveSession?.abort()
            if (!active.persistedInQueue) active.audio.delete()
            return
        }
        val duration = result.getOrElse {
            Log.w(TAG, "Microphone capture failed", it)
            DictationDiagnostics.counters.sessionsFailed.incrementAndGet()
            DictationDiagnostics.recordError(
                stage = "audio_capture",
                code = it.javaClass.simpleName,
                error = it,
                opId = active.opId,
            )
            active.liveSession?.abort()
            finish(message = R.string.microphone_error, reason = "audio_capture_failed", opId = active.opId)
            return
        }
        val wavBytes = active.audio.length()
        DictationDiagnostics.record(
            category = "audio",
            event = "capture_done",
            opId = active.opId,
            fields = mapOf(
                "durationMs" to duration,
                "wavBytes" to wavBytes,
                "backend" to active.backend.id,
            ),
        )
        if (duration == 0L) {
            DictationDiagnostics.counters.sessionsEmptySpeech.incrementAndGet()
            active.liveSession?.abort()
            finish(message = R.string.no_speech, reason = "zero_duration_capture", opId = active.opId)
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
        } else {
            DictationDiagnostics.record(
                category = "queue",
                event = "enqueue_failed",
                opId = active.opId,
                level = DiagnosticFlightRecorder.LEVEL_WARN,
                fields = mapOf("file" to active.audio.name, "wavBytes" to wavBytes),
            )
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
        if (!promote(recording = false, opId = active.opId)) {
            live.abort()
            leaveInQueueAfterFailure(
                active,
                active.backend.formatError(this, IllegalStateException("Foreground service unavailable")),
                reason = "promote_transcribing_failed",
            )
            return
        }
        publish(
            Snapshot(Phase.TRANSCRIBING, pendingCount = visiblePendingCount(), opId = active.opId),
            reason = "finalize_live_session",
            backendId = active.backend.id,
            activeDurationMs = active.durationMs,
        )
        worker.execute {
            val result = runCatching { live.finish() }
            main.post { handleActiveTranscriptionResult(active, active.backend, result) }
        }
    }

    private fun transcribeActiveFile(active: Session, backend: TranscriptionBackend) {
        backend.validateBeforeStart(this)?.let { errorRes ->
            leaveInQueueAfterFailure(active, getString(errorRes), reason = "backend_validation_failed")
            return
        }
        if (!promote(recording = false, opId = active.opId)) {
            leaveInQueueAfterFailure(
                active,
                backend.formatError(this, IllegalStateException("Foreground service unavailable")),
                reason = "promote_transcribing_failed",
            )
            return
        }
        publish(
            Snapshot(Phase.TRANSCRIBING, pendingCount = visiblePendingCount(), opId = active.opId),
            reason = "transcribe_active_file",
            backendId = backend.id,
            activeDurationMs = active.durationMs,
        )
        worker.execute {
            val result = runCatching { backend.transcribeWav(this, active.audio, active.durationMs, active.opId) }
            main.post { handleActiveTranscriptionResult(active, backend, result) }
        }
    }

    private fun handleActiveTranscriptionResult(
        active: Session,
        backend: TranscriptionBackend,
        result: Result<String>,
    ) {
        if (session !== active) {
            DictationDiagnostics.record(
                category = "state",
                event = "transcription_result_dropped_stale_session",
                opId = active.opId,
                level = DiagnosticFlightRecorder.LEVEL_WARN,
            )
            return
        }
        val text = result.getOrElse { error ->
            Log.w(TAG, "Transcription failed on ${backend.id}; keeping recording in persistent queue", error)
            DictationDiagnostics.counters.sessionsFailed.incrementAndGet()
            DictationDiagnostics.recordError(
                stage = "transcription_result",
                code = error.javaClass.simpleName,
                error = error,
                opId = active.opId,
                extraFields = mapOf(
                    "backend" to backend.id,
                    "durationMs" to active.durationMs,
                    "persisted" to active.persistedInQueue,
                ),
            )
            leaveInQueueAfterFailure(active, backend.formatError(this, error), reason = "transcription_error")
            return
        }
        autoRetryDelayMs = AUTO_RETRY_INITIAL_MS
        if (text.isEmpty()) {
            DictationDiagnostics.counters.sessionsEmptySpeech.incrementAndGet()
            DictationDiagnostics.record(
                category = "state",
                event = "empty_speech_result",
                opId = active.opId,
                level = if (active.durationMs >= 5_000L) {
                    DiagnosticFlightRecorder.LEVEL_WARN
                } else {
                    DiagnosticFlightRecorder.LEVEL_INFO
                },
                fields = mapOf(
                    "backend" to backend.id,
                    "durationMs" to active.durationMs,
                    "wavBytes" to active.audio.length(),
                ),
            )
            // Nothing to deliver — delete the recording immediately.
            store.delete(active.audio, reason = "empty_speech")
            finish(message = R.string.no_speech, reason = "empty_speech", opId = active.opId)
        } else {
            DictationDiagnostics.counters.sessionsCompleted.incrementAndGet()
            // Defer deletion until commitText() delivers the transcript so a crash
            // or deferred insertion cannot lose either the transcript or the WAV.
            deliverTranscript(text, listOf(active.audio), opId = active.opId, backendId = backend.id)
        }
    }

    private fun retryPending(all: Boolean, silent: Boolean) {
        cancelAutoRetry()
        val opId = DictationDiagnostics.nextOpId("retry")
        val excluded = protectedFileNames()
        val entries = if (all) store.listPending(excluded) else listOfNotNull(store.oldest(excluded))
        if (entries.isEmpty()) {
            publish(Snapshot(Phase.FINISHED, pendingCount = visiblePendingCount(), opId = opId), reason = "retry_empty")
            stopSelfIfIdle()
            return
        }
        val backend = TranscriptionRegistry.activeBackend(this)
        backend.validateBeforeStart(this)?.let { errorRes ->
            if (!silent) Toast.makeText(this, errorRes, Toast.LENGTH_SHORT).show()
            publish(
                Snapshot(Phase.FINISHED, pendingCount = visiblePendingCount(), opId = opId),
                reason = "retry_validation_failed",
                backendId = backend.id,
            )
            stopSelfIfIdle()
            return
        }
        if (!promote(recording = false, opId = opId)) {
            if (!silent) {
                Toast.makeText(
                    this,
                    backend.formatError(this, IllegalStateException("Foreground service unavailable")),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            publish(
                Snapshot(Phase.FINISHED, pendingCount = visiblePendingCount(), opId = opId),
                reason = "retry_promote_failed",
                backendId = backend.id,
            )
            stopSelfIfIdle()
            return
        }
        val active = Session(
            opId = opId,
            backend = backend,
            audio = entries.first().file,
            liveSession = null,
            persistedInQueue = true,
            inFlightFiles = entries.mapTo(mutableSetOf()) { it.file.name },
        )
        session = active
        DictationDiagnostics.record(
            category = "queue",
            event = "retry_start",
            opId = opId,
            fields = mapOf(
                "backend" to backend.id,
                "entries" to entries.size,
                "all" to all,
                "silent" to silent,
            ),
        )
        publish(
            Snapshot(Phase.TRANSCRIBING, pendingCount = visiblePendingCount(), opId = opId),
            reason = "retry_transcribing",
            backendId = backend.id,
        )
        worker.execute {
            val transcripts = mutableListOf<String>()
            val filesToDeleteOnDelivery = mutableListOf<File>()
            val emptySpeechFiles = mutableListOf<File>()
            var lastError: Throwable? = null
            var succeededAny = false
            for (entry in entries) {
                val res = runCatching { backend.transcribeWav(this, entry.file, entry.durationMs, opId) }
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
                    store.delete(emptyFile, reason = "retry_empty_speech")
                }
                val joined = transcripts.joinToString(" ").trim()
                if (succeededAny && joined.isNotEmpty()) {
                    autoRetryDelayMs = AUTO_RETRY_INITIAL_MS
                    deliverTranscript(joined, filesToDeleteOnDelivery, opId = opId, backendId = backend.id)
                } else if (succeededAny) {
                    autoRetryDelayMs = AUTO_RETRY_INITIAL_MS
                    session = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    if (!silent) Toast.makeText(this, R.string.no_speech, Toast.LENGTH_SHORT).show()
                    val remaining = visiblePendingCount()
                    publish(
                        Snapshot(Phase.FINISHED, pendingCount = remaining, opId = opId),
                        reason = "retry_empty_speech",
                        backendId = backend.id,
                    )
                    if (remaining > 0) scheduleAutoRetryIfVisible() else stopSelfIfIdle()
                } else {
                    session = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    val remaining = visiblePendingCount()
                    val error = lastError
                    Log.w(TAG, "Pending retry failed on ${backend.id} (remaining=$remaining)", error)
                    if (error != null) {
                        DictationDiagnostics.recordError(
                            stage = "retry_pending",
                            code = error.javaClass.simpleName,
                            error = error,
                            opId = opId,
                            extraFields = mapOf("backend" to backend.id, "remaining" to remaining, "silent" to silent),
                        )
                    }
                    if (!silent && error != null) {
                        Toast.makeText(this, backend.formatError(this, error), Toast.LENGTH_SHORT).show()
                    }
                    publish(
                        Snapshot(Phase.FINISHED, pendingCount = remaining, opId = opId),
                        reason = "retry_failed",
                        backendId = backend.id,
                    )
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

    private fun deliverTranscript(text: String, sourceFiles: List<File>, opId: String, backendId: String) {
        session = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        pendingDeliveryFiles = sourceFiles.toList()
        val remaining = visiblePendingCount()
        val wordCount = text.split(Regex("\\s+")).count { it.isNotEmpty() }
        DictationDiagnostics.record(
            category = "state",
            event = "deliver_transcript",
            opId = opId,
            fields = mapOf(
                "backend" to backendId,
                "transcriptLen" to text.length,
                "wordCount" to wordCount,
                "sourceFiles" to sourceFiles.size,
                "remainingPending" to remaining,
                "keyboardVisible" to keyboardVisible,
                "manualInsertRequired" to manualInsertRequired,
            ),
        )
        publish(
            Snapshot(Phase.RESULT, transcript = text, pendingCount = remaining, opId = opId),
            reason = "deliver_transcript",
            backendId = backendId,
        )
    }

    private fun leaveInQueueAfterFailure(active: Session, errorMessage: String, reason: String) {
        active.liveSession?.abort()
        if (!active.persistedInQueue) {
            store.enqueue(active.audio, protectedNames = protectedFileNames())
        }
        session = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        val pending = visiblePendingCount()
        DictationDiagnostics.record(
            category = "queue",
            event = "leave_in_queue",
            opId = active.opId,
            level = DiagnosticFlightRecorder.LEVEL_WARN,
            fields = mapOf(
                "reason" to reason,
                "pendingCount" to pending,
                "backend" to active.backend.id,
            ),
        )
        Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show()
        publish(
            Snapshot(Phase.FINISHED, pendingCount = pending, opId = active.opId),
            reason = reason,
            backendId = active.backend.id,
        )
        autoRetryDelayMs = AUTO_RETRY_INITIAL_MS
        scheduleAutoRetryIfVisible()
    }

    private fun finish(text: String? = null, message: Int? = null, reason: String = "finish", opId: String = "") {
        val effectiveOpId = opId.ifEmpty { session?.opId.orEmpty() }
        val backendId = session?.backend?.id.orEmpty()
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
                opId = effectiveOpId,
            ),
            reason = reason,
            backendId = backendId,
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
        DictationDiagnostics.record(
            category = "queue",
            event = "auto_retry_scheduled",
            fields = mapOf("delayMs" to autoRetryDelayMs, "pendingCount" to visiblePendingCount()),
        )
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

    private fun promote(recording: Boolean, opId: String): Boolean {
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
            DictationDiagnostics.recordError(
                stage = if (recording) "promote_microphone" else "promote_data_sync",
                code = error.javaClass.simpleName,
                error = error,
                opId = opId,
            )
            false
        }
    }

    override fun onDestroy() {
        cancelAutoRetry()
        val activeOp = session?.opId.orEmpty()
        DictationDiagnostics.record(
            category = "lifecycle",
            event = "recording_service_destroy",
            opId = activeOp,
            fields = mapOf(
                "hadSession" to (session != null),
                "pendingDeliveryCount" to pendingDeliveryFiles.size,
                "pendingStoreCount" to store.count(),
            ),
        )
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
        publish(Snapshot(Phase.FINISHED, pendingCount = store.count()), reason = "service_destroy")
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
                publish(snapshot.copy(pendingCount = count), reason = "refresh_pending_count")
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
            DictationDiagnostics.updateServiceState { it.copy(keyboardVisible = visible, pendingCount = pending) }
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
            DictationDiagnostics.init(context)
            if (snapshot.phase != Phase.READY && snapshot.phase != Phase.FINISHED) return
            manualInsertRequired = false
            val pending = PendingRecordingStore.forContext(context).count(excluding = protectedFileNames())
            publish(Snapshot(Phase.STARTING, pendingCount = pending), reason = "start_requested")
            try {
                context.startForegroundService(Intent(context, WhisperRecordingService::class.java).setAction(START))
            } catch (error: RuntimeException) {
                Log.w(TAG, "Android did not allow microphone service startup", error)
                DictationDiagnostics.recordError(
                    stage = "start_foreground_service",
                    code = error.javaClass.simpleName,
                    error = error,
                )
                publish(Snapshot(Phase.FINISHED, pendingCount = pending), reason = "start_fgs_rejected")
                Toast.makeText(context, R.string.microphone_error, Toast.LENGTH_SHORT).show()
            }
        }

        internal fun stopFromKeyboard() {
            manualInsertRequired = false
            current?.requestStop(source = "keyboard")
        }

        internal fun retryPending(context: Context, all: Boolean = false) {
            DictationDiagnostics.init(context)
            if (snapshot.phase != Phase.READY && snapshot.phase != Phase.FINISHED) return
            val count = PendingRecordingStore.forContext(context).count(excluding = protectedFileNames())
            if (count == 0) {
                refreshPendingCount(context)
                return
            }
            manualInsertRequired = false
            publish(Snapshot(Phase.TRANSCRIBING, pendingCount = count), reason = "retry_requested")
            val action = if (all) RETRY_ALL else RETRY_OLDEST
            try {
                context.startForegroundService(Intent(context, WhisperRecordingService::class.java).setAction(action))
            } catch (error: RuntimeException) {
                Log.w(TAG, "Android did not allow retry service startup", error)
                DictationDiagnostics.recordError(
                    stage = "start_retry_fgs",
                    code = error.javaClass.simpleName,
                    error = error,
                )
                publish(Snapshot(Phase.FINISHED, pendingCount = count), reason = "retry_fgs_rejected")
                Toast.makeText(context, R.string.network_error, Toast.LENGTH_SHORT).show()
            }
        }

        internal fun discardOldestPending(context: Context) {
            val store = PendingRecordingStore.forContext(context)
            store.oldest(excluding = protectedFileNames())?.let { store.delete(it.file, reason = "user_discard") }
            refreshPendingCount(context)
        }

        internal fun clearAllPending(context: Context): Int {
            val removed = PendingRecordingStore.forContext(context).clearAll(excluding = protectedFileNames())
            DictationDiagnostics.record(
                category = "queue",
                event = "user_clear_all",
                fields = mapOf("removed" to removed),
            )
            refreshPendingCount(context)
            return removed
        }

        internal fun deferInsertion() {
            manualInsertRequired = true
            DictationDiagnostics.updateServiceState { it.copy(manualInsertRequired = true) }
            DictationDiagnostics.record(
                category = "state",
                event = "defer_insertion",
                opId = snapshot.opId,
                fields = mapOf("phase" to snapshot.phase.name),
            )
        }

        internal fun transcriptInserted() {
            if (snapshot.phase != Phase.RESULT) return
            val opId = snapshot.opId
            // Auto-delete source WAVs only after commitText() has delivered the transcript.
            val delivered = pendingDeliveryFiles
            pendingDeliveryFiles = emptyList()
            val service = current
            for (file in delivered) {
                if (service != null) {
                    service.store.delete(file, reason = "transcript_delivered")
                } else {
                    val deleted = file.delete()
                    if (deleted) {
                        DictationDiagnostics.counters.queueDeleted.incrementAndGet()
                        DictationDiagnostics.record(
                            category = "queue",
                            event = "delete",
                            opId = opId,
                            fields = mapOf("file" to file.name, "reason" to "transcript_delivered"),
                        )
                    }
                }
            }
            val remaining = service?.visiblePendingCount() ?: snapshot.pendingCount
            publish(Snapshot(Phase.FINISHED, pendingCount = remaining, opId = opId), reason = "transcript_inserted")
            if (remaining > 0) {
                service?.scheduleAutoRetryIfVisible()
            } else {
                service?.stopSelfIfIdle()
            }
        }

        private fun publish(
            next: Snapshot,
            reason: String = "",
            backendId: String = "",
            activeDurationMs: Long = 0L,
        ) {
            val prev = snapshot
            snapshot = next
            DictationDiagnostics.updateServiceState { cur ->
                cur.copy(
                    phase = next.phase.name,
                    opId = next.opId,
                    backend = backendId.ifEmpty { cur.backend },
                    keyboardVisible = keyboardVisible,
                    manualInsertRequired = manualInsertRequired,
                    hasTranscript = !next.transcript.isNullOrEmpty(),
                    transcriptLen = next.transcript?.length ?: 0,
                    pendingCount = next.pendingCount,
                    pendingDeliveryCount = pendingDeliveryFiles.size,
                    autoRetryDelayMs = current?.autoRetryDelayMs ?: cur.autoRetryDelayMs,
                    activeDurationMs = if (activeDurationMs > 0L) activeDurationMs else cur.activeDurationMs,
                )
            }
            if (prev.phase != next.phase) {
                DictationDiagnostics.record(
                    category = "state",
                    event = "phase_transition",
                    opId = next.opId,
                    fields = mapOf(
                        "from" to prev.phase.name,
                        "to" to next.phase.name,
                        "reason" to reason,
                        "pendingCount" to next.pendingCount,
                        "transcriptLen" to (next.transcript?.length ?: 0),
                    ),
                )
                if (next.phase == Phase.RESULT || next.phase == Phase.FINISHED) {
                    DictationDiagnostics.persistSnapshot()
                }
            }
            listeners.toList().forEach { it(next) }
        }
    }
}
