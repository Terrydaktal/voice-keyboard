package io.github.terrydaktal.whisperkeyboard

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Bounded causal flight recorder, lifecycle counter registry, and live snapshot builder
 * for Voice Keyboard.
 *
 * Design properties:
 * - Zero synchronous disk I/O on UI or audio capture threads (asynchronous bounded queue).
 * - Survives aggressive Android logcat rotation and process death via rotating JSONL files
 *   under `filesDir/diagnostics/` (256 KiB active + 256 KiB previous = 512 KiB max).
 * - Privacy-safe by construction: never accepts raw transcripts or credentials, and redacts
 *   API-key / bearer-token patterns in error messages.
 * - Independent live inspection via `dumpsys` (`dump()`) on binder threads without waiting
 *   on the main looper, plus an in-app SetupActivity Diagnostics panel.
 */
internal class DiagnosticFlightRecorder(
    private val logDir: File?,
    private val ringCapacity: Int = DEFAULT_RING_CAPACITY,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val diskQueueCapacity: Int = DEFAULT_DISK_QUEUE_CAPACITY,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val monoClockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val logcatSink: ((level: String, tag: String, line: String) -> Unit)? = null,
) {
    data class Event(
        val seq: Long,
        val tsWallMs: Long,
        val tsMonoMs: Long,
        val thread: String,
        val opId: String,
        val category: String,
        val event: String,
        val level: String,
        val fields: Map<String, String>,
    ) {
        fun toJsonLine(): String {
            val sb = StringBuilder(256)
            sb.append('{')
            sb.append("\"schema\":").append(SCHEMA_VERSION)
            sb.append(",\"seq\":").append(seq)
            sb.append(",\"tsWallMs\":").append(tsWallMs)
            sb.append(",\"tsMonoMs\":").append(tsMonoMs)
            sb.append(",\"level\":\"").append(jsonEscape(level)).append('"')
            sb.append(",\"thread\":\"").append(jsonEscape(thread)).append('"')
            if (opId.isNotEmpty()) {
                sb.append(",\"opId\":\"").append(jsonEscape(opId)).append('"')
            }
            sb.append(",\"cat\":\"").append(jsonEscape(category)).append('"')
            sb.append(",\"event\":\"").append(jsonEscape(event)).append('"')
            sb.append(",\"fields\":{")
            var first = true
            for ((k, v) in fields) {
                if (!first) sb.append(',')
                first = false
                sb.append('"').append(jsonEscape(k)).append("\":\"").append(jsonEscape(v)).append('"')
            }
            sb.append("}}")
            return sb.toString()
        }

        fun toCompactLogLine(): String {
            val sb = StringBuilder(160)
            sb.append('#').append(seq)
            if (opId.isNotEmpty()) sb.append(' ').append(opId)
            sb.append(' ').append(category).append('.').append(event)
            for ((k, v) in fields) {
                sb.append(' ').append(k).append('=').append(v)
            }
            return sb.toString()
        }
    }

    data class ErrorRecord(
        val seq: Long,
        val tsWallMs: Long,
        val opId: String,
        val stage: String,
        val code: String,
        val errorClass: String,
        val message: String,
        val causeChain: String,
    )

    data class RecorderStats(
        val ringSize: Int,
        val ringCapacity: Int,
        val totalEvents: Long,
        val overwrittenCount: Long,
        val droppedDiskCount: Long,
        val diskWriteErrors: Long,
        val oldestSeq: Long,
        val newestSeq: Long,
        val logFileBytes: Long,
    )

    class Counters {
        val sessionsStarted = AtomicLong()
        val sessionsCompleted = AtomicLong()
        val sessionsFailed = AtomicLong()
        val sessionsEmptySpeech = AtomicLong()
        val sessionsAborted = AtomicLong()
        val s3CallsStarted = AtomicLong()
        val s3CallsSucceeded = AtomicLong()
        val s3CallsFailed = AtomicLong()
        val s3BufferedRetries = AtomicLong()
        val s3EmptyResponses = AtomicLong()
        val whisperCallsStarted = AtomicLong()
        val whisperCallsSucceeded = AtomicLong()
        val whisperCallsFailed = AtomicLong()
        val commitsAttempted = AtomicLong()
        val commitsSucceeded = AtomicLong()
        val commitsFailed = AtomicLong()
        val commitsBlocked = AtomicLong()
        val queueEnqueued = AtomicLong()
        val queueDeleted = AtomicLong()
        val queueEvicted = AtomicLong()

        fun asMap(): LinkedHashMap<String, Long> = linkedMapOf(
            "sessionsStarted" to sessionsStarted.get(),
            "sessionsCompleted" to sessionsCompleted.get(),
            "sessionsFailed" to sessionsFailed.get(),
            "sessionsEmptySpeech" to sessionsEmptySpeech.get(),
            "sessionsAborted" to sessionsAborted.get(),
            "s3CallsStarted" to s3CallsStarted.get(),
            "s3CallsSucceeded" to s3CallsSucceeded.get(),
            "s3CallsFailed" to s3CallsFailed.get(),
            "s3BufferedRetries" to s3BufferedRetries.get(),
            "s3EmptyResponses" to s3EmptyResponses.get(),
            "whisperCallsStarted" to whisperCallsStarted.get(),
            "whisperCallsSucceeded" to whisperCallsSucceeded.get(),
            "whisperCallsFailed" to whisperCallsFailed.get(),
            "commitsAttempted" to commitsAttempted.get(),
            "commitsSucceeded" to commitsSucceeded.get(),
            "commitsFailed" to commitsFailed.get(),
            "commitsBlocked" to commitsBlocked.get(),
            "queueEnqueued" to queueEnqueued.get(),
            "queueDeleted" to queueDeleted.get(),
            "queueEvicted" to queueEvicted.get(),
        )
    }

    private val seqGen = AtomicLong(0L)
    private val opSeqGen = AtomicLong(0L)
    @Volatile var enabled: Boolean = true
    @Volatile private var disabledCategories: Set<String> = emptySet()
    private val ringLock = Any()
    private val ring = ArrayDeque<Event>(ringCapacity.coerceAtLeast(1))
    private var overwrittenCount = 0L
    private val droppedDiskCount = AtomicLong(0L)
    private val diskWriteErrors = AtomicLong(0L)
    private val diskWriteLock = Any()
    private val lastErrorRef = AtomicReference<ErrorRecord?>(null)
    private val firstErrorByOpLock = Any()
    private val firstErrorByOp = LinkedHashMap<String, ErrorRecord>(32, 0.75f, false)

    val counters = Counters()

    private val diskQueue = ArrayBlockingQueue<String>(diskQueueCapacity.coerceAtLeast(8))
    private val diskExecutor: ThreadPoolExecutor? = if (logDir != null) {
        ThreadPoolExecutor(
            1,
            1,
            30L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(diskQueueCapacity.coerceAtLeast(8)),
            { r ->
                Thread(r, "voice-kb-diag-writer").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            },
        ).apply {
            allowCoreThreadTimeOut(true)
            setRejectedExecutionHandler { _, _ -> droppedDiskCount.incrementAndGet() }
        }
    } else {
        null
    }

    fun nextOpId(prefix: String = "op"): String {
        val id = opSeqGen.incrementAndGet()
        return "$prefix-%04d".format(Locale.US, id)
    }

    fun setCategoryEnabled(category: String, isEnabled: Boolean) {
        disabledCategories = if (isEnabled) {
            disabledCategories - category
        } else {
            disabledCategories + category
        }
    }

    fun isCategoryEnabled(category: String): Boolean = enabled && category !in disabledCategories

    fun record(
        category: String,
        event: String,
        opId: String = "",
        level: String = LEVEL_INFO,
        fields: Map<String, Any?> = emptyMap(),
    ): Event? {
        if (!enabled || category in disabledCategories) return null
        val sanitized = sanitizeFields(fields)
        val rec = Event(
            seq = seqGen.incrementAndGet(),
            tsWallMs = wallClockMs(),
            tsMonoMs = monoClockMs(),
            thread = Thread.currentThread().name.take(32),
            opId = opId.take(32),
            category = category.take(24),
            event = event.take(48),
            level = level,
            fields = sanitized,
        )
        synchronized(ringLock) {
            if (ring.size >= ringCapacity) {
                ring.removeFirst()
                overwrittenCount++
            }
            ring.addLast(rec)
        }
        val jsonLine = rec.toJsonLine()
        enqueueDiskLine(jsonLine)
        logcatSink?.invoke(level, LOG_TAG, rec.toCompactLogLine())
        return rec
    }

    fun recordError(
        stage: String,
        code: String,
        error: Throwable,
        opId: String = "",
        extraFields: Map<String, Any?> = emptyMap(),
    ): ErrorRecord {
        val errClass = error.javaClass.simpleName.ifEmpty { error.javaClass.name }
        val message = redactString(error.message.orEmpty()).take(MAX_VALUE_LEN)
        val causeChain = buildCauseChain(error)
        val merged = LinkedHashMap<String, Any?>(extraFields.size + 5)
        merged["stage"] = stage
        merged["code"] = code
        merged["errClass"] = errClass
        if (message.isNotEmpty()) merged["errMsg"] = message
        if (causeChain.isNotEmpty()) merged["causeChain"] = causeChain
        merged.putAll(extraFields)

        val ev = record(
            category = "error",
            event = stage,
            opId = opId,
            level = LEVEL_ERROR,
            fields = merged,
        )
        val record = ErrorRecord(
            seq = ev?.seq ?: 0L,
            tsWallMs = ev?.tsWallMs ?: wallClockMs(),
            opId = opId,
            stage = stage,
            code = code,
            errorClass = errClass,
            message = message,
            causeChain = causeChain,
        )
        lastErrorRef.set(record)
        if (opId.isNotEmpty()) {
            synchronized(firstErrorByOpLock) {
                if (!firstErrorByOp.containsKey(opId)) {
                    if (firstErrorByOp.size >= MAX_TRACKED_OPS) {
                        val oldestKey = firstErrorByOp.keys.firstOrNull()
                        if (oldestKey != null) firstErrorByOp.remove(oldestKey)
                    }
                    firstErrorByOp[opId] = record
                }
            }
        }
        return record
    }

    fun firstErrorFor(opId: String): ErrorRecord? = synchronized(firstErrorByOpLock) {
        firstErrorByOp[opId]
    }

    fun lastError(): ErrorRecord? = lastErrorRef.get()

    fun recentEvents(limit: Int = 100): List<Event> = synchronized(ringLock) {
        if (limit <= 0) return emptyList()
        val take = minOf(limit, ring.size)
        val start = ring.size - take
        val out = ArrayList<Event>(take)
        var i = 0
        for (ev in ring) {
            if (i++ >= start) out.add(ev)
        }
        out
    }

    fun stats(): RecorderStats {
        val size: Int
        val overwritten: Long
        val oldest: Long
        val newest: Long
        synchronized(ringLock) {
            size = ring.size
            overwritten = overwrittenCount
            oldest = ring.firstOrNull()?.seq ?: 0L
            newest = ring.lastOrNull()?.seq ?: 0L
        }
        return RecorderStats(
            ringSize = size,
            ringCapacity = ringCapacity,
            totalEvents = seqGen.get(),
            overwrittenCount = overwritten,
            droppedDiskCount = droppedDiskCount.get(),
            diskWriteErrors = diskWriteErrors.get(),
            oldestSeq = oldest,
            newestSeq = newest,
            logFileBytes = totalDiskBytes(),
        )
    }

    fun readPersistedTail(maxLines: Int = 120): List<String> {
        val dir = logDir ?: return emptyList()
        flush(timeoutMs = 500)
        val files = listOf(File(dir, PREV_LOG_FILE), File(dir, ACTIVE_LOG_FILE))
        val lines = ArrayDeque<String>(maxLines.coerceAtLeast(1))
        synchronized(diskWriteLock) {
            for (file in files) {
                if (!file.isFile) continue
                runCatching {
                    file.bufferedReader(Charsets.UTF_8).useLines { seq ->
                        for (line in seq) {
                            if (line.isBlank()) continue
                            if (lines.size >= maxLines) lines.removeFirst()
                            lines.addLast(line)
                        }
                    }
                }
            }
        }
        return lines.toList()
    }

    fun clearLogs() {
        synchronized(ringLock) {
            ring.clear()
        }
        lastErrorRef.set(null)
        synchronized(firstErrorByOpLock) {
            firstErrorByOp.clear()
        }
        flush(timeoutMs = 500)
        val dir = logDir ?: return
        synchronized(diskWriteLock) {
            runCatching { File(dir, ACTIVE_LOG_FILE).delete() }
            runCatching { File(dir, PREV_LOG_FILE).delete() }
        }
    }

    fun flush(timeoutMs: Long = 1_000L): Boolean {
        val exec = diskExecutor ?: return true
        val latch = CountDownLatch(1)
        return try {
            exec.execute {
                drainPendingDiskLines()
                latch.countDown()
            }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
            false
        }
    }

    fun totalDiskBytes(): Long {
        val dir = logDir ?: return 0L
        var total = 0L
        val active = File(dir, ACTIVE_LOG_FILE)
        val prev = File(dir, PREV_LOG_FILE)
        if (active.isFile) total += active.length()
        if (prev.isFile) total += prev.length()
        return total
    }

    private fun enqueueDiskLine(jsonLine: String) {
        val exec = diskExecutor ?: return
        if (!diskQueue.offer(jsonLine)) {
            diskQueue.poll()
            droppedDiskCount.incrementAndGet()
            if (!diskQueue.offer(jsonLine)) {
                droppedDiskCount.incrementAndGet()
                return
            }
        }
        exec.execute { drainPendingDiskLines() }
    }

    private fun drainPendingDiskLines() {
        val dir = logDir ?: return
        val batch = ArrayList<String>(32)
        diskQueue.drainTo(batch, 64)
        if (batch.isEmpty()) return
        synchronized(diskWriteLock) {
            try {
                if (!dir.exists()) dir.mkdirs()
                val active = File(dir, ACTIVE_LOG_FILE)
                var currentBytes = if (active.isFile) active.length() else 0L
                var writer = FileOutputStream(active, true).bufferedWriter(Charsets.UTF_8)
                try {
                    for (line in batch) {
                        if (currentBytes >= maxFileBytes) {
                            writer.flush()
                            writer.close()
                            val prev = File(dir, PREV_LOG_FILE)
                            if (prev.exists()) prev.delete()
                            active.renameTo(prev)
                            currentBytes = 0L
                            writer = FileOutputStream(active, true).bufferedWriter(Charsets.UTF_8)
                        }
                        writer.write(line)
                        writer.newLine()
                        currentBytes += line.length + 1L
                    }
                } finally {
                    runCatching { writer.flush() }
                    runCatching { writer.close() }
                }
            } catch (_: Throwable) {
                diskWriteErrors.incrementAndGet()
            }
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val LEVEL_INFO = "I"
        const val LEVEL_WARN = "W"
        const val LEVEL_ERROR = "E"
        const val LOG_TAG = "VoiceKeyboardDiag"
        const val DEFAULT_RING_CAPACITY = 512
        const val DEFAULT_MAX_FILE_BYTES = 256L * 1024L
        const val DEFAULT_DISK_QUEUE_CAPACITY = 512
        const val ACTIVE_LOG_FILE = "events.jsonl"
        const val PREV_LOG_FILE = "events.1.jsonl"
        private const val MAX_FIELDS = 24
        private const val MAX_KEY_LEN = 40
        private const val MAX_VALUE_LEN = 240
        private const val MAX_TRACKED_OPS = 32

        private val FORBIDDEN_KEYS = setOf(
            "transcript",
            "text",
            "hypothesis",
            "partial",
            "final",
            "authorization",
            "apikey",
            "api_key",
            "x-goog-api-key",
            "password",
            "secret",
            "token",
            "cookie",
        )

        private val GOOGLE_API_KEY_REGEX = Regex("AIza[0-9A-Za-z_-]{10,}")
        private val SECRET_KV_REGEX = Regex(
            "(?i)(x-goog-api-key|authorization|bearer|api[_-]?key|token|password|secret)\\s*([:=]|\\s+)\\s*[^\\s,;\"'&]+",
        )
        private val URL_CREDS_REGEX = Regex("://[^/@\\s]+@")

        internal fun sanitizeFields(raw: Map<String, Any?>): Map<String, String> {
            if (raw.isEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>(minOf(raw.size, MAX_FIELDS))
            for ((k, v) in raw) {
                if (out.size >= MAX_FIELDS) break
                val key = k.trim().take(MAX_KEY_LEN)
                if (key.isEmpty()) continue
                val lower = key.lowercase(Locale.US)
                val value = if (lower in FORBIDDEN_KEYS) {
                    "<redacted>"
                } else {
                    redactString(v?.toString() ?: "null").take(MAX_VALUE_LEN)
                }
                out[key] = value
            }
            return out
        }

        internal fun redactString(input: String): String {
            if (input.isEmpty()) return input
            var out = GOOGLE_API_KEY_REGEX.replace(input, "<redacted_api_key>")
            out = SECRET_KV_REGEX.replace(out) { m -> "${m.groupValues[1]}=<redacted>" }
            out = URL_CREDS_REGEX.replace(out, "://<redacted>@")
            return out.replace('\n', ' ').replace('\r', ' ')
        }

        internal fun sanitizeUrlForLog(rawUrl: String): String {
            if (rawUrl.isBlank()) return ""
            val redacted = redactString(rawUrl.trim())
            val qIdx = redacted.indexOf('?')
            return if (qIdx >= 0) redacted.substring(0, qIdx) else redacted
        }

        internal fun buildCauseChain(error: Throwable, maxDepth: Int = 4): String {
            val parts = ArrayList<String>(maxDepth)
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth < maxDepth) {
                val cls = current.javaClass.simpleName.ifEmpty { current.javaClass.name }
                val msg = redactString(current.message.orEmpty()).take(96)
                parts.add(if (msg.isNotEmpty()) "$cls: $msg" else cls)
                current = current.cause
                depth++
            }
            return parts.joinToString(" <- ")
        }

        internal fun jsonEscape(value: String): String {
            val sb = StringBuilder(value.length + 8)
            for (ch in value) {
                when (ch) {
                    '\\' -> sb.append("\\\\")
                    '"' -> sb.append("\\\"")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (ch.code < 0x20) {
                        sb.append("\\u%04x".format(Locale.US, ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
            return sb.toString()
        }
    }
}

/**
 * Process-wide diagnostic hub used by [WhisperRecordingService], [WhisperKeyboardService],
 * [RamblerLiveSession], [RamblerS3Client], [OpusOggEncoder], [WhisperEndpoint], and [SetupActivity].
 */
internal object DictationDiagnostics {
    private const val DIR_NAME = "diagnostics"

    @Volatile private var initialized = false
    @Volatile private var buildVersionName = "unknown"
    @Volatile private var buildVersionCode = -1L
    @Volatile private var buildPackageName = "io.github.terrydaktal.whisperkeyboard"
    @Volatile private var recorder: DiagnosticFlightRecorder = DiagnosticFlightRecorder(
        logDir = null,
        logcatSink = ::writeLogcat,
    )

    data class ImeStateSnapshot(
        val viewActive: Boolean = false,
        val isInputViewShown: Boolean = false,
        val passwordField: Boolean = false,
        val hasInputConnection: Boolean = false,
        val hasSubmittedConnection: Boolean = false,
        val editorPackage: String = "",
        val editorInputType: Int = 0,
        val lastInsertDecision: String = "none",
        val lastInsertAtWallMs: Long = 0L,
    )

    data class ServiceStateSnapshot(
        val phase: String = "READY",
        val opId: String = "",
        val backend: String = "",
        val keyboardVisible: Boolean = false,
        val manualInsertRequired: Boolean = false,
        val hasTranscript: Boolean = false,
        val transcriptLen: Int = 0,
        val pendingCount: Int = 0,
        val pendingDeliveryCount: Int = 0,
        val autoRetryDelayMs: Long = 0L,
        val activeDurationMs: Long = 0L,
        val activeSegmentSummary: String = "",
    )

    private val imeStateRef = AtomicReference(ImeStateSnapshot())
    private val serviceStateRef = AtomicReference(ServiceStateSnapshot())
    private val activeSegmentRef = AtomicReference("")
    private val initCount = AtomicInteger(0)

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appCtx = context.applicationContext ?: context
            buildPackageName = appCtx.packageName
            runCatching {
                val pi = appCtx.packageManager.getPackageInfo(appCtx.packageName, 0)
                buildVersionName = pi.versionName ?: "unknown"
                buildVersionCode = pi.longVersionCode
            }
            val dir = File(appCtx.filesDir, DIR_NAME)
            val existing = recorder.recentEvents(DiagnosticFlightRecorder.DEFAULT_RING_CAPACITY)
            val newRecorder = DiagnosticFlightRecorder(
                logDir = dir,
                logcatSink = ::writeLogcat,
            )
            // Copy any early pre-init events into the disk-backed recorder.
            for (ev in existing) {
                newRecorder.record(
                    category = ev.category,
                    event = ev.event,
                    opId = ev.opId,
                    level = ev.level,
                    fields = ev.fields,
                )
            }
            recorder = newRecorder
            initialized = true
            initCount.incrementAndGet()
            newRecorder.record(
                category = "lifecycle",
                event = "process_init",
                fields = mapOf(
                    "versionName" to buildVersionName,
                    "versionCode" to buildVersionCode,
                    "pid" to safePid(),
                    "android" to (Build.VERSION.RELEASE ?: "unknown"),
                    "model" to (Build.MODEL ?: "unknown"),
                    "existingLogBytes" to newRecorder.totalDiskBytes(),
                ),
            )
        }
    }

    val counters: DiagnosticFlightRecorder.Counters
        get() = recorder.counters

    fun nextOpId(prefix: String = "op"): String = recorder.nextOpId(prefix)

    fun setEnabled(enabled: Boolean) {
        recorder.enabled = enabled
    }

    fun setCategoryEnabled(category: String, enabled: Boolean) {
        recorder.setCategoryEnabled(category, enabled)
    }

    fun record(
        category: String,
        event: String,
        opId: String = "",
        level: String = DiagnosticFlightRecorder.LEVEL_INFO,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        recorder.record(category = category, event = event, opId = opId, level = level, fields = fields)
    }

    fun recordError(
        stage: String,
        code: String,
        error: Throwable,
        opId: String = "",
        extraFields: Map<String, Any?> = emptyMap(),
    ) {
        recorder.recordError(
            stage = stage,
            code = code,
            error = error,
            opId = opId,
            extraFields = extraFields,
        )
    }

    fun updateImeState(transform: (ImeStateSnapshot) -> ImeStateSnapshot) {
        imeStateRef.updateAndGet(transform)
    }

    fun updateServiceState(transform: (ServiceStateSnapshot) -> ServiceStateSnapshot) {
        serviceStateRef.updateAndGet(transform)
    }

    fun updateActiveSegmentSummary(summary: String) {
        activeSegmentRef.set(summary.take(180))
    }

    fun stats(): DiagnosticFlightRecorder.RecorderStats = recorder.stats()

    fun lastError(): DiagnosticFlightRecorder.ErrorRecord? = recorder.lastError()

    fun clearLogs(context: Context) {
        init(context)
        recorder.clearLogs()
        record("lifecycle", "logs_cleared")
    }

    fun buildSnapshotJson(context: Context? = null): String {
        if (context != null) init(context)
        val rec = recorder
        val st = rec.stats()
        val svc = serviceStateRef.get().copy(activeSegmentSummary = activeSegmentRef.get())
        val ime = imeStateRef.get()
        val lastErr = rec.lastError()
        val pendingSummary = if (context != null) {
            runCatching { WhisperRecordingService.pendingSummary(context) }.getOrNull()
        } else {
            null
        }
        val activeBackend = if (context != null) {
            runCatching { DictationSettings.backend(context).name }.getOrDefault(svc.backend)
        } else {
            svc.backend
        }
        val locale = if (context != null) {
            runCatching { DictationSettings.locale(context) }.getOrDefault("")
        } else {
            ""
        }
        val whisperUrl = if (context != null) {
            runCatching {
                DiagnosticFlightRecorder.sanitizeUrlForLog(WhisperEndpoint.savedAddress(context))
            }.getOrDefault("")
        } else {
            ""
        }

        val sb = StringBuilder(1024)
        sb.append('{')
        sb.append("\"schema\":").append(DiagnosticFlightRecorder.SCHEMA_VERSION)
        sb.append(",\"consistency\":\"best-effort\"")
        sb.append(",\"capturedAtWallMs\":").append(System.currentTimeMillis())
        sb.append(",\"capturedAtMonoMs\":").append(System.nanoTime() / 1_000_000L)
        sb.append(",\"build\":{")
        sb.append("\"package\":\"").append(esc(buildPackageName)).append('"')
        sb.append(",\"versionName\":\"").append(esc(buildVersionName)).append('"')
        sb.append(",\"versionCode\":").append(buildVersionCode)
        sb.append(",\"pid\":").append(safePid())
        sb.append(",\"android\":\"").append(esc(Build.VERSION.RELEASE ?: "unknown")).append('"')
        sb.append(",\"model\":\"").append(esc(Build.MODEL ?: "unknown")).append('"')
        sb.append('}')

        sb.append(",\"config\":{")
        sb.append("\"activeBackend\":\"").append(esc(activeBackend)).append('"')
        sb.append(",\"locale\":\"").append(esc(locale)).append('"')
        sb.append(",\"whisperConfigured\":").append(whisperUrl.isNotEmpty())
        sb.append(",\"whisperUrl\":\"").append(esc(whisperUrl)).append('"')
        sb.append('}')

        sb.append(",\"recorder\":{")
        sb.append("\"ringSize\":").append(st.ringSize)
        sb.append(",\"ringCapacity\":").append(st.ringCapacity)
        sb.append(",\"totalEvents\":").append(st.totalEvents)
        sb.append(",\"overwrittenCount\":").append(st.overwrittenCount)
        sb.append(",\"droppedDiskCount\":").append(st.droppedDiskCount)
        sb.append(",\"diskWriteErrors\":").append(st.diskWriteErrors)
        sb.append(",\"oldestSeq\":").append(st.oldestSeq)
        sb.append(",\"newestSeq\":").append(st.newestSeq)
        sb.append(",\"logFileBytes\":").append(st.logFileBytes)
        sb.append('}')

        sb.append(",\"service\":{")
        sb.append("\"phase\":\"").append(esc(svc.phase)).append('"')
        sb.append(",\"opId\":\"").append(esc(svc.opId)).append('"')
        sb.append(",\"backend\":\"").append(esc(svc.backend)).append('"')
        sb.append(",\"keyboardVisible\":").append(svc.keyboardVisible)
        sb.append(",\"manualInsertRequired\":").append(svc.manualInsertRequired)
        sb.append(",\"hasTranscript\":").append(svc.hasTranscript)
        sb.append(",\"transcriptLen\":").append(svc.transcriptLen)
        sb.append(",\"pendingCount\":").append(pendingSummary?.first ?: svc.pendingCount)
        sb.append(",\"pendingBytes\":").append(pendingSummary?.second ?: -1L)
        sb.append(",\"pendingDeliveryCount\":").append(svc.pendingDeliveryCount)
        sb.append(",\"autoRetryDelayMs\":").append(svc.autoRetryDelayMs)
        sb.append(",\"activeDurationMs\":").append(svc.activeDurationMs)
        sb.append(",\"activeSegment\":\"").append(esc(svc.activeSegmentSummary)).append('"')
        sb.append('}')

        sb.append(",\"ime\":{")
        sb.append("\"viewActive\":").append(ime.viewActive)
        sb.append(",\"isInputViewShown\":").append(ime.isInputViewShown)
        sb.append(",\"passwordField\":").append(ime.passwordField)
        sb.append(",\"hasInputConnection\":").append(ime.hasInputConnection)
        sb.append(",\"hasSubmittedConnection\":").append(ime.hasSubmittedConnection)
        sb.append(",\"editorPackage\":\"").append(esc(ime.editorPackage)).append('"')
        sb.append(",\"editorInputType\":").append(ime.editorInputType)
        sb.append(",\"lastInsertDecision\":\"").append(esc(ime.lastInsertDecision)).append('"')
        sb.append(",\"lastInsertAtWallMs\":").append(ime.lastInsertAtWallMs)
        sb.append('}')

        sb.append(",\"counters\":{")
        var first = true
        for ((k, v) in rec.counters.asMap()) {
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(esc(k)).append("\":").append(v)
        }
        sb.append('}')

        if (lastErr != null) {
            sb.append(",\"lastError\":{")
            sb.append("\"seq\":").append(lastErr.seq)
            sb.append(",\"tsWallMs\":").append(lastErr.tsWallMs)
            sb.append(",\"opId\":\"").append(esc(lastErr.opId)).append('"')
            sb.append(",\"stage\":\"").append(esc(lastErr.stage)).append('"')
            sb.append(",\"code\":\"").append(esc(lastErr.code)).append('"')
            sb.append(",\"errClass\":\"").append(esc(lastErr.errorClass)).append('"')
            sb.append(",\"message\":\"").append(esc(lastErr.message)).append('"')
            sb.append(",\"causeChain\":\"").append(esc(lastErr.causeChain)).append('"')
            sb.append('}')
        } else {
            sb.append(",\"lastError\":null")
        }
        sb.append('}')
        return sb.toString()
    }

    fun formatHumanReport(context: Context? = null, maxEvents: Int = 120): String {
        if (context != null) init(context)
        val sb = StringBuilder(4096)
        sb.append("=== Voice Keyboard Diagnostics ===\n")
        sb.append("Snapshot: ").append(buildSnapshotJson(context)).append("\n\n")
        val persisted = recorder.readPersistedTail(maxEvents)
        if (persisted.isNotEmpty()) {
            sb.append("=== Recent Persisted Events (last ").append(persisted.size).append(") ===\n")
            for (line in persisted) {
                sb.append(line).append('\n')
            }
        } else {
            val mem = recorder.recentEvents(maxEvents)
            sb.append("=== Recent Memory Events (last ").append(mem.size).append(") ===\n")
            for (ev in mem) {
                sb.append(formatTimestamp(ev.tsWallMs)).append(' ')
                    .append(ev.toCompactLogLine()).append('\n')
            }
        }
        return sb.toString()
    }

    fun formatCompactSummary(context: Context): String {
        init(context)
        val st = recorder.stats()
        val c = recorder.counters
        val err = recorder.lastError()
        val kb = (st.logFileBytes + 1023L) / 1024L
        val errText = if (err != null) {
            "${formatTimestamp(err.tsWallMs)} [${err.opId}] ${err.stage}/${err.code}: ${err.message.ifEmpty { err.errorClass }}"
        } else {
            "none"
        }
        return buildString {
            append("Build: v").append(buildVersionName).append(" (").append(buildVersionCode).append(")\n")
            append("Sessions: started=").append(c.sessionsStarted.get())
                .append(" ok=").append(c.sessionsCompleted.get())
                .append(" empty=").append(c.sessionsEmptySpeech.get())
                .append(" fail=").append(c.sessionsFailed.get()).append('\n')
            append("S3 calls: started=").append(c.s3CallsStarted.get())
                .append(" ok=").append(c.s3CallsSucceeded.get())
                .append(" fail=").append(c.s3CallsFailed.get())
                .append(" retry=").append(c.s3BufferedRetries.get())
                .append(" empty=").append(c.s3EmptyResponses.get()).append('\n')
            append("Commits: ok=").append(c.commitsSucceeded.get())
                .append(" blocked=").append(c.commitsBlocked.get())
                .append(" fail=").append(c.commitsFailed.get()).append('\n')
            append("Log: ").append(st.ringSize).append(" in memory, ").append(kb)
                .append(" KB on disk (drops=").append(st.droppedDiskCount)
                .append(", overwrites=").append(st.overwrittenCount).append(")\n")
            append("Last error: ").append(errText)
        }
    }

    /**
     * Independent binder-thread dump for `adb shell dumpsys activity service` and
     * `adb shell dumpsys input_method`. Never blocks on the main looper.
     */
    fun dumpToWriter(writer: PrintWriter, context: Context?, source: String, maxEvents: Int = 80) {
        writer.println("=== VoiceKeyboard Diagnostics ($source) ===")
        writer.println(buildSnapshotJson(context))
        val events = recorder.recentEvents(maxEvents)
        writer.println("--- Recent Ring Events (${events.size}) ---")
        for (ev in events) {
            writer.print(formatTimestamp(ev.tsWallMs))
            writer.print(' ')
            writer.println(ev.toCompactLogLine())
        }
    }

    private fun writeLogcat(level: String, tag: String, line: String) {
        runCatching {
            when (level) {
                DiagnosticFlightRecorder.LEVEL_ERROR -> Log.e(tag, line)
                DiagnosticFlightRecorder.LEVEL_WARN -> Log.w(tag, line)
                else -> Log.i(tag, line)
            }
        }
    }

    private fun safePid(): Int = runCatching { Process.myPid() }.getOrDefault(-1)

    private fun formatTimestamp(wallMs: Long): String {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
        return fmt.format(Date(wallMs))
    }

    private fun esc(value: String): String = DiagnosticFlightRecorder.jsonEscape(value)
}
