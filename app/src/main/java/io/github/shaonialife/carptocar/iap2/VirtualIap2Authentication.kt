package io.github.shaonialife.carptocar.iap2

import android.content.Context
import java.security.SecureRandom

/** Authentication state belongs to one link epoch, never to the enclosing AirPlay connection. */
internal class VirtualIap2Authentication(
    private val context: Context?,
    private val nowMillis: () -> Long,
    private val send: (Int, ByteArray) -> Unit,
    private val onCompleted: () -> Unit,
    private val log: (String) -> Unit,
) {
    private enum class Stage { IDLE, CERTIFICATE, CHALLENGE, COMPLETE, EXHAUSTED }

    private var stage = Stage.IDLE
    private var identity: String? = null
    private var onTunnel = false
    private var fullCertificateRequested = false
    private var memory = MfiLengthMemory.Entry()
    private var order: List<Int> = emptyList()
    private var position = -1
    private var currentLength = 0
    private var challengeSentAtMs = 0L
    private var windowMillis = 0L
    private val random = SecureRandom()

    val finished: Boolean get() = stage == Stage.COMPLETE
    val diagnosticPhase: String
        get() = when (stage) {
            Stage.IDLE -> "WAIT_IDENTIFICATION"
            Stage.CERTIFICATE -> if (fullCertificateRequested) "WAIT_CERTIFICATE" else "WAIT_SERIAL_OR_CERTIFICATE"
            Stage.CHALLENGE -> "WAIT_CHALLENGE_RESPONSE"
            Stage.COMPLETE -> "RESPONSE_RECEIVED_UNVERIFIED"
            Stage.EXHAUSTED -> "EXHAUSTED"
        }

    fun reset() {
        stage = Stage.IDLE
        identity = null
        fullCertificateRequested = false
        memory = MfiLengthMemory.Entry()
        order = emptyList()
        position = -1
        currentLength = 0
        challengeSentAtMs = 0L
        windowMillis = 0L
    }

    fun begin(identity: String, onTunnel: Boolean, sendsAuthSerial: Boolean) {
        reset()
        this.identity = identity
        this.onTunnel = onTunnel
        stage = Stage.CERTIFICATE
        // BMW MGU93 WLAN: 20B first request, then 16B after AA06. The Bluetooth
        // sibling prints the otherwise truncated WLAN tail: an empty parameter 0.
        val body = if (sendsAuthSerial) Iap2Csm.param(0, ByteArray(0)) else ByteArray(0)
        send(Iap2MessageIds.MFI_REQUEST_CERTIFICATE, body)
        log("IAPLINK: authentication request=${if (sendsAuthSerial) "serial-first" else "certificate-direct"}; awaiting peer certificate (RTSP 200 is not authentication)")
    }

    fun onFrame(frame: Iap2Csm.Frame) {
        when (frame.messageId) {
            Iap2MessageIds.MFI_AUTH_SERIAL_NUMBER -> {
                if (stage != Stage.CERTIFICATE || fullCertificateRequested) return
                fullCertificateRequested = true
                send(Iap2MessageIds.MFI_REQUEST_CERTIFICATE, ByteArray(0))
                log("IAPLINK: received 0xaa06; requested full certificate")
            }

            Iap2MessageIds.MFI_CERTIFICATE -> {
                if (stage != Stage.CERTIFICATE) return
                if (!hasValue(frame.body)) {
                    log("IAPLINK: ignored empty 0xaa01; authentication remains incomplete")
                    return
                }
                if (onTunnel) {
                    log("IAPLINK: received certificate; requesting 32B tunnel challenge")
                    order = listOf(32)
                } else {
                    memory = MfiLengthMemory.load(context, identity!!)
                    order = MfiLengthMemory.orderAttempts(BASELINE, memory)
                }
                nextChallenge()
            }

            Iap2MessageIds.MFI_AUTHENTICATION_FAILED -> {
                if (stage != Stage.CHALLENGE) return
                if (!onTunnel) MfiLengthMemory.recordRejected(context, identity!!, currentLength)
                nextChallenge()
            }

            Iap2MessageIds.MFI_RESPONSE -> {
                if (stage != Stage.CHALLENGE) return
                if (!hasValue(frame.body)) {
                    log("IAPLINK: ignored empty 0xaa03; outstanding challenge remains incomplete")
                    return
                }
                // Signature verification is not implemented here (same as the Bluetooth path).
                // Require an actual response to an outstanding challenge; silence is never success.
                if (!onTunnel) MfiLengthMemory.recordSuccess(context, identity!!, currentLength)
                log("IAPLINK: rx=0xaa03 signature ${frame.body.size}B for ${currentLength}B challenge (not cryptographically verified)")
                currentLength = 0
                stage = Stage.COMPLETE
                send(Iap2MessageIds.MFI_AUTHENTICATION_SUCCEEDED, ByteArray(0))
                onCompleted()
            }
        }
    }

    fun advanceTime() {
        if (stage != Stage.CHALLENGE || onTunnel) return
        if (nowMillis() - challengeSentAtMs < windowMillis) return
        MfiLengthMemory.recordSilent(context, identity!!, currentLength)
        log("IAPLINK: MFi ${currentLength}B silent for ${windowMillis}ms; trying next length")
        nextChallenge()
    }

    private fun nextChallenge() {
        position++
        if (position >= order.size) {
            currentLength = 0
            stage = Stage.EXHAUSTED
            log("IAPLINK: MFi attempts exhausted; authentication remains incomplete (no 0xaa05)")
            return
        }
        currentLength = order[position]
        val base = WINDOWS[currentLength] ?: SILENT_WINDOW_MS
        windowMillis =
            if (currentLength in memory.silent && currentLength != memory.succeeded) {
                minOf(base, SILENT_WINDOW_MS)
            } else {
                base
            }
        stage = Stage.CHALLENGE
        challengeSentAtMs = nowMillis()
        val challenge = ByteArray(currentLength).also(random::nextBytes)
        send(Iap2MessageIds.MFI_REQUEST_CHALLENGE, Iap2Csm.param(0, challenge))
        log("IAPLINK: tx=0xaa02 challenge=${currentLength}B attempt=${position + 1} window=${windowMillis}ms")
    }

    private fun hasValue(body: ByteArray): Boolean =
        Iap2Csm.parseParams(body).any { it.id == 0 && it.value.isNotEmpty() }

    companion object {
        val BASELINE = listOf(20, 32, 16)
        val WINDOWS = mapOf(20 to 6_000L, 32 to 3_000L, 16 to 3_000L)
        const val SILENT_WINDOW_MS = 1_500L
    }
}
