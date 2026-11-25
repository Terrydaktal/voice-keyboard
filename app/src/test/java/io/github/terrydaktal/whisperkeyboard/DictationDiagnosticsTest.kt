package io.github.terrydaktal.whisperkeyboard

import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationDiagnosticsTest {
    @Test
    fun ringBufferPreservesOrderingAndReportsOverwrites() {
        var wall = 1_000L
        var mono = 10L
        val recorder = DiagnosticFlightRecorder(
            logDir = null,
            ringCapacity = 4,
            wallClockMs = { wall += 10; wall },
            monoClockMs = { mono += 5; mono },
        )

        repeat(7) { i ->
            recorder.record("state", "step_$i", opId = "rec-0001", fields = mapOf("idx" to i))
        }

        val recent = recorder.recentEvents(10)
        assertEquals(4, recent.size)
        assertEquals(listOf(4L, 5L, 6L, 7L), recent.map { it.seq })
        assertEquals(listOf("step_3", "step_4", "step_5", "step_6"), recent.map { it.event })

        val stats = recorder.stats()
        assertEquals(4, stats.ringSize)
        assertEquals(4, stats.ringCapacity)
        assertEquals(7L, stats.totalEvents)
        assertEquals(3L, stats.overwrittenCount)
        assertEquals(4L, stats.oldestSeq)
        assertEquals(7L, stats.newestSeq)
    }

    @Test
    fun persistedJsonlRotatesAndReadsAcrossPreviousAndActiveFiles() {
        val dir = Files.createTempDirectory("voice-kb-diag-test").toFile()
        try {
            val recorder = DiagnosticFlightRecorder(
                logDir = dir,
                ringCapacity = 16,
                maxFileBytes = 512L,
            )
            repeat(25) { idx ->
                recorder.record(
                    category = "s3",
                    event = "stream_progress",
                    opId = "rec-0009",
                    fields = mapOf("chunk" to idx, "oggBytesOut" to idx * 1024),
                )
            }
            assertTrue("flush should complete", recorder.flush(timeoutMs = 2_000L))

            val active = File(dir, DiagnosticFlightRecorder.ACTIVE_LOG_FILE)
            val prev = File(dir, DiagnosticFlightRecorder.PREV_LOG_FILE)
            assertTrue("active log must exist", active.isFile)
            assertTrue("previous rotated log must exist", prev.isFile)

            val tail = recorder.readPersistedTail(maxLines = 10)
            assertTrue("tail should return persisted JSONL lines", tail.isNotEmpty())
            assertTrue(tail.last().contains("\"chunk\":\"24\""))
            assertTrue(tail.all { it.startsWith("{\"schema\":1,") && it.endsWith("}") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun redactsCanarySecretsAndForbiddenTranscriptFields() {
        val canaryKey = "AIzaSyCANARY_SECRET_KEY_1234567890"
        val canaryTranscript = "my private medical note 994411"
        val recorder = DiagnosticFlightRecorder(logDir = null, ringCapacity = 8)

        val ev = recorder.record(
            category = "s3",
            event = "call_close",
            opId = "rec-0002",
            fields = mapOf(
                "transcript" to canaryTranscript,
                "text" to canaryTranscript,
                "detail" to "x-goog-api-key=$canaryKey failed with Bearer supersecret123",
                "url" to "https://user:pass@example.com/transcribe_raw?token=abc",
                "transcriptLen" to canaryTranscript.length,
            ),
        )

        val json = ev!!.toJsonLine()
        assertFalse("must never contain raw API key", json.contains(canaryKey))
        assertFalse("must never contain raw transcript", json.contains(canaryTranscript))
        assertFalse("must never contain bearer secret", json.contains("supersecret123"))
        assertFalse("must never contain URL password", json.contains("user:pass"))
        assertEquals("<redacted>", ev.fields["transcript"])
        assertEquals("<redacted>", ev.fields["text"])
        assertEquals(canaryTranscript.length.toString(), ev.fields["transcriptLen"])
        assertEquals(
            "https://<redacted>@example.com/transcribe_raw",
            DiagnosticFlightRecorder.sanitizeUrlForLog("https://user:pass@example.com/transcribe_raw?token=abc"),
        )
    }

    @Test
    fun runtimeDisableAndCategorySwitchesSkipRecordingAndDiskWrites() {
        val dir = Files.createTempDirectory("voice-kb-diag-disabled").toFile()
        try {
            val recorder = DiagnosticFlightRecorder(logDir = dir, ringCapacity = 8)
            recorder.enabled = false
            assertNull(recorder.record("s3", "stream_progress", fields = mapOf("chunk" to 1)))
            assertEquals(0L, recorder.stats().totalEvents)
            assertTrue(recorder.flush(timeoutMs = 1_000L))
            assertEquals(0L, recorder.totalDiskBytes())

            recorder.enabled = true
            recorder.setCategoryEnabled("s3", false)
            assertNull(recorder.record("s3", "stream_progress"))
            assertNotNull(recorder.record("state", "phase_transition"))
            assertEquals(1L, recorder.stats().totalEvents)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun preservesFirstCausalErrorAndCauseChainPerOperation() {
        val recorder = DiagnosticFlightRecorder(logDir = null, ringCapacity = 16)
        val root = IOException("HTTP/2 RST_STREAM CANCEL")
        val primary = IOException("Rambler live stream aborted", root)
        val secondary = IllegalStateException("cleanup sink already closed")

        recorder.recordError("s3_call", "IOException", primary, opId = "rec-0007")
        recorder.recordError("cleanup", "IllegalStateException", secondary, opId = "rec-0007")

        val first = recorder.firstErrorFor("rec-0007")
        assertNotNull(first)
        assertEquals("s3_call", first!!.stage)
        assertTrue(first.causeChain.contains("HTTP/2 RST_STREAM CANCEL"))

        val last = recorder.lastError()
        assertNotNull(last)
        assertEquals("cleanup", last!!.stage)
    }

    @Test
    fun validatesGrpcTrailerStatuses() {
        assertNull(RamblerS3Client.validateGrpcStatus(null, null))
        assertNull(RamblerS3Client.validateGrpcStatus("", ""))
        assertNull(RamblerS3Client.validateGrpcStatus("0", ""))

        val err = RamblerS3Client.validateGrpcStatus("4", "DEADLINE_EXCEEDED")
        assertNotNull(err)
        assertEquals("4", err!!.grpcStatus)
        assertEquals("DEADLINE_EXCEEDED", err.grpcMessage)
        assertTrue(err.message!!.contains("4"))
        assertTrue(err.message!!.contains("DEADLINE_EXCEEDED"))
    }

    @Test
    fun editorTargetComparisonAllowsRefreshedConnectionInSameField() {
        val chatField = WhisperKeyboardService.EditorIdentity("com.example.chat", 101, 131073)
        val otherField = WhisperKeyboardService.EditorIdentity("com.example.chat", 202, 131073)
        val otherApp = WhisperKeyboardService.EditorIdentity("com.example.notes", 101, 131073)

        assertTrue(
            "same editor identity with refreshed InputConnection should auto-insert",
            WhisperKeyboardService.isSameSubmissionTarget(chatField, chatField.copy(), sameConnectionReference = false),
        )
        assertFalse(
            "different fieldId should require manual confirmation",
            WhisperKeyboardService.isSameSubmissionTarget(chatField, otherField, sameConnectionReference = false),
        )
        assertFalse(
            "different package should require manual confirmation",
            WhisperKeyboardService.isSameSubmissionTarget(chatField, otherApp, sameConnectionReference = false),
        )
        assertTrue(
            "null submitted editor should not block",
            WhisperKeyboardService.isSameSubmissionTarget(null, otherApp, sameConnectionReference = false),
        )
    }

    @Test
    fun dumpToWriterEmitsSnapshotJsonAndRecentEvents() {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        DictationDiagnostics.record("state", "unit_test_marker", opId = "test-0001")
        DictationDiagnostics.dumpToWriter(pw, context = null, source = "UnitTest", maxEvents = 10)
        pw.flush()

        val output = sw.toString()
        assertTrue(output.contains("=== VoiceKeyboard Diagnostics (UnitTest) ==="))
        assertTrue(output.contains("\"schema\":1"))
        assertTrue(output.contains("\"consistency\":\"best-effort\""))
        assertTrue(output.contains("unit_test_marker"))
    }

    @Test
    fun persistsAtomicLastSnapshotJsonAndRedactedLastCrashJson() {
        val dir = Files.createTempDirectory("voice-kb-diag-crash").toFile()
        try {
            val recorder = DiagnosticFlightRecorder(logDir = dir, ringCapacity = 8)
            val snapJson = """{"schema":1,"consistency":"best-effort","service":{"phase":"TRANSCRIBING"}}"""
            recorder.record("state", "before_crash", opId = "rec-0042")
            recorder.writeSnapshotAsync(snapJson)
            assertTrue(recorder.flush(timeoutMs = 2_000L))

            val snapshotFile = File(dir, DiagnosticFlightRecorder.SNAPSHOT_FILE)
            assertTrue("last_snapshot.json should exist", snapshotFile.isFile)
            assertTrue(snapshotFile.readText().contains("\"phase\":\"TRANSCRIBING\""))

            val canaryKey = "AIzaSyCRASH_CANARY_9876543210"
            val fatal = IllegalStateException("fatal boom with x-goog-api-key=$canaryKey")
            recorder.writeCrashSync("voice-kb-worker", fatal, snapJson)

            val crashFile = File(dir, DiagnosticFlightRecorder.CRASH_FILE)
            val eventsFile = File(dir, DiagnosticFlightRecorder.ACTIVE_LOG_FILE)
            assertTrue("last_crash.json should exist", crashFile.isFile)
            assertTrue("events.jsonl should be flushed synchronously on crash", eventsFile.isFile)

            val crashText = crashFile.readText()
            assertFalse("crash report must redact API key", crashText.contains(canaryKey))
            assertTrue(crashText.contains("\"thread\":\"voice-kb-worker\""))
            assertTrue(crashText.contains("\"errClass\":\"java.lang.IllegalStateException\""))
            assertTrue(crashText.contains("\"snapshot\":{\"schema\":1"))
            assertTrue(eventsFile.readText().contains("uncaught_exception"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
