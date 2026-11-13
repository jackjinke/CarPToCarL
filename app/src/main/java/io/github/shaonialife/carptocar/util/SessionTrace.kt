package io.github.shaonialife.carptocar.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Rolling control-plane evidence, including sessions which linked successfully then dropped.
 * No media is recorded. Each session retains 3,000 records / 4,000,000 UTF-16 characters;
 * eviction and oversized-record truncation are explicitly reported. Five snapshots are kept
 * on disk (at most ~80 MB UTF-8), plus a bounded pending/recent set of five in memory.
 * Lifecycle/observation calls capture immutable metadata under one lock; only a single
 * background writer does disk IO. Export captures the active session without ending it.
 */
object SessionTrace {
    enum class Dir(val tag: String) { TX("TX"), RX("RX") }

    @Volatile private var store: SessionTraceStore? = null
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "session-trace-dump").apply { isDaemon = true }
    }

    @Synchronized
    fun attach(context: Context) {
        if (store == null) {
            store = SessionTraceStore(
                context.filesDir,
                writer,
                onWriteFailure = { Log.w("SessionTrace", "snapshot write failed", it) },
            )
        }
    }

    fun isActive(): Boolean = store != null
    fun sessionStart(note: String) { store?.sessionStart(note) }
    fun markLinked() { store?.markLinked() }
    fun sessionEnd(reason: String) { store?.sessionEnd(reason) }
    fun mark(text: String) { store?.mark(text) }

    /** Saves a timestamped user observation, independent of transport/disconnect callbacks. */
    fun markObservation(note: String) { store?.markObservation(note) }

    fun csm(dir: Dir, channel: String, messageId: Int, body: ByteArray, note: String = "") {
        store?.record { ts -> formatCsm(ts, dir, channel, messageId, body, note) }
    }

    fun rtsp(dir: Dir, channel: String, text: String) {
        store?.record { ts -> formatRtsp(ts, dir, channel, text) }
    }

    /** Complete payload; any retention loss is applied and labeled by the storage layer. */
    fun binary(body: ByteArray): String =
        if (body.isEmpty()) "" else "\n    <binary ${body.size}B> ${body.toHex()}"

    fun appendAvailable(out: Appendable, context: Context, currentProcessOnly: Boolean) {
        val capture = store
        if (capture != null) {
            capture.appendAvailable(out, currentProcessOnly)
        } else {
            out.append("（本次启动尚未初始化会话录制）\n")
            if (!currentProcessOnly) SessionTraceStore.appendHistorical(out, context.filesDir)
        }
    }

    internal fun formatCsm(ts: String, dir: Dir, channel: String, messageId: Int, body: ByteArray, note: String = ""): String {
        val id = "0x%04x".format(messageId)
        val suffix = if (note.isEmpty()) "" else " ($note)"
        val payload = if (body.isEmpty()) "len=0" else "len=${body.size} body=${body.toHex()}"
        return "[$ts] ${dir.tag} $channel csm id=$id $payload$suffix"
    }

    internal fun formatRtsp(ts: String, dir: Dir, channel: String, text: String): String {
        val head = text.lineSequence().firstOrNull()?.trim().orEmpty()
        val body = text.lineSequence().joinToString("\n") { "    | $it" }
        return "[$ts] ${dir.tag} $channel rtsp len=${text.length} $head\n$body"
    }

    private fun ByteArray.toHex(): String = buildString(size * 3) {
        for (index in indices) {
            if (index > 0) append(' ')
            val value = this@toHex[index].toInt() and 0xff
            append(HEX[value ushr 4]).append(HEX[value and 15])
        }
    }

    private const val HEX = "0123456789abcdef"
}

internal class SessionTraceStore(
    private val root: File,
    private val executor: Executor,
    private val onWriteFailure: (Throwable) -> Unit = {},
    private val maxChars: Int = 4_000_000,
    private val maxRecords: Int = 3_000,
) {
    private data class Snapshot(
        val name: String,
        val sequence: Int,
        val startedAt: Long,
        val note: String,
        val linked: Boolean,
        val ended: Boolean,
        val capturedAt: String,
        val elapsedMs: Long,
        val trigger: String,
        val evicted: Int,
        val truncated: Int,
        val records: List<String>,
    )

    private val lock = Any()
    private val diskLock = Any()
    private val runId = "${System.currentTimeMillis()}-${System.nanoTime().toString(16)}"
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lines = ArrayDeque<String>()
    private var chars = 0
    private var evicted = 0
    private var truncated = 0
    private var sequence = 0
    private var startedAt = 0L
    private var note = ""
    private var linked = false
    private var ended = false
    private var active = false
    private val recent = LinkedHashMap<String, Snapshot>()
    private val pending = LinkedHashMap<String, Snapshot>()
    private var writing = false

    init {
        require(maxChars >= 256 && maxRecords > 0)
    }

    fun sessionStart(note: String) = synchronized(lock) {
        if (active && !ended) {
            appendLocked("[${now()}] ========== 会话被新一轮替代 ==========")
            saveLocked(snapshotLocked("被新一轮替代"))
        }
        lines.clear()
        chars = 0
        evicted = 0
        truncated = 0
        sequence++
        startedAt = System.currentTimeMillis()
        this.note = note
        linked = false
        ended = false
        active = true
        appendLocked("[${now()}] ========== 会话开始 #$sequence · $note ==========")
    }

    fun markLinked() = synchronized(lock) {
        ensureSessionLocked()
        linked = true
        appendLocked("[${now()}] ---- 已建链（保留后续协议与断链现场）")
        saveLocked(snapshotLocked("已建链检查点"))
    }

    fun sessionEnd(reason: String) = synchronized(lock) {
        if (!active || ended) return@synchronized
        ended = true
        appendLocked("[${now()}] ========== 会话结束 · $reason ==========")
        saveLocked(snapshotLocked(reason))
    }

    fun mark(text: String) = record { ts -> "[$ts] ---- $text" }

    fun markObservation(note: String) = synchronized(lock) {
        ensureSessionLocked()
        appendLocked("[${now()}] USER_OBSERVATION: $note (transport state not inferred)")
        saveLocked(snapshotLocked("用户观察: $note"))
    }

    fun record(format: (String) -> String) = synchronized(lock) {
        ensureSessionLocked()
        appendLocked(format(now()))
    }

    private fun ensureSessionLocked() {
        // An observation after disconnect belongs to the retained ended session, not an old
        // failure file selected by timestamp. Only an explicit sessionStart begins a new one.
        if (!active) sessionStart("进程控制报文 / 用户观察（尚未启动连接）")
    }

    private fun now(): String = stamp.format(Date())

    private fun appendLocked(text: String) {
        val line = if (text.length + 1 > maxChars) {
            truncated++
            val marker = "\n[STORAGE BOUNDARY: oversized record truncated at $maxChars characters]"
            text.take((maxChars - marker.length - 1).coerceAtLeast(0)) + marker
        } else text
        lines.addLast(line)
        chars += line.length + 1
        while (lines.size > maxRecords || chars > maxChars) {
            chars -= lines.removeFirst().length + 1
            evicted++
        }
    }

    private fun snapshotLocked(trigger: String): Snapshot =
        Snapshot(
            name = "session_trace_${runId}_${sequence.toString().padStart(5, '0')}.log",
            sequence = sequence,
            startedAt = startedAt,
            note = note,
            linked = linked,
            ended = ended,
            capturedAt = now(),
            elapsedMs = System.currentTimeMillis() - startedAt,
            trigger = trigger,
            evicted = evicted,
            truncated = truncated,
            // Strings are immutable; copy at most 3,000 references, never payload contents,
            // on the protocol/UI caller. Serialization belongs to the writer/export thread.
            records = lines.toList(),
        )

    private fun appendSnapshot(out: Appendable, snapshot: Snapshot) {
        with(out) {
            appendLine("# CarPToCarL 会话控制面快照")
            appendLine("# process=$runId session=${snapshot.sequence} startedAt=${snapshot.startedAt}")
            appendLine("# 触发: ${snapshot.trigger} | 启动: ${snapshot.note}")
            appendLine("# 状态: ${if (snapshot.ended) "已结束" else "进行中"}; linked=${snapshot.linked}")
            appendLine("# capturedAt=${snapshot.capturedAt} elapsedMs=${snapshot.elapsedMs}")
            appendLine("# storage: $maxRecords records / $maxChars characters; evictedRecords=${snapshot.evicted} truncatedRecords=${snapshot.truncated}")
            appendLine("# 控制面原文；不含音视频。敏感诊断，仅与信任的人分享。")
            for (line in snapshot.records) appendLine(line)
        }
    }

    private fun saveLocked(snapshot: Snapshot) {
        recent[snapshot.name] = snapshot
        while (recent.size > KEEP_FILES) recent.remove(recent.keys.first())
        pending[snapshot.name] = snapshot
        while (pending.size > KEEP_FILES) pending.remove(pending.keys.first())
        if (!writing) {
            writing = true
            executor.execute { drainWrites() }
        }
    }

    private fun drainWrites() {
        while (true) {
            val snapshot = synchronized(lock) {
                val first = pending.entries.firstOrNull()
                if (first == null) {
                    writing = false
                    return
                }
                pending.remove(first.key)
                first.value
            }
            runCatching {
                synchronized(diskLock) {
                    writeCheckpoint(snapshot)
                    files(root).drop(KEEP_FILES).forEach { check(it.delete()) { "Cannot rotate ${it.name}" } }
                }
            }.onFailure(onWriteFailure)
        }
    }

    private fun writeCheckpoint(snapshot: Snapshot) {
        val destination = File(root, snapshot.name)
        // A fixed staging path bounds abandoned crash debris to one checkpoint. Only the
        // single background writer uses it; an incomplete write never touches the old log.
        val staging = File(root, ".session_trace_checkpoint.tmp")
        try {
            staging.bufferedWriter(Charsets.UTF_8).use { appendSnapshot(it, snapshot) }
            java.nio.file.Files.move(
                staging.toPath(),
                destination.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            if (staging.isFile && !staging.delete()) {
                onWriteFailure(java.io.IOException("Cannot remove checkpoint staging file"))
            }
        }
    }

    /** IO-thread only; immutable current/recent copies also cover writes still pending. */
    fun appendAvailable(out: Appendable, currentProcessOnly: Boolean) {
        val snapshots = synchronized(lock) {
            val captured = LinkedHashMap(recent)
            if (active) {
                val current = snapshotLocked("导出时捕获当前会话")
                captured[current.name] = current
            }
            captured.values.toList()
        }
        out.append("Session storage: $KEEP_FILES snapshots; each $maxRecords records / $maxChars characters.\n")
        if (snapshots.isEmpty() && currentProcessOnly) out.append("（本次启动没有会话证据；未附无关的历史失败）\n")
        synchronized(diskLock) {
            for (file in files(root).asReversed()) {
                if (snapshots.any { it.name == file.name }) continue
                if (currentProcessOnly && !file.name.startsWith("session_trace_${runId}_")) continue
                appendFile(out, file)
            }
        }
        for (snapshot in snapshots) {
            out.append("\n========== 本次进程会话 ${snapshot.name} ==========\n")
            appendSnapshot(out, snapshot)
        }
    }

    companion object {
        private const val KEEP_FILES = 5

        private fun files(root: File): List<File> = root.listFiles { file ->
            file.isFile && file.name.startsWith("session_trace_") && file.name.endsWith(".log")
        }?.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name }) ?: emptyList()

        private fun appendFile(out: Appendable, file: File) {
            val kind = if (file.name.startsWith("session_trace_fail_")) "历史旧版失败快照" else "已保存的历史会话"
            out.append("\n========== $kind ${file.name} ==========\n")
            file.bufferedReader().use { it.copyToAppendable(out) }
        }

        fun appendHistorical(out: Appendable, root: File) {
            for (file in files(root).asReversed()) appendFile(out, file)
        }
    }
}
