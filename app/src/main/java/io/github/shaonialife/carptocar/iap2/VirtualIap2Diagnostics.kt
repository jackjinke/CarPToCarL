package io.github.shaonialife.carptocar.iap2

/** Wire metadata only: RTSP 200, link ACK, authentication and media are separate observations. */
internal class VirtualIap2Diagnostics(
    private val engine: Iap2LinkEngine,
    private val nowMillis: () -> Long,
    private val transport: () -> String,
    private val authenticationPhase: () -> String,
    private val log: (String) -> Unit,
) {
    private var received = 0L
    private var sent = 0L
    private var lastReceiveAt: Long? = null
    private var lastLinkPhase: Iap2LinkEngine.State? = null
    private var lastAuthenticationPhase: String? = null
    private var nextWaitAt = 0L
    private var eventInputBytes = 0L
    private var controlReplyInputBytes = 0L
    private var lastInputAt: Long? = null

    fun onInput(size: Int, fromControlReply: Boolean) {
        if (fromControlReply) controlReplyInputBytes += size else eventInputBytes += size
        if (size > 0) lastInputAt = nowMillis()
    }

    init {
        engine.onPeerSynchronizationAdopted = { peer ->
            log(
                "IAPLINK-DIAG: negotiated transport=${transport()} " +
                    "sessions=${peer.sessions.joinToString { "${it.id}/${it.kind}/${it.version}" }} " +
                    "peerWindow=${peer.maxOutgoing} localWindow=${engine.declaredSendWindow} " +
                    "maxLength=${peer.maxLength} retryMs=${peer.retransmissionTimeoutMillis} " +
                    "maxRetries=${peer.maxRetransmissions} ackMs=${peer.acknowledgementTimeoutMillis} " +
                    "maxCumAck=${peer.maxAcknowledgements}",
            )
        }
        engine.onFrameReceived = { control, sequence, ack, session, size ->
            received++
            lastReceiveAt = nowMillis()
            if (received <= 24 || received % 100L == 0L) {
                trace("rx", received, control, sequence, ack, session, size)
            }
        }
        engine.onFrameSent = { control, sequence, ack, session, size ->
            sent++
            if (sent <= 24 || sent % 100L == 0L) {
                trace("tx", sent, control, sequence, ack, session, size)
            }
        }
        engine.onUnknownSessionData = { session, size ->
            log("IAPLINK-DIAG: ignored session=$session bytes=$size ${summary()}")
        }
        engine.onSendWindowHold = { peer, effective, queued ->
            log("IAPLINK-DIAG: waiting peer ACK peerWindow=$peer effectiveWindow=$effective queued=$queued ${summary()}")
        }
        engine.onSendWindowReleased = { effective, elapsed ->
            log("IAPLINK-DIAG: peer ACK released window=$effective waitMs=$elapsed")
        }
    }

    fun notePhase() {
        val linkPhase = engine.state
        val authPhase = authenticationPhase()
        val now = nowMillis()
        val changed = linkPhase != lastLinkPhase || authPhase != lastAuthenticationPhase
        if (!changed && now < nextWaitAt) return
        lastLinkPhase = linkPhase
        lastAuthenticationPhase = authPhase
        nextWaitAt = now + 10_000L
        log("IAPLINK-DIAG: ${if (changed) "phase" else "status"} ${summary()} receiverActivation=unconfirmed")
    }

    fun summary(): String =
        "transport=${transport()} link=${engine.state} auth=${authenticationPhase()} " +
            "rxFrames=$received txFrames=$sent " +
            "eventIapBytes=$eventInputBytes controlReplyIapBytes=$controlReplyInputBytes " +
            "lastInputAgeMs=${lastInputAt?.let { nowMillis() - it } ?: -1L} " +
            "lastLinkFrameAgeMs=${lastReceiveAt?.let { nowMillis() - it } ?: -1L} " +
            "queued=${engine.queuedControlPackets} outstanding=${engine.outstandingPackets} " +
            "peerAck=${engine.acknowledgedSequence} lastTx=${engine.lastSentSequence}"

    private fun trace(direction: String, count: Long, control: Int, sequence: Int, ack: Int, session: Int, size: Int) {
        log("IAPLINK-DIAG: $direction#$count ctl=0x${control.toString(16)} seq=$sequence ack=$ack session=$session bytes=$size")
    }
}
