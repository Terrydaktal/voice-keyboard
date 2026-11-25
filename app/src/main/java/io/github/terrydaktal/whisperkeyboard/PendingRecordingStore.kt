package io.github.terrydaktal.whisperkeyboard

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * Persistent FIFO queue of dictation WAV recordings stored in internal `filesDir`.
 *
 * Mirrors desktop `queue-worker.sh` durability without unbounded growth:
 * - Active captures write to `active-*.wav.tmp` and are promoted to
 *   `dictation-<epochMs>-<id>.wav` immediately upon Stop (before network upload)
 *   so an app crash during transcription cannot lose the recorded audio.
 * - Queued files are deleted only after their transcript has been delivered
 *   (or when empty speech is returned with nothing to insert).
 * - Enforces hard count ([MAX_PENDING_FILES]) and byte ([MAX_TOTAL_BYTES]) caps by
 *   evicting the oldest pending clips first.
 */
internal class PendingRecordingStore(private val dir: File) {
    data class Entry(val file: File, val durationMs: Long, val sizeBytes: Long)

    init {
        dir.mkdirs()
    }

    fun newActiveFile(): File {
        dir.mkdirs()
        return File(dir, "$ACTIVE_PREFIX${UUID.randomUUID()}$ACTIVE_SUFFIX")
    }

    fun cleanOrphanedActiveFiles(exclude: File? = null) {
        val excludedName = exclude?.name
        dir.listFiles { file ->
            file.isFile &&
                file.name.startsWith(ACTIVE_PREFIX) &&
                file.name.endsWith(ACTIVE_SUFFIX) &&
                file.name != excludedName
        }?.forEach { it.delete() }
    }

    /**
     * Promotes a finalized active WAV into the persistent FIFO queue and prunes oldest
     * entries if the store exceeds [MAX_PENDING_FILES] or [MAX_TOTAL_BYTES].
     */
    fun enqueue(
        activeFile: File,
        nowMs: Long = System.currentTimeMillis(),
        protectedNames: Set<String> = emptySet(),
    ): File? {
        if (!activeFile.isFile || activeFile.length() <= WAV_HEADER_BYTES) {
            activeFile.delete()
            return null
        }
        dir.mkdirs()
        val shortId = UUID.randomUUID().toString().substring(0, 8)
        val target = File(dir, "$PENDING_PREFIX%013d-%s$PENDING_SUFFIX".format(nowMs, shortId))
        val promoted = if (activeFile.renameTo(target)) {
            target
        } else {
            runCatching {
                activeFile.copyTo(target, overwrite = true)
                activeFile.delete()
                target
            }.getOrNull() ?: return null
        }
        pruneToLimits(protectedNames = protectedNames + promoted.name)
        return if (promoted.exists()) promoted else null
    }

    fun listPending(excluding: Set<String> = emptySet()): List<Entry> {
        val files = dir.listFiles { file ->
            file.isFile &&
                file.name.startsWith(PENDING_PREFIX) &&
                file.name.endsWith(PENDING_SUFFIX) &&
                file.name !in excluding
        }?.sortedBy { it.name } ?: return emptyList()

        val valid = mutableListOf<Entry>()
        for (file in files) {
            val size = file.length()
            if (size <= WAV_HEADER_BYTES) {
                file.delete()
                continue
            }
            valid.add(Entry(file, wavDurationMs(size), size))
        }
        return valid
    }

    fun oldest(excluding: Set<String> = emptySet()): Entry? = listPending(excluding).firstOrNull()

    fun count(excluding: Set<String> = emptySet()): Int = listPending(excluding).size

    fun totalBytes(excluding: Set<String> = emptySet()): Long = listPending(excluding).sumOf { it.sizeBytes }

    fun delete(file: File): Boolean = file.delete()

    fun clearAll(excluding: Set<String> = emptySet()): Int {
        var removed = 0
        for (entry in listPending(excluding)) {
            if (entry.file.delete()) removed++
        }
        return removed
    }

    internal fun pruneToLimits(
        maxFiles: Int = MAX_PENDING_FILES,
        maxBytes: Long = MAX_TOTAL_BYTES,
        protectedNames: Set<String> = emptySet(),
    ) {
        val entries = listPending().toMutableList()
        var total = entries.sumOf { it.sizeBytes }
        while (entries.size > maxFiles || (entries.size > 1 && total > maxBytes)) {
            val evictIndex = entries.indexOfFirst { it.file.name !in protectedNames }
            if (evictIndex < 0) break
            val oldest = entries.removeAt(evictIndex)
            total -= oldest.sizeBytes
            oldest.file.delete()
        }
    }

    companion object {
        private const val DIR_NAME = "pending_dictations"
        private const val ACTIVE_PREFIX = "active-"
        private const val ACTIVE_SUFFIX = ".wav.tmp"
        private const val PENDING_PREFIX = "dictation-"
        private const val PENDING_SUFFIX = ".wav"
        private const val WAV_HEADER_BYTES = 44L
        private const val BYTES_PER_SECOND = 16_000L * 2L
        internal const val MAX_PENDING_FILES = 20
        internal const val MAX_TOTAL_BYTES = 50L * 1024L * 1024L

        fun forContext(context: Context): PendingRecordingStore =
            PendingRecordingStore(File(context.filesDir, DIR_NAME))

        internal fun wavDurationMs(fileBytes: Long): Long =
            ((fileBytes - WAV_HEADER_BYTES).coerceAtLeast(0L) * 1_000L) / BYTES_PER_SECOND
    }
}
