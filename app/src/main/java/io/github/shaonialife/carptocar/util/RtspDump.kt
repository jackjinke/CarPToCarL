package io.github.shaonialife.carptocar.util

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Control/event protocol only; never media. Headers and payload hex are complete until the
 * explicit storage boundary: four 8 MiB segments across all dates/processes. An individual
 * record larger than a segment is truncated with an explicit marker, not silently summarized.
 * Callers already run on protocol workers; export also runs off the UI thread.
 */
object RtspDump {
    @Volatile private var store: RtspDumpStore? = null
    @Volatile var enabled: Boolean = true

    @Synchronized
    fun init(filesDir: File) {
        if (store == null) store = RtspDumpStore(filesDir)
    }

    fun tx(label: String, head: String, body: ByteArray, channel: String = "control") =
        write("TX", label, head, body, channel)

    fun rx(label: String, head: String, body: ByteArray, channel: String = "control") =
        write("RX", label, head, body, channel)

    private fun write(direction: String, label: String, head: String, body: ByteArray, channel: String) {
        if (!enabled) return
        runCatching { store?.write(direction, label, head, body, channel) }
            .onFailure { Log.w("RtspDump", "write failed", it) }
    }

    fun appendAvailable(out: Appendable, filesDir: File, currentProcessOnly: Boolean) {
        val capture = store
        if (capture != null) {
            capture.appendAvailable(out, currentProcessOnly)
        } else if (currentProcessOnly) {
            out.append("（本次启动尚未录制 RTSP；不将历史报文视作当前会话）\n")
        } else {
            RtspDumpStore(filesDir).appendAvailable(out, false)
        }
    }
}

internal class RtspDumpStore(
    private val root: File,
    private val maxFileBytes: Long = 8L * 1024 * 1024,
    private val keepFiles: Int = 4,
) {
    private val runId = "${System.currentTimeMillis()}-${System.nanoTime().toString(16)}"
    private val lock = Any()
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var segment = 0

    init {
        require(maxFileBytes >= 256 && keepFiles > 0)
    }

    fun write(direction: String, label: String, head: String, body: ByteArray, channel: String) {
        synchronized(lock) {
            val prefix = buildString {
                append('[').append(stamp.format(Date())).append("] ").append(direction)
                    .append(" channel=").append(channel).append(' ').append(label).append('\n')
                append(head)
                if (!head.endsWith('\n')) append('\n')
                append("body=").append(body.size).append("B hex=")
            }.toByteArray(Charsets.UTF_8)
            val recordBytes = prefix.size.toLong() + body.size.toLong() * 2 + 2
            val truncated = recordBytes > maxFileBytes
            val marker = if (truncated) {
                "\n[STORAGE BOUNDARY: record exceeds $maxFileBytes bytes; remaining bytes omitted]\n".toByteArray()
            } else ByteArray(0)
            var file = currentFile()
            if (file.length() > 0 && file.length() + minOf(recordBytes, maxFileBytes) > maxFileBytes) {
                segment++
                file = currentFile()
            }
            // Hex is emitted in bounded chunks: no per-byte formatting or full payload copy.
            java.io.FileOutputStream(file, true).buffered().use { stream ->
                var budget = maxFileBytes - marker.size
                val prefixBytes = minOf(prefix.size.toLong(), budget).toInt()
                stream.write(prefix, 0, prefixBytes)
                budget -= prefixBytes
                val hex = ByteArray(8192)
                var offset = 0
                while (offset < body.size && budget >= 2) {
                    val count = minOf(body.size - offset, hex.size / 2, (budget / 2).toInt())
                    for (index in 0 until count) {
                        val value = body[offset + index].toInt() and 0xff
                        hex[index * 2] = HEX[value ushr 4].code.toByte()
                        hex[index * 2 + 1] = HEX[value and 15].code.toByte()
                    }
                    stream.write(hex, 0, count * 2)
                    offset += count
                    budget -= count * 2
                }
                if (truncated) stream.write(marker) else stream.write(byteArrayOf(10, 10))
            }
            files().dropLast(keepFiles).forEach { check(it.delete()) { "Cannot rotate ${it.name}" } }
        }
    }

    fun appendAvailable(out: Appendable, currentProcessOnly: Boolean) {
        // Open fixed-length views while holding the writer lock, then release it before a
        // potentially slow MediaStore export. Rotation can unlink an open segment safely.
        val available = synchronized(lock) {
            files().filter { !currentProcessOnly || it.name.startsWith("rtsp-$runId-") }
                .map { file ->
                    val input = java.io.FileInputStream(file)
                    Triple(file.name, input, input.channel.size())
                }
        }
        out.append("RTSP storage: $keepFiles × $maxFileBytes bytes; oldest segments rotate; no media packets.\n")
        if (available.isEmpty()) out.append("（此范围内没有可用 RTSP 报文）\n")
        try {
            for ((name, input, size) in available) {
                out.append("\n========== RTSP segment $name ==========\n")
                object : java.io.FilterInputStream(input) {
                    var remaining = size
                    override fun read(): Int {
                        if (remaining == 0L) return -1
                        val value = super.read()
                        if (value >= 0) remaining--
                        return value
                    }

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (remaining == 0L) return -1
                        val count = `in`.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
                        if (count > 0) remaining -= count
                        return count
                    }
                }.bufferedReader().use { it.copyToAppendable(out) }
            }
        } finally {
            available.forEach { it.second.close() }
        }
    }

    private fun currentFile() = File(root, "rtsp-$runId-${segment.toString().padStart(5, '0')}.log")

    private fun files(): List<File> = root.listFiles { file ->
        file.isFile && file.name.startsWith("rtsp-") && file.name.endsWith(".log")
    }?.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name }) ?: emptyList()

    private companion object { const val HEX = "0123456789abcdef" }
}

internal fun java.io.Reader.copyToAppendable(out: Appendable) {
    val buffer = CharArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        out.append(java.nio.CharBuffer.wrap(buffer, 0, count))
    }
}
