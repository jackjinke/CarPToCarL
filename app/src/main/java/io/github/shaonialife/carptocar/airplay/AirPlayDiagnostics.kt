package io.github.shaonialife.carptocar.airplay

import java.util.concurrent.atomic.AtomicInteger

/** Metadata only: never retain payloads, identities, keys or media frames. */
internal class AirPlayDiagnostics {
    enum class Signal {
        CONTROL_TX, CONTROL_RX, EVENT_TX, EVENT_RX, FEEDBACK, KEEPALIVE, IAP_RX,
    }

    val id: Int = nextId.incrementAndGet()
    private val createdAt = now()
    private var openedAt = 0L
    private val counts = LongArray(Signal.entries.size)
    private val lastAt = LongArray(Signal.entries.size)
    private var keepAliveMaxGapMs = 0L
    private var lastMethod = "-"
    private var lastPath = "-"
    private var lastCseq = 0
    private var lastControlStatus = "-"
    private var lastEventType = "-"
    private var lastFeedbackStatus = "-"

    @Synchronized
    fun opened() {
        openedAt = now()
    }

    @Synchronized
    fun controlSent(method: String, path: String, cseq: Int) {
        lastMethod = method
        lastPath = path
        lastCseq = cseq
        mark(Signal.CONTROL_TX)
    }

    @Synchronized
    fun controlReceived(status: String) {
        lastControlStatus = status
        mark(Signal.CONTROL_RX)
    }

    @Synchronized
    fun eventType(type: String) {
        lastEventType = type
    }

    @Synchronized
    fun feedback(status: String) {
        lastFeedbackStatus = status
        mark(Signal.FEEDBACK)
    }

    @Synchronized
    fun mark(signal: Signal) {
        val at = now()
        val index = signal.ordinal
        if (signal == Signal.KEEPALIVE && counts[index] != 0L) {
            keepAliveMaxGapMs = maxOf(keepAliveMaxGapMs, at - lastAt[index])
        }
        counts[index]++
        lastAt[index] = at
    }

    @Synchronized
    fun summary(): String {
        val at = now()
        return buildString {
            append("ap=$id clientAgeMs=${at - createdAt} transportAgeMs=")
            append(if (openedAt == 0L) "never" else (at - openedAt).toString())
            append(" counts=client-lifetime")
            for (signal in Signal.entries) {
                val index = signal.ordinal
                append(" ${signal.name}=${counts[index]}/ageMs=")
                append(if (counts[index] == 0L) "never" else (at - lastAt[index]).toString())
            }
            append(" keepAliveMaxGapMs=$keepAliveMaxGapMs")
            append(" lastTx=$lastCseq:$lastMethod:$lastPath lastRx=$lastControlStatus")
            append(" lastEvent=$lastEventType lastFeedback=$lastFeedbackStatus")
        }
    }

    private companion object {
        val nextId = AtomicInteger()
        fun now(): Long = System.nanoTime() / 1_000_000L
    }
}
