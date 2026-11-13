package io.github.shaonialife.carptocar.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Reader
import java.io.SequenceInputStream
import java.nio.CharBuffer
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * Shared diagnostic log, including on devices whose logcat is encrypted.
 * main_debug.log remains the active file; .1 is its immediate predecessor.
 */
object DebugFileLog {
    private val lock = Any()
    private val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // Eight 2 MiB segments retain at most 16 MiB across processes. Only capacity eviction
    // removes history; rotation must never impersonate a real process startup.
    private const val SEGMENT_BYTES = 2L * 1024 * 1024
    private const val SEGMENT_COUNT = 8
    private const val RETAINED_BYTES = SEGMENT_BYTES * SEGMENT_COUNT
    private const val FILE_NAME = "main_debug.log"

    const val SESSION_MARKER = "===== CarPToCarL 进程启动（本次日志起点）====="
    internal const val LEGACY_SESSION_MARKER = "===== CarDeck 进程启动（本次日志起点）====="
    private val markers = arrayOf(SESSION_MARKER, LEGACY_SESSION_MARKER)

    /** Called once by SenderApp; build identity is appended by that same startup path. */
    fun markSessionStart(context: Context) {
        append(context, SESSION_MARKER)
    }

    fun append(context: Context, message: String) {
        try {
            append(context.filesDir, message)
        } catch (error: Exception) {
            Log.w("DebugFileLog", "append failed", error)
        }
    }

    /** File-system seam shared by the Android writer and retention replay. */
    internal fun append(filesDir: File, message: String) {
        synchronized(lock) {
            OutputStreamWriter(SegmentOutputStream(filesDir), Charsets.UTF_8).use { writer ->
                writer.append('[').append(timestamp.format(Date())).append("] ")
                // Writer.append(String) may allocate a char array as large as the record.
                // Bounded writes also allow unusually large records to span segments.
                var offset = 0
                while (offset < message.length) {
                    val count = minOf(8192, message.length - offset)
                    writer.write(message, offset, count)
                    offset += count
                }
                writer.append('\n')
            }
        }
    }

    /**
     * Streams a fixed-length snapshot oldest first. CURRENT selects after the last real
     * startup marker, even when it precedes or straddles a segment boundary. If that marker
     * has normally aged out, all retained text is from that same process and is included.
     * Returns whether nonblank text was written. Input/output failures propagate to export.
     */
    fun appendAvailable(out: Appendable, filesDir: File, currentSessionOnly: Boolean): Boolean {
        val available = ArrayList<Segment>()
        try {
            // Open descriptors under the writer lock, but never hold it while writing a slow
            // export. Renaming/unlinking cannot invalidate these open, size-limited views.
            synchronized(lock) {
                for (file in files(filesDir)) {
                    val input = FileInputStream(file)
                    try {
                        available.add(Segment(input, input.channel.size()))
                    } catch (error: Exception) {
                        input.close()
                        throw error
                    }
                }
            }
            val start = if (currentSessionOnly) {
                reader(available).use(::lastMarkerEnd)
            } else {
                0L
            }
            var hasText = false
            var lastChar: Char? = null
            reader(available).use { source ->
                var remaining = start
                val buffer = CharArray(8192)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    val skipped = minOf(remaining, count.toLong()).toInt()
                    remaining -= skipped
                    if (skipped == count) continue
                    for (index in skipped until count) {
                        if (!buffer[index].isWhitespace()) hasText = true
                    }
                    out.append(CharBuffer.wrap(buffer, skipped, count - skipped))
                    lastChar = buffer[count - 1]
                }
            }
            if (lastChar != null && lastChar != '\n') out.append('\n')
            return hasText
        } finally {
            available.forEach { it.input.close() }
        }
    }

    private data class Segment(val input: FileInputStream, val size: Long)

    private fun reader(segments: List<Segment>): Reader {
        val inputs = segments.map { segment ->
            segment.input.channel.position(0)
            object : FilterInputStream(segment.input) {
                private var remaining = segment.size

                override fun read(): Int {
                    if (remaining == 0L) return -1
                    val value = `in`.read()
                    if (value >= 0) remaining--
                    return value
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0
                    if (remaining == 0L) return -1
                    val count = `in`.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
                    if (count > 0) remaining -= count
                    return count
                }

                // Two passes share the snapshot descriptors; appendAvailable owns closing.
                override fun close() = Unit
            }
        }
        return SequenceInputStream(Collections.enumeration(inputs)).reader(Charsets.UTF_8).buffered()
    }

    private fun lastMarkerEnd(source: Reader): Long {
        val overlap = markers.maxOf { it.length } - 1
        val buffer = CharArray(8192 + overlap)
        var carry = 0
        var readChars = 0L
        var end = 0L
        while (true) {
            val count = source.read(buffer, carry, 8192)
            if (count < 0) return end
            val available = carry + count
            for (marker in markers) {
                for (offset in 0..available - marker.length) {
                    if (buffer[offset] != marker[0]) continue
                    var index = 1
                    while (index < marker.length && buffer[offset + index] == marker[index]) index++
                    if (index == marker.length) {
                        end = maxOf(end, readChars - carry + offset + marker.length)
                    }
                }
            }
            readChars += count
            carry = minOf(overlap, available)
            buffer.copyInto(buffer, 0, available - carry, available)
        }
    }

    /** Oldest first, independently of mtime; missing/empty segments are harmless. */
    private fun files(root: File): List<File> =
        (SEGMENT_COUNT - 1 downTo 0).map { segmentFile(root, it) }.filter { it.isFile }

    private fun segmentFile(root: File, index: Int): File =
        File(root, if (index == 0) FILE_NAME else "$FILE_NAME.$index")

    private fun rotate(root: File) {
        val oldest = segmentFile(root, SEGMENT_COUNT - 1)
        if (oldest.exists()) check(oldest.delete()) { "Cannot evict ${oldest.name}" }
        for (index in SEGMENT_COUNT - 2 downTo 0) {
            val source = segmentFile(root, index)
            if (source.exists()) {
                check(source.renameTo(segmentFile(root, index + 1))) { "Cannot rotate ${source.name}" }
            }
        }
        // A pre-upgrade active log may already exceed the new segment size. Keep it intact
        // until ordinary total-capacity eviction, rather than deleting its first failure.
        val retained = files(root)
        var bytes = retained.sumOf { it.length() }
        for (file in retained) {
            if (bytes <= RETAINED_BYTES - SEGMENT_BYTES) break
            val length = file.length()
            check(file.delete()) { "Cannot evict ${file.name}" }
            bytes -= length
        }
    }

    /** UTF-8 writes stay bounded without a whole-record byte-array copy. */
    private class SegmentOutputStream(private val root: File) : OutputStream() {
        private var output: FileOutputStream? = null
        private var size = segmentFile(root, 0).length()

        override fun write(value: Int) {
            if (size >= SEGMENT_BYTES) {
                output?.close()
                output = null
                rotate(root)
                size = 0
            }
            val stream = output ?: FileOutputStream(segmentFile(root, 0), true).also { output = it }
            stream.write(value)
            size++
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            var position = offset
            val end = offset + length
            while (position < end) {
                var count = minOf(end - position.toLong(), SEGMENT_BYTES - size).coerceAtLeast(0).toInt()
                // Never split a UTF-8 code point, so main_debug.log itself stays readable.
                while (count > 0 && position + count < end &&
                    buffer[position + count].toInt() and 0xc0 == 0x80
                ) {
                    count--
                }
                if (count == 0) {
                    output?.close()
                    output = null
                    rotate(root)
                    size = 0
                    continue
                }
                val stream = output ?: FileOutputStream(segmentFile(root, 0), true).also { output = it }
                stream.write(buffer, position, count)
                position += count
                size += count
            }
        }

        override fun close() {
            output?.close()
        }
    }
}
