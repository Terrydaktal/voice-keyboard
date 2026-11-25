package io.github.terrydaktal.whisperkeyboard

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingRecordingStoreTest {
    @Test
    fun enqueuesFifoComputesDurationAndDeletesOnDelivery() {
        val dir = Files.createTempDirectory("pending-store-test").toFile()
        try {
            val store = PendingRecordingStore(dir)

            // 1 second of 16 kHz mono 16-bit PCM = 32,000 bytes + 44 byte WAV header
            val first = store.newActiveFile().apply { writeBytes(ByteArray(44 + 32_000)) }
            val second = store.newActiveFile().apply { writeBytes(ByteArray(44 + 64_000)) }

            val promoted1 = store.enqueue(first, nowMs = 1_000L)
            val promoted2 = store.enqueue(second, nowMs = 2_000L)
            assertNotNull(promoted1)
            assertNotNull(promoted2)
            assertFalse(first.exists())
            assertFalse(second.exists())

            val pending = store.listPending()
            assertEquals(2, pending.size)
            assertEquals(1_000L, pending[0].durationMs)
            assertEquals(2_000L, pending[1].durationMs)
            assertEquals(promoted1, store.oldest()?.file)

            // Excluding in-flight / pending-delivery files hides them from count and oldest()
            val excluded = setOf(promoted1!!.name)
            assertEquals(1, store.count(excluding = excluded))
            assertEquals(promoted2, store.oldest(excluding = excluded)?.file)

            // Auto-delete after delivery
            assertTrue(store.delete(pending[0].file))
            assertEquals(1, store.count())
            assertEquals(promoted2, store.oldest()?.file)

            assertTrue(store.delete(pending[1].file))
            assertEquals(0, store.count())
            assertEquals(0L, store.totalBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun prunesOldestWhenExceedingCountOrByteLimitsWhileProtectingActiveFiles() {
        val dir = Files.createTempDirectory("pending-store-prune").toFile()
        try {
            val store = PendingRecordingStore(dir)
            val promoted = mutableListOf<String>()
            for (i in 1..5) {
                val tmp = store.newActiveFile().apply { writeBytes(ByteArray(44 + 1_000)) }
                promoted.add(store.enqueue(tmp, nowMs = i * 1_000L)!!.name)
            }
            assertEquals(5, store.count())

            // Protecting entry 0 forces pruning to skip it and evict entries 1 and 2 instead
            store.pruneToLimits(maxFiles = 3, maxBytes = Long.MAX_VALUE, protectedNames = setOf(promoted[0]))
            assertEquals(3, store.count())
            val remainingNames = store.listPending().map { it.file.name }
            assertTrue(promoted[0] in remainingNames)
            assertFalse(promoted[1] in remainingNames)
            assertFalse(promoted[2] in remainingNames)

            // Byte limit prunes down to 2 entries (2 * 1044 = 2088 <= 2200)
            store.pruneToLimits(maxFiles = 10, maxBytes = 2_200L)
            assertEquals(2, store.count())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun ignoresCorruptFilesAndCleansOrphanedActiveTemps() {
        val dir = Files.createTempDirectory("pending-store-clean").toFile()
        try {
            val store = PendingRecordingStore(dir)
            val empty = store.newActiveFile().apply { writeBytes(ByteArray(10)) }
            assertNull(store.enqueue(empty))
            assertFalse(empty.exists())

            val orphan = store.newActiveFile().apply { writeBytes(ByteArray(1_000)) }
            val active = store.newActiveFile().apply { writeBytes(ByteArray(1_000)) }
            store.cleanOrphanedActiveFiles(exclude = active)
            assertFalse(orphan.exists())
            assertTrue(active.exists())

            val queued = store.enqueue(active, nowMs = 5_000L)
            assertNotNull(queued)
            assertEquals(1, store.clearAll())
            assertEquals(0, store.count())
        } finally {
            dir.deleteRecursively()
        }
    }
}
