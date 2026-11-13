package io.github.shaonialife.carptocar.bt

import io.github.shaonialife.carptocar.transport.Iap2LinkChannel

/**
 * 入站会话门闸：车机主动连入的 iAP2 会话活跃期间，出站客户端暂停连接尝试。
 * 目的：车机通常只有单条无线 iAP2 上下文，双向同时建链会让双方互相干扰。
 */
object BtSessionGate {
    @Volatile
    private var controlOwner: Iap2LinkChannel? = null

    /** Inbound and outbound sockets share one vehicle control context. */
    @Synchronized
    fun claimControl(channel: Iap2LinkChannel, inbound: Boolean): Iap2LinkChannel? {
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

    /**
     * 最近一次**真正建链**的对端地址（入站或出站都算，null = 还没连上过）。
     *
     * 用途：音频配置守卫（[BtProfiles.guardAudioProfiles]）要知道「盯哪台设备」。
     * 用户指定了目标时直接用目标地址；没指定时（自动选车机）只能靠这里记下的
     * 实际对端——否则守卫会盯着一台没连上的设备空转。
     */
    @Volatile
    var activePeerAddress: String? = null

    fun notePeerActive(address: String?) {
        if (address != null) activePeerAddress = address
    }

    /**
     * iAP2 会话已跑通 bring-up（发送端会话就绪）。
     *
     * 与 [sessionActive]（CarPlay 端点拿到、画面能上）不同：这个更早，只表示车机
     * 已经认可这条控制链路。音频配置守卫（[BtProfiles.guardAudioProfiles]）
     * 就是看它来决定「还要不要继续断 HFP/A2DP」——宝马 fork 的结论是 iAP2 一建立
     * 车机就不再依赖配置文件状态，继续断只是徒增抖动。
     */
    @Volatile
    var iap2Ready: Boolean = false

    /**
     * CarPlay 会话已建立（任一路径成功拿到端点后置位，由 SessionController 维护）：
     * 暂停一切出站连接尝试（静默降噪）——典型表现为连着 DiPlay 时不再探测 Audi。
     * 会话结束/手动断开时由 SessionController 清除。
     */
    @Volatile
    var sessionActive: Boolean = false

    /**
     * 目标设备软回退：指定了目标车机但连续多轮连不上时置位。
     *
     * 场景：用户上次连的是 A 车（配了目标），这次坐的是 B 车 / 测试台。
     * 旧行为是**死等目标**——出站只试目标、入站把非目标全部掐掉，
     * 用户体感就是「点了启动连接，四十多秒毫无反应」（2026-10-25 日志实测
     * 17:48:47 启动 → 17:49:30 才连上）。置位后目标降级为「优先」：
     * 出站回退到全部已配对设备，入站也不再拒绝。
     *
     * 每次 `connect()` 重新开始都会清零，所以真车场景只要目标在窗口内可达，
     * 行为与旧版完全一致。
     */
    @Volatile
    var targetSoftFallback: Boolean = false

    /**
     * 「CarPlay 在跑、但我们自己的 iAP2 控制链路掉了」——掉线那一台的地址与时刻。
     *
     * 2026-11-04 奥迪 MIB3 实车（`docs/audi-mib3-ea-tunnel-20261104.md`）：BT iAP2 在连上
     * 约 45 秒后判 `packet 115 not acknowledged` 拆链，而此刻 CarPlay 画面/声音都在跑，
     * [sessionActive] 于是把出站连接整体静音 —— **整场会话再没人重连控制链路**。
     * 车机控制面板从头到尾「未连接车机」、歌名/媒体键也全没了，只有手动断开重连才好。
     *
     * 静音闸门的本意是「别去骚扰别的接收端」，不是「自己这条链断了也不管」。所以这里
     * 记下掉线的那台：出站循环在 CarPlay 期间**只对它**重连，连上即清；超过
     * [LINK_RECOVERY_WINDOW_MILLIS] 还没连回来就认输（打一行日志，回到静音）。
     */
    @Volatile
    var linkLostPeerAddress: String? = null

    /** 掉线时刻（uptimeMillis，0 = 没有待恢复的链路）。 */
    @Volatile
    var linkLostAt: Long = 0L

    /** 单条控制链路的恢复窗口：超时后不再拨（CarPlay 本身不受影响）。 */
    const val LINK_RECOVERY_WINDOW_MILLIS = 180_000L

    /**
     * 用户手动断开的时刻（uptimeMillis，0 = 本次进程还没手动断过）。
     *
     * 为什么要记（2026-11-04 奥迪 4910 / MIB3 4580 实车）：车机固件里
     * `mediaDisconnectTimeout = 15000ms`——蓝牙断开后要满 15 秒，它才把设备判成
     * 「已移除」。在那之前 `keepIAP2Session = 1`，车机只走
     * `IAP2 session already running. Waiting for required IAP2 information.` 这条分支：
     * **接受 RFCOMM、走完 iAP2 身份交换、下发 0x5703 热点凭据，但不启动 CarPlay
     * projection**——7000 端口不开、`_airplay._tcp` 不广播、0x4301 不回。
     *
     * 那一轮实录：16:22:32.519 手动断开 → 16:22:33.696（**1.2 秒**）重连 → 车机给了
     * 热点和凭据 → 之后 48 秒里 mDNS 一直是「未发现」，只能拿网关兜底，8 次重连全超时。
     * 而前一轮（车机主动连入、它自己启动的 projection）10 秒就连上了。
     *
     * 所以：手动断开后要给车机留足 [REACTIVATE_COOLDOWN_MILLIS] 再发起。
     */
    @Volatile
    var manualStopAt: Long = 0L

    /**
     * 手动断开后的重新发起冷却：**必须 > 车机的 mediaDisconnectTimeout（15s）**。
     * 取 18 秒留 3 秒余量。改小就等于把车机的移除计时器一次次续上，永远停在旧会话上。
     */
    const val REACTIVATE_COOLDOWN_MILLIS = 18_000L

    /** 记一次「用户手动断开」。 */
    fun noteManualStop() {
        manualStopAt = android.os.SystemClock.uptimeMillis()
    }

    /** 距上次手动断开多少毫秒；本次进程没手动断过返回 [Long.MAX_VALUE]。 */
    fun sinceManualStop(): Long {
        val at = manualStopAt
        if (at == 0L) return Long.MAX_VALUE
        return android.os.SystemClock.uptimeMillis() - at
    }

    /** 记一次「控制链路掉线」，交给出站循环单独重连。 */
    fun noteLinkLost(address: String?) {
        if (address == null) return
        linkLostPeerAddress = address
        linkLostAt = android.os.SystemClock.uptimeMillis()
    }

    /** 链路已恢复（或用户已停止）——清掉恢复任务。 */
    fun clearLinkLost() {
        linkLostPeerAddress = null
        linkLostAt = 0L
    }

    /**
     * 当前需要恢复的目标地址；null = 不需要（没有掉线 / CarPlay 已结束 / 已超窗）。
     *
     * CarPlay 会话结束时 [sessionActive] 会被 SessionController 清掉，这里跟着失效。
     */
    fun linkRecoveryTarget(): String? {
        if (!sessionActive) return null
        val address = linkLostPeerAddress ?: return null
        val at = linkLostAt
        if (at == 0L) return null
        if (android.os.SystemClock.uptimeMillis() - at > LINK_RECOVERY_WINDOW_MILLIS) return null
        return address
    }
}
