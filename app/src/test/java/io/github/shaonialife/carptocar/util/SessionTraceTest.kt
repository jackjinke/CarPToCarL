package io.github.shaonialife.carptocar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Consumer failure modes: linked evidence discarded; delayed serialization uses another
 * session's mutable state; observations require disconnect; old failure mislabeled current;
 * unbounded storage; failed checkpoint replacement destroys the last complete evidence.
 */
class SessionTraceTest {
    @Test
    fun linkedDropObservationAndQueuedWritesKeepTheirOwnSessionMetadata() {
        val root = java.nio.file.Files.createTempDirectory("session-evidence-").toFile()
        val jobs = ArrayDeque<Runnable>()
        val store = SessionTraceStore(root, java.util.concurrent.Executor { jobs.addLast(it) })
        store.sessionStart("first car attempt")
        store.mark("before link")
        store.markLinked()
        store.markObservation("车机画面已退出")
        val live = buildString { store.appendAvailable(this, true) }
        assertTrue(live.contains("USER_OBSERVATION: 车机画面已退出"))
        assertTrue(live.contains("linked=true"))
        store.sessionEnd("event reset")
        store.sessionStart("second car attempt")
        store.mark("second-only evidence")
        while (jobs.isNotEmpty()) jobs.removeFirst().run()
        val saved = root.listFiles()!!.single().readText()
        assertTrue(saved.contains("first car attempt"))
        assertTrue(saved.contains("before link"))
        assertTrue(saved.contains("event reset"))
        assertFalse(saved.contains("second car attempt"))
        val restarted = SessionTraceStore(root, java.util.concurrent.Executor { it.run() })
        assertFalse(buildString { restarted.appendAvailable(this, true) }.contains("first car attempt"))
        assertTrue(buildString { restarted.appendAvailable(this, false) }.contains("first car attempt"))
    }

    @Test
    fun storageBoundariesAreExplicitAndSnapshotsStayBounded() {
        val root = java.nio.file.Files.createTempDirectory("session-retention-").toFile()
        val store = SessionTraceStore(root, java.util.concurrent.Executor { it.run() }, maxChars = 300, maxRecords = 3)
        repeat(8) { index ->
            store.sessionStart("attempt $index")
            store.mark("x".repeat(500))
            store.markObservation("screen exit $index")
            store.sessionEnd("stopped $index")
        }
        assertEquals(5, root.listFiles()!!.size)
        val content = buildString { store.appendAvailable(this, true) }
        assertTrue(content.contains("truncatedRecords=1"))
        assertFalse(content.contains("evictedRecords=0"))
        assertTrue(content.contains("screen exit 7"))
        assertFalse(content.contains("attempt 0"))
    }

    @Test
    fun observationCapturesRecordMembershipBeforeBackgroundSerialization() {
        val root = java.nio.file.Files.createTempDirectory("session-tap-snapshot-").toFile()
        val jobs = ArrayDeque<Runnable>()
        val store = SessionTraceStore(root, java.util.concurrent.Executor { jobs.addLast(it) })
        store.sessionStart("tap session")
        val payload = "payload-start:" + "a".repeat(200_000) + ":payload-end"
        store.mark(payload)
        store.markObservation("车机画面已退出")
        store.mark("arrived after observation")
        assertTrue(root.listFiles()!!.isEmpty())
        while (jobs.isNotEmpty()) jobs.removeFirst().run()
        val checkpoint = root.listFiles()!!.single().readText()
        assertTrue(checkpoint.contains(payload))
        assertTrue(checkpoint.contains("USER_OBSERVATION: 车机画面已退出"))
        assertFalse(checkpoint.contains("arrived after observation"))
        assertTrue(buildString { store.appendAvailable(this, true) }.contains("arrived after observation"))
    }

    @Test
    fun failedCheckpointWritePreservesPreviousCompleteCheckpoint() {
        val root = java.nio.file.Files.createTempDirectory("session-atomic-checkpoint-").toFile()
        val jobs = ArrayDeque<Runnable>()
        val failures = mutableListOf<Throwable>()
        val store = SessionTraceStore(
            root,
            java.util.concurrent.Executor { jobs.addLast(it) },
            onWriteFailure = { failures.add(it) },
        )
        store.sessionStart("linked session")
        store.markLinked()
        while (jobs.isNotEmpty()) jobs.removeFirst().run()
        val checkpoint = root.listFiles()!!.single()
        val previous = checkpoint.readText()
        // A real filesystem failure opening the staging path; no mock writer or permission
        // assumptions (which fail under privileged CI). The old final file must be untouched.
        val blockedStaging = java.io.File(root, ".session_trace_checkpoint.tmp")
        assertTrue(blockedStaging.mkdir())
        store.markObservation("first screen exit")
        while (jobs.isNotEmpty()) jobs.removeFirst().run()
        assertEquals(1, failures.size)
        assertEquals(previous, checkpoint.readText())
        assertTrue(blockedStaging.delete())
        store.markObservation("second screen exit")
        while (jobs.isNotEmpty()) jobs.removeFirst().run()
        val replacement = checkpoint.readText()
        assertTrue(replacement.contains("first screen exit"))
        assertTrue(replacement.contains("second screen exit"))
        assertTrue(replacement.contains("linked=true"))
        assertFalse(blockedStaging.exists())
    }
}
