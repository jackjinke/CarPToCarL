package io.github.shaonialife.carptocar.iap2

import android.content.Context
import java.security.SecureRandom

/**
 * 单一虚拟 iAP2 链路周期内的 MFi 认证状态机。
 *
 *  - 与蓝牙链路共享同一车机身份键（不加私有后缀），直接复用蓝牙阶段已验证通过的挑战长度；
 *  - 解析 `0xAA01` 证书中的 ANSI X9.62 `id-ecPublicKey`（ECDSA P-256）OID：AuthV3 芯片冷启动优先发 `32B` 挑战，
 *    传统 RSA 证书保持 `20B` 优先，兼顾宝马 iD8/MGU 与奥迪老款 MMI；
 *  - 挑战发出后若对端直接拆链/重发 DETECT，按 [MfiLengthMemory.recordHardRejected] 记录硬拒绝，避免错误长度锁死。
 */
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

    /** 挑战等待应答期间链路被对端重置或关闭：将该长度记为硬拒绝，推翻可能误记的旧结论。 */
    fun onLinkInterrupted() {
        val id = identity
        if (stage == Stage.CHALLENGE && !onTunnel && id != null && currentLength > 0) {
            MfiLengthMemory.recordHardRejected(context, id, currentLength)
            log("IAPLINK: MFi ${currentLength}B 挑战期间链路中断——记入硬拒绝记忆")
        }
        reset()
    }

    fun begin(
        identity: String,
        onTunnel: Boolean,
        sendsAuthSerial: Boolean,
    ) {
        reset()
        this.identity = identity
        this.onTunnel = onTunnel
        stage = Stage.CERTIFICATE
        // 声明了 0xAA06 的车机（如宝马 iD8/MGU WLAN）首帧 0xAA00 需携带空参数 0 先取序列号，随后再拉完整证书。
        val body = if (sendsAuthSerial) Iap2Csm.param(0, ByteArray(0)) else ByteArray(0)
        send(Iap2MessageIds.MFI_REQUEST_CERTIFICATE, body)
        log("IAPLINK: 发起认证请求（${if (sendsAuthSerial) "先序列号后证书" else "直接拉取证书"}）")
    }

    fun onFrame(frame: Iap2Csm.Frame) {
        when (frame.messageId) {
            Iap2MessageIds.MFI_AUTH_SERIAL_NUMBER -> {
                if (stage != Stage.CERTIFICATE || fullCertificateRequested) return
                fullCertificateRequested = true
                send(Iap2MessageIds.MFI_REQUEST_CERTIFICATE, ByteArray(0))
                log("IAPLINK: 收到 0xaa06 序列号 → 请求完整证书 0xaa00")
            }

            Iap2MessageIds.MFI_CERTIFICATE -> {
                if (stage != Stage.CERTIFICATE) return
                val cert = certificateBytes(frame.body)
                if (cert == null) {
                    log("IAPLINK: 忽略空载荷 0xaa01，继续等待有效证书")
                    return
                }
                if (onTunnel) {
                    log("IAPLINK: 收到隧道证书 → 发送 32B 挑战")
                    order = listOf(32)
                } else {
                    val id = identity ?: return
                    val ecdsa = containsEcdsaPublicKeyOid(cert)
                    val baseline = if (ecdsa) ECDSA_BASELINE else BASELINE
                    memory = MfiLengthMemory.load(context, id)
                    order = MfiLengthMemory.orderAttempts(baseline, memory)
                    log(
                        "IAPLINK: 收到证书 ${cert.size}B（公钥类型=${if (ecdsa) "ECDSA-P256" else "RSA/其他"}，" +
                            "记忆=${MfiLengthMemory.describe(memory)}）→ 挑战顺序 ${order.joinToString("/")}B",
                    )
                }
                nextChallenge()
            }

            Iap2MessageIds.MFI_AUTHENTICATION_FAILED -> {
                if (stage != Stage.CHALLENGE) return
                if (!onTunnel) MfiLengthMemory.recordRejected(context, identity!!, currentLength)
                log("IAPLINK: MFi ${currentLength}B 被拒（0xaa04）——换下一档")
                nextChallenge()
            }

            Iap2MessageIds.MFI_RESPONSE -> {
                if (stage != Stage.CHALLENGE) return
                if (certificateBytes(frame.body) == null) {
                    log("IAPLINK: 忽略空载荷 0xaa03，继续等待有效签名")
                    return
                }
                if (!onTunnel) MfiLengthMemory.recordSuccess(context, identity!!, currentLength)
                log("IAPLINK: 收到 0xaa03 签名 ${frame.body.size}B（${currentLength}B 挑战完成）")
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
        log("IAPLINK: MFi ${currentLength}B 静默 ${windowMillis}ms——记入记忆，换下一档")
        nextChallenge()
    }

    private fun nextChallenge() {
        position++
        if (position >= order.size) {
            currentLength = 0
            stage = Stage.EXHAUSTED
            log("IAPLINK: MFi 挑战档位全部耗尽，保持未完成态（不伪造 0xaa05）")
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
        log("IAPLINK: tx=0xaa02 challenge=${currentLength}B（第 ${position + 1} 次，窗口 ${windowMillis}ms）")
    }

    private fun certificateBytes(body: ByteArray): ByteArray? =
        Iap2Csm.parseParams(body).firstOrNull { it.id == 0 && it.value.isNotEmpty() }?.value

    companion object {
        val BASELINE = listOf(20, 32, 16)
        val ECDSA_BASELINE = listOf(32, 20, 16)
        val WINDOWS = mapOf(20 to 6_000L, 32 to 3_000L, 16 to 3_000L)
        const val SILENT_WINDOW_MS = 1_500L

        /** ANSI X9.62 `id-ecPublicKey` (1.2.840.10045.2.1) DER 编码：`06 07 2A 86 48 CE 3D 02 01`。 */
        private val ECDSA_PUBLIC_KEY_OID =
            byteArrayOf(
                0x06,
                0x07,
                0x2A,
                0x86.toByte(),
                0x48,
                0xCE.toByte(),
                0x3D,
                0x02,
                0x01,
            )

        internal fun containsEcdsaPublicKeyOid(der: ByteArray): Boolean {
            if (der.size < ECDSA_PUBLIC_KEY_OID.size) return false
            val limit = der.size - ECDSA_PUBLIC_KEY_OID.size
            outer@ for (i in 0..limit) {
                for (j in ECDSA_PUBLIC_KEY_OID.indices) {
                    if (der[i + j] != ECDSA_PUBLIC_KEY_OID[j]) continue@outer
                }
                return true
            }
            return false
        }
    }
}
