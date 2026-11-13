package io.github.shaonialife.carptocar.bt

import io.github.shaonialife.carptocar.transport.Iap2LinkChannel

/**
 * 蓝牙无线 iAP2 会话门闸：协调入站监听与出站重连客户端对单一车机控制上下文的互斥访问。
 */
object BtSessionGate {
    private val clockBaseNano = System.nanoTime()

    /** 单调时钟（毫秒，恒 > 0；单测可注入）。 */
    internal var nowMillis: () -> Long = { ((System.nanoTime() - clockBaseNano) / 1_000_000L) + 1L }

    @Volatile
    private var controlOwner: Iap2LinkChannel? = null

    /** 入站与出站共享同一车机控制上下文，后建立的通道接管所有权并返回旧通道供调用方退役。 */
    @Synchronized
    fun claimControl(
        channel: Iap2LinkChannel,
        inbound: Boolean,
    ): Iap2LinkChannel? {
        val previous = controlOwner
        iap2Ready = false
        inboundActive = inbound
        controlOwner = channel
        return previous
    }

    fun ownsControl(channel: Iap2LinkChannel): Boolean = controlOwner === channel

    @Synchronized
    fun markControlReady(channel: Iap2LinkChannel) {
        if (controlOwner === channel) iap2Ready = true
    }

    @Synchronized
    fun releaseControl(channel: Iap2LinkChannel): Boolean {
        if (controlOwner !== channel) return false
        controlOwner = null
        iap2Ready = false
        inboundActive = false
        return true
    }

    /** 入站（车机主动连入）的 iAP2 会话活跃：出站暂停，避免两条 RFCOMM 互相踩。 */
    @Volatile
    var inboundActive: Boolean = false

    /** 最近一次真正建链的对端蓝牙地址。 */
    @Volatile
    var activePeerAddress: String? = null

    fun notePeerActive(address: String?) {
        if (address != null) activePeerAddress = address
    }

    /** iAP2 会话已跑通 bring-up（发送端会话就绪）。 */
    @Volatile
    var iap2Ready: Boolean = false

    /** CarPlay 会话已建立：暂停对其它接收端的出站探测。 */
    @Volatile
    var sessionActive: Boolean = false

    /** 目标设备软回退：指定目标车机连续多轮连不上时降级为优先尝试。 */
    @Volatile
    var targetSoftFallback: Boolean = false

    /** CarPlay 推流期间控制链路掉线的对端地址（出站循环仅对该地址单独补连）。 */
    @Volatile
    var linkLostPeerAddress: String? = null

    /** 掉线时刻（[nowMillis] 时钟，0 = 无待恢复链路）。 */
    @Volatile
    var linkLostAt: Long = 0L

    /** 单条控制链路的恢复窗口。 */
    const val LINK_RECOVERY_WINDOW_MILLIS = 180_000L

    /** 用户手动断开的时刻（[nowMillis] 时钟，0 = 尚未手动断过）。 */
    @Volatile
    var manualStopAt: Long = 0L

    /** 手动断开后的重新发起冷却（需大于车机 `mediaDisconnectTimeout = 15s`）。 */
    const val REACTIVATE_COOLDOWN_MILLIS = 18_000L

    /**
     * 对端（车机 UI）主动结束会话后的被动等待窗口：
     * 车机侧断开再点重连时由车机主动回连本机 `…cafe` 监听，此窗口内暂停本机出站拨号以避免双向 RFCOMM 碰撞。
     */
    const val PASSIVE_RECONNECT_GRACE_MILLIS = 15_000L

    @Volatile
    var remoteDisconnectAt: Long = 0L

    /** 记一次「用户手动断开」。 */
    fun noteManualStop() {
        manualStopAt = nowMillis()
    }

    /** 距上次手动断开多少毫秒；未手动断过返回 [Long.MAX_VALUE]。 */
    fun sinceManualStop(): Long {
        val at = manualStopAt
        if (at == 0L) return Long.MAX_VALUE
        return (nowMillis() - at).coerceAtLeast(0L)
    }

    /** 记一次「对端主动结束已建立的会话」，开启被动回连避让窗口。 */
    fun noteRemoteSessionEnded() {
        remoteDisconnectAt = nowMillis()
    }

    /** 用户手动点击「启动连接」时清除被动避让窗口，确保首次主动拨号零延迟。 */
    fun clearRemoteDisconnectGrace() {
        remoteDisconnectAt = 0L
    }

    /** 被动回连避让窗口剩余毫秒数（0 = 无需避让）。 */
    fun passiveGraceRemainingMillis(): Long {
        val at = remoteDisconnectAt
        if (at == 0L) return 0L
        return (PASSIVE_RECONNECT_GRACE_MILLIS - (nowMillis() - at)).coerceAtLeast(0L)
    }

    /** 记一次「控制链路掉线」，交给出站循环单独重连。 */
    fun noteLinkLost(address: String?) {
        if (address == null) return
        linkLostPeerAddress = address
        linkLostAt = nowMillis()
    }

    /** 链路已恢复或会话已结束——清掉恢复任务。 */
    fun clearLinkLost() {
        linkLostPeerAddress = null
        linkLostAt = 0L
    }

    /** 当前需要恢复的控制链路目标地址；null = 不需要。 */
    fun linkRecoveryTarget(): String? {
        if (!sessionActive) return null
        val address = linkLostPeerAddress ?: return null
        val at = linkLostAt
        if (at == 0L) return null
        if (nowMillis() - at > LINK_RECOVERY_WINDOW_MILLIS) return null
        return address
    }
}
