package io.github.shaonialife.carptocar.net

/**
 * 车机 `_carplay-ctrl._tcp` 连接邀请门闸：一次邀请仅授权同网段、同接收端的一次会话激活，不跨轮次无限重放。
 */
class CarPlayInvitationGate(
    private val lifetimeMillis: Long = 30_000L,
) {
    data class Invitation(
        val serial: Long,
        val receiverId: String?,
        val peerHost: String,
        val localHost: String,
        val networkHandle: Long?,
        val receivedAtMillis: Long,
    )

    private var pending: Invitation? = null
    private var latestSerial = 0L

    @Synchronized
    fun offer(invitation: Invitation) {
        if (invitation.serial <= latestSerial) return
        latestSerial = invitation.serial
        pending = invitation
    }

    @Synchronized
    fun discard(invitation: Invitation) {
        if (invitation.serial < latestSerial) return
        latestSerial = invitation.serial
        pending = null
    }

    @Synchronized
    fun clear() {
        pending = null
    }

    /** 入网绑定可能晚于邀请到达，消费时按当前实际绑定网络与本机/对端地址核验。 */
    @Synchronized
    fun consume(
        receiverId: String?,
        peerHosts: List<String>,
        localHosts: List<String>,
        networkHandle: Long?,
        nowMillis: Long,
    ): Invitation? {
        val invitation = pending ?: return null
        val age = nowMillis - invitation.receivedAtMillis
        if (age !in 0..lifetimeMillis) {
            pending = null
            return null
        }
        if (networkHandle == null || localHosts.isEmpty()) return null
        pending = null
        if (invitation.networkHandle != null && invitation.networkHandle != networkHandle) return null
        if (localHosts.none { address(it) == address(invitation.localHost) }) return null
        if (peerHosts.none { address(it) == address(invitation.peerHost) }) return null
        if (!receiverId.isNullOrBlank() &&
            !invitation.receiverId.isNullOrBlank() &&
            identity(receiverId) != identity(invitation.receiverId)
        ) {
            return null
        }
        return invitation
    }

    private fun address(value: String): String = EndpointHosts.stripScope(value).lowercase()

    private fun identity(value: String): String {
        val text = value.trim()
        return if (text.all { it.isDigit() }) {
            text.toULongOrNull()?.toString(16)?.padStart(12, '0') ?: text
        } else {
            text.replace(":", "").replace("-", "").lowercase()
        }
    }
}
