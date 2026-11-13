package io.github.shaonialife.carptocar.util

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real files exercise retention, process selection and exports racing with rotation. */
class DebugFileLogTest {
    @Test
    fun keepsOnlyContentAfterLastRealMarkerAcrossSegments() = withDirectory { root ->
        File(root, "main_debug.log.2").writeText(
            "old error\n[12:00:00.000] ${DebugFileLog.LEGACY_SESSION_MARKER}\nold build\n",
        )
        File(root, "main_debug.log.1").writeText(
            "old run\n[13:00:00.000] ${DebugFileLog.SESSION_MARKER}\nBUILD: current\nfirst error\n",
        )
        File(root, "main_debug.log").writeText("long drive\n")

        assertEquals("\nBUILD: current\nfirst error\nlong drive\n", export(root, true))
        assertEquals(
            "old error\n[12:00:00.000] ${DebugFileLog.LEGACY_SESSION_MARKER}\nold build\n" +
                "old run\n[13:00:00.000] ${DebugFileLog.SESSION_MARKER}\nBUILD: current\nfirst error\nlong drive\n",
            export(root, false),
        )
    }

    @Test
    fun missingSegmentsAndUnmarkedLegacyHistoryRemainReadable() = withDirectory { root ->
        assertFalse(DebugFileLog.appendAvailable(StringBuilder(), root, true))
        File(root, "main_debug.log.7").writeText("oldest\n")
        File(root, "main_debug.log.3").writeText("")
        File(root, "main_debug.log.1").writeText("middle\n")
        File(root, "main_debug.log").writeText("newest")
        assertEquals("oldest\nmiddle\nnewest\n", export(root, true))
        assertEquals("oldest\nmiddle\nnewest\n", export(root, false))
    }

    @Test
    fun markerOnlyExportIsNotUsableAndBothMarkerNamesSelectNewest() = withDirectory { root ->
        File(root, "main_debug.log.1").writeText(
            "[12:00:00.000] ${DebugFileLog.SESSION_MARKER}\nold build\n",
        )
        File(root, "main_debug.log").writeText(
            "[13:00:00.000] ${DebugFileLog.LEGACY_SESSION_MARKER}\n",
        )
        assertFalse(DebugFileLog.appendAvailable(StringBuilder(), root, true))
        DebugFileLog.append(root, "BUILD: latest")
        val current = export(root, true)
        assertTrue(current.contains("BUILD: latest"))
        assertFalse(current.contains("old build"))
    }

    @Test
    fun longDriveAndRestartRetainFirstFailureUntilCapacityEviction() = withDirectory { root ->
        DebugFileLog.append(root, DebugFileLog.SESSION_MARKER)
        DebugFileLog.append(root, "BUILD: first")
        DebugFileLog.append(root, "FIRST_FAILURE")
        val payload = "x".repeat(64 * 1024)
        repeat(40) { DebugFileLog.append(root, "drive-$it $payload") }
        val beforeRestart = export(root, false)
        assertTrue(beforeRestart.contains("FIRST_FAILURE"))
        assertEquals(1, Regex(Regex.escape(DebugFileLog.SESSION_MARKER)).findAll(beforeRestart).count())
        assertTrue(File(root, "main_debug.log.1").isFile)

        DebugFileLog.append(root, DebugFileLog.SESSION_MARKER)
        DebugFileLog.append(root, "BUILD: second")
        DebugFileLog.append(root, "SECOND_FAILURE")
        assertTrue(export(root, false).contains("FIRST_FAILURE"))
        assertFalse(export(root, true).contains("FIRST_FAILURE"))
        assertTrue(export(root, true).contains("BUILD: second"))
        repeat(280) { DebugFileLog.append(root, "later-$it $payload") }
        val retained = root.listFiles()!!.filter { it.name.startsWith("main_debug.log") }
        assertTrue(retained.size <= 8)
        assertTrue(retained.all { it.length() <= 2L * 1024 * 1024 })
        assertTrue(retained.sumOf { it.length() } <= 16L * 1024 * 1024)
        assertFalse(export(root, false).contains("FIRST_FAILURE"))
        assertTrue(export(root, true).contains("later-279"))
        assertFalse(export(root, false).contains(DebugFileLog.SESSION_MARKER))
    }

    @Test
    fun splitStartupMarkerAndMultibyteRecordRemainReadable() = withDirectory { root ->
        File(root, "main_debug.log").writeText("x".repeat(2 * 1024 * 1024 - 22))
        DebugFileLog.append(root, DebugFileLog.SESSION_MARKER)
        DebugFileLog.append(root, "BUILD: unicode")
        val payload = "车".repeat(800_000)
        DebugFileLog.append(root, payload)
        val current = export(root, true)
        assertTrue(current.contains("BUILD: unicode"))
        assertTrue(current.contains(payload))
        assertFalse(current.contains('\uFFFD'))
        assertFalse(current.contains(DebugFileLog.SESSION_MARKER))
        assertFalse(current.startsWith("x"))
        assertTrue(root.listFiles()!!.all { it.length() <= 2L * 1024 * 1024 })
    }

    @Test
    fun exportSnapshotSurvivesConcurrentRotationWithoutIncludingLaterAppends() = withDirectory { root ->
        DebugFileLog.append(root, DebugFileLog.SESSION_MARKER)
        DebugFileLog.append(root, "FIRST_FAILURE")
        val payload = "车".repeat(32 * 1024)
        repeat(30) { DebugFileLog.append(root, "before-$it $payload") }
        val expected = export(root, false)
        val started = CountDownLatch(1)
        val appended = CountDownLatch(1)
        val writer = Thread {
            try {
                check(started.await(10, TimeUnit.SECONDS))
                repeat(180) { DebugFileLog.append(root, "AFTER_SNAPSHOT-$it $payload") }
            } finally {
                appended.countDown()
            }
        }
        val text = StringBuilder()
        val sink = object : Appendable {
            private var first = true
            private fun beforeAppend() {
                if (first) {
                    first = false
                    started.countDown()
                    check(appended.await(10, TimeUnit.SECONDS))
                }
            }
            override fun append(value: CharSequence?): Appendable {
                beforeAppend()
                text.append(value)
                return this
            }
            override fun append(value: CharSequence?, start: Int, end: Int): Appendable {
                beforeAppend()
                text.append(value, start, end)
                return this
            }
            override fun append(value: Char): Appendable {
                beforeAppend()
                text.append(value)
                return this
            }
        }
        writer.start()
        try {
            assertTrue(DebugFileLog.appendAvailable(sink, root, false))
        } finally {
            started.countDown()
            writer.join(10_000)
        }
        assertFalse(writer.isAlive)
        assertEquals(expected, text.toString())
        assertFalse(text.contains("AFTER_SNAPSHOT"))
    }

    private fun export(root: File, currentSessionOnly: Boolean): String =
        StringBuilder().also { DebugFileLog.appendAvailable(it, root, currentSessionOnly) }.toString()

    private fun withDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("debug-log-retention-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
