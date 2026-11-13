package io.github.shaonialife.carptocar.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Failure modes: /info cut at 4KB, header cut at 2KB, overlapping CSeq loses channel,
 * writes overwrite prior records, daily files accumulate, old process evidence appears current.
 */
class RtspDumpTest {
    @Test
    fun exportedProtocolKeepsCompleteInfoHeadersAndSeparateEventChannel() {
        val root = java.nio.file.Files.createTempDirectory("rtsp-complete-").toFile()
        val capture = RtspDumpStore(root)
        val head = "RTSP/1.0 200 OK\r\nCSeq: 1\r\nX-Info: " + "z".repeat(3000) + "\r\n\r\n"
        val body = ByteArray(6000) { 0xab.toByte() }
        capture.write("RX", "/info response", head, body, "control")
        capture.write("RX", "POST /command", "CSeq: 1\r\n", byteArrayOf(1, 2), "event")
        val exported = buildString { capture.appendAvailable(this, true) }
        assertTrue(exported.contains(head))
        assertTrue(exported.contains("body=6000B hex=" + "ab".repeat(6000)))
        assertTrue(exported.contains("channel=control /info response"))
        assertTrue(exported.contains("channel=event POST /command"))
        assertTrue(exported.contains("body=2B hex=0102"))
        val restarted = RtspDumpStore(root)
        assertFalse(buildString { restarted.appendAvailable(this, true) }.contains("/info response"))
        assertTrue(buildString { restarted.appendAvailable(this, false) }.contains("/info response"))
    }

    @Test
    fun storageRotationAndOversizedRecordBoundaryAreExplicit() {
        val root = java.nio.file.Files.createTempDirectory("rtsp-rotation-").toFile()
        val capture = RtspDumpStore(root, maxFileBytes = 400, keepFiles = 2)
        repeat(5) { index ->
            capture.write("TX", "attempt-$index", "CSeq: $index", ByteArray(100), "control")
        }
        assertEquals(2, root.listFiles()!!.size)
        assertTrue(root.listFiles()!!.all { it.length() <= 400 })
        var exported = buildString { capture.appendAvailable(this, true) }
        assertTrue(exported.contains("attempt-4"))
        assertFalse(exported.contains("attempt-0"))
        capture.write("RX", "oversized", "CSeq: 6", ByteArray(1000), "event")
        exported = buildString { capture.appendAvailable(this, true) }
        assertTrue(exported.contains("STORAGE BOUNDARY: record exceeds 400 bytes"))
        assertTrue(root.listFiles()!!.all { it.length() <= 400 })
    }
}
