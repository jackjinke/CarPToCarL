package io.github.shaonialife.carptocar.carlife

import android.os.SystemClock
import java.io.InputStream

/** Tracks producer progress, not AirPlay's placeholder video or a handshake success label.
 * Payloads are never retained. Each socket owns its counters so a retiring reader cannot
 * overwrite the progress of its replacement.
 */
internal class CarLifeSourceProgress {
    private val createdAt = SystemClock.uptimeMillis()
    @Volatile private var featureTxAt = 0L
    @Volatile private var featureAckAt = 0L
    @Volatile private var encoderTxAt = 0L
    @Volatile private var encoderAckAt = 0L
    @Volatile private var startTxAt = 0L
    @Volatile private var encoderCommands = 0L
    @Volatile private var firstFrameAt = 0L
    @Volatile private var totalFrames = 0L
    @Volatile private var current: VideoRead? = null
    private var lastReportAt = 0L

    inner class VideoRead(private val input: InputStream) : InputStream() {
        @Volatile var phase = "header"
        @Volatile var expectedBytes = 12
        @Volatile var phaseBytes = 0L
        @Volatile var wireBytes = 0L
        @Volatile var frames = 0L
        @Volatile var payloadBytes = 0L
        @Volatile var lastByteAt = 0L
        @Volatile var lastFrameAt = 0L

        fun phase(name: String, expected: Int) {
            phaseBytes = 0
            expectedBytes = expected
            phase = name
        }

        private fun received(count: Int) {
            if (count <= 0) return
            wireBytes += count
            phaseBytes += count
            lastByteAt = SystemClock.uptimeMillis()
        }

        override fun read(): Int = input.read().also { if (it >= 0) received(1) }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            input.read(bytes, offset, length).also(::received)

        fun frame(bytes: Int): Boolean {
            frames++
            payloadBytes += bytes
            lastFrameAt = SystemClock.uptimeMillis()
            if (current !== this) return false
            totalFrames++
            if (firstFrameAt != 0L) return false
            firstFrameAt = lastFrameAt
            return true
        }
    }

    fun video(input: InputStream): VideoRead = VideoRead(input).also { current = it }

    fun sent(type: Int) {
        val now = SystemClock.uptimeMillis()
        when (type) {
            CarLifeProtocol.HU_FEATURE_CONFIG -> featureTxAt = now
            CarLifeProtocol.HU_VIDEO_ENC_INIT -> { encoderTxAt = now; encoderCommands++ }
            CarLifeProtocol.VIDEO_ENC_START -> startTxAt = now
        }
    }

    fun acknowledged(type: Int) {
        val now = SystemClock.uptimeMillis()
        when (type) {
            CarLifeProtocol.MD_FEATURE_CONFIG_DONE -> featureAckAt = now
            CarLifeProtocol.MD_VIDEO_ENC_INIT -> encoderAckAt = now
        }
    }

    fun describe(): String {
        val now = SystemClock.uptimeMillis()
        fun age(at: Long) = if (at == 0L) -1 else now - at
        val video = current
        return "sourceAgeMs=${now - createdAt} featureTxAgeMs=${age(featureTxAt)} featureAckAgeMs=${age(featureAckAt)} " +
            "encoderTxAgeMs=${age(encoderTxAt)} encoderAckAgeMs=${age(encoderAckAt)} startTxAgeMs=${age(startTxAt)} " +
            "encoderCommands=$encoderCommands firstFrameAgeMs=${age(firstFrameAt)} sourceFrames=$totalFrames " +
            "socketPhase=${video?.phase ?: "not-open"} phaseBytes=${video?.phaseBytes ?: 0}/${video?.expectedBytes ?: 0} " +
            "socketWireBytes=${video?.wireBytes ?: 0} socketPayloadBytes=${video?.payloadBytes ?: 0} " +
            "socketFrames=${video?.frames ?: 0} byteAgeMs=${age(video?.lastByteAt ?: 0)} frameAgeMs=${age(video?.lastFrameAt ?: 0)}"
    }

    /** The existing supervisor calls this; no extra watcher or recovery action. */
    fun reportIfDue(log: (String) -> Unit, state: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastReportAt < 10_000) return
        lastReportAt = now
        log("CARLIFE-SOURCE: $state ${describe()}")
    }
}
