package io.github.shaonialife.carptocar.airplay

import android.content.Context
import io.github.shaonialife.carptocar.iap2.AccessoryDeclaration
import io.github.shaonialife.carptocar.iap2.CarPlayNowPlaying
import io.github.shaonialife.carptocar.iap2.Iap2CapabilityReport
import io.github.shaonialife.carptocar.iap2.Iap2Csm
import io.github.shaonialife.carptocar.iap2.Iap2LinkEngine
import io.github.shaonialife.carptocar.iap2.Iap2MessageIds
import io.github.shaonialife.carptocar.iap2.Iap2NowPlaying
import io.github.shaonialife.carptocar.iap2.Iap2SubscriptionReplies
import io.github.shaonialife.carptocar.iap2.VirtualIap2Authentication
import io.github.shaonialife.carptocar.iap2.VirtualIap2DeviceState
import io.github.shaonialife.carptocar.iap2.VirtualIap2Diagnostics
import io.github.shaonialife.carptocar.iap2.VirtualIap2Epochs
import io.github.shaonialife.carptocar.iap2.NmeaLocation
import io.github.shaonialife.carptocar.input.ConsumerKeys
import io.github.shaonialife.carptocar.util.SessionTrace

/**
 * 事件通道上的虚拟 iAP2 链路（AirPlay 会话之上的「WiFi 隧道」），一条实现服务三种形态：
 *
 *  - **DiPlay（type-130 隧道）**：出站写隧道 TCP；链路协商用「回显先行」语义
 *    （对端引擎硬性期望，见 [Iap2LinkEngine.echoFirst]）；
 *  - **Audi MIB3（事件通道 + 控制通道 POST）**：真机时序（AADisplay 以 Frida 挂
 *    越狱 iPhone mediaserverd 抓包确认）——**屏幕流 SETUP 成功后由手机主动发一次
 *    DETECT（经控制通道 POST /command 的 iAPSendMessage）→ 车机回 SYN（事件通道）
 *    → 手机回 SYN|ACK（控制通道）**，随后控制会话整段重跑：0x1D00 → 0x1D01 →
 *    0x1D02+0xAA00 →（0xAA06 → 0xAA00）→ 0xAA02 挑战 20B → 0xAA03 → 0xAA05 →
 *    手机突发消息 → 应答车机订阅。**只回显空 ACK 的话 MIB3 约 10~12 秒整链拆除**
 *    （本仓库 2026-10-19 两轮实车日志 10.2s 复现，此前误判为心跳缺失）；
 *  - **旧事件形态 / 明文会话**：marker 一问一答回显（保持历史行为）。
 *
 * 车机→手机的链路字节统一从事件通道 iAPSendMessage 进来（[onCarBytes]）；
 * 手机→车机按形态分流（隧道 TCP / 控制通道 POST <-- 默认）。**方向不能对调**：
 * 出站写在事件通道上车机不读（Frida 结论；2026-10-20 Honda HFT 实测复现——
 * 出站改到事件通道后，车机 20 分钟内一条 FF5A 同步包都没回，只按 1Hz 重发 marker）。
 */
class VirtualIap2Link(
    private val log: (String) -> Unit,
    /** 事件通道 iAPSendMessage 出站（旧形态兜底）。 */
    private val sendEventIap: (ByteArray) -> Boolean,
    /** Queued control output carries a lock-free predicate, also checked before delivering replies. */
    private val sendControlIap: (ByteArray, () -> Boolean) -> Unit,
    /** DiPlay type-130 隧道写入口（每次出站动态取用，可为 null）。 */
    private val tunnelWriter: () -> AirPlayClient.IapTunnelClient?,
    /** 节拍线程存活条件（宿主控制连接未关）。 */
    private val alive: () -> Boolean,
    /** MFi 长度记忆的持久化上下文；null = 进程内记忆（单测）。 */
    private val mfiContext: Context? = null,
    /** 时钟（单测可注入）。 */
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    /**
     * 「正在播放」信息提供者（歌名/歌手/专辑/播放状态）。
     *
     * 0x5000（StartNowPlayingUpdates）订阅到达时调用：返回非 null 就发带真实歌名的
     * 0x5001 NowPlayingUpdate，否则退化为空态（车机存活前置）。由 SessionController
     * 注入（数据源 = CarLife 内置播放器经 0x10035 推来的歌名，见 CarLifeHijack）。
     */
    private val nowPlayingProvider: (() -> CarPlayNowPlaying?)? = null,
    /**
     * 当前通话状态提供者（iAP2 `CallState`：0=无通话、1=来电、2=拨出、3=通话中）。
     *
     * 0x4154（StartCallStateUpdates）订阅到达时用它回**真实值**。恒回 0 的后果不是
     * 「少一个功能」而是「车机认定这台手机永远不打电话」——通话时它不会切通话 UI，
     * 也不会把音频通路让给 telephony 档（2026-10-29 通话适配）。
     *
     * 由 SessionController 注入（数据源 = `AudioManager.mode`，见 `callStateOf`）。
     */
    private val callStateProvider: (() -> Int)? = null,
) {
    private val lock = Any()
    private val epochs = VirtualIap2Epochs()
    private val engine = Iap2LinkEngine(reliableTransport = true, declaredWindow = 32)
    private var parser = Iap2Csm.Parser()
    private var frameCount = 0
    private var started = false

    // ---- DiPlay 隧道路径状态 ----
    private var tunnelCsmStarted = false
    private var tunnelIdentified = false

    /** 同步回显待投递（隧道门闸：回显真正写进隧道 TCP 后才允许发 0x1D00）。 */
    private var pendingEchoFlush = false
    private var tunnelEchoDelivered = false

    // ---- MIB3 路径状态 ----
    private var mib3Armed = false
    private var mib3SynAckQueued = false
    private var mib3CsmStarted = false
    private var mib3Identified = false
    private var peerDetectCount = 0
    private var lastDetectResponseAtMs: Long? = null

    // ---- 0x5000 订阅后的周期补推（歌名晚于订阅到达时唯一能自愈的路径）----
    /** 车机是否订阅过 0x5000；没订阅不主动推 0x5001，避免改变既有会话形态。 */
    private var nowPlayingSubscribed = false

    /** 上次实际发出的 0x5001 内容键；订阅应答 / 事件补推 / 周期补推三条路径共用。 */
    private var lastPushedNowPlayingKey: String? = null

    /** 上次周期查询时刻（[nowMillis] 时钟）；0 = 还没查过。 */
    private var lastNowPlayingPollMs = 0L

    /** Honda fallback: repeated event-channel DETECT without SYN; output stays on control. */
    private var eventLinkArmed = false
    private var eventCsmStarted = false

    /**
     * Control negotiation is bounded to [MAX_CONTROL_SYN_ROUNDS] rounds, each lasting at most
     * [CONTROL_SYN_ROUND_MILLIS]. A later peer SYN can still make progress after retries stop.
     */
    private var controlSynRounds = 0
    private var controlSynRoundStartedAtMs = 0L
    private var controlSynGaveUp = false

    /** 未处理的控制会话消息计数（媒体键排查的观测点，见 [maybeRespondControlCsmLocked]）。 */
    private var unknownCsmIds = 0

    /** 上一次记录过的链路状态（只在对日志里出现一次状态迁移）。 */
    private var lastLinkState = Iap2LinkEngine.State.IDLE

    // ---- 车机 HID 媒体键（0x6802）状态 ----
    /** 上一次报文里按下的 Usage 集合（用于判定「新按下」，松开后自动清空）。 */
    private var lastHidUsages: Set<Int> = emptySet()

    /** 当前这批按键的起始时刻（连续同一按键超过 [HID_HOLD_REPEAT_MILLIS] 才允许连发）。 */
    private var hidHoldStartedAtMs = 0L

    /** 已打印的参数明细帧数（只打前几帧，之后按需打动作）。 */
    private var hidReportFrames = 0

    private val deviceState = VirtualIap2DeviceState(::sendCsmLocked, log)
    private val authentication =
        VirtualIap2Authentication(
            mfiContext,
            nowMillis,
            ::sendCsmLocked,
            {
                if (tunnelWriter() == null) deviceState.sendGreetings()
            },
            log,
        )
    private val diagnostics = VirtualIap2Diagnostics(
        engine, nowMillis, { if (tunnelWriter() == null) "event-in/control-out" else "event-in/type130-out" },
        { authentication.diagnosticPhase }, log,
    )

    /** Link negotiation plus an actual response to the current authentication challenge.
     * This does not prove that the receiver activated its UI or rendered media. */
    val controlSessionReady: Boolean
        get() = synchronized(lock) { epochs.open && engine.state == Iap2LinkEngine.State.NORMAL && authentication.finished }
    fun diagnosticSummary(): String = synchronized(lock) { diagnostics.summary() }

    init {
        engine.onSynchronizationEchoSent = { pendingEchoFlush = true }
        engine.onPeerDetect = { handlePeerDetectLocked() }
        // 非 DETECT 的握手应答（车机侧 sendLackOfBackwardCompatibility 就在这一族）：
        // 旧实现把它当杂字节丢掉，日志里只剩「协商无进展」，只能等预算耗尽。
        engine.onPeerHandshake = { handshake ->
            log(
                "IAPLINK: 对端握手应答（非 DETECT）raw=${handshake.hex()} " +
                    "载荷长度=${handshake.declaredPayloadLength}（累计 ${engine.peerHandshakeRejections} 次）",
            )
        }
        Thread(
            {
                while (alive() && epochs.open) {
                    try {
                        Thread.sleep(200)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    synchronized(lock) {
                        engine.advanceTime(nowMillis())
                        drainLocked()
                    }
                }
            },
            "iap-link-pacer",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /** Invalidates queued writes/replies without waiting for the link lock or blocked tunnel I/O. */
    fun close() = epochs.close()

    /** 事件形态启动：服务端待命（收到 marker 回 marker，等车机 SYN）。 */
    fun begin() {
        synchronized(lock) {
            if (started) return
            started = true
            engine.enterNegotiating()
        }
    }

    /**
     * MIB3 布防（屏幕流 SETUP 成功后调用，且仅一次）：主动向**控制通道**发一次 DETECT。
     * 必须等 SETUP 完成后再发——早发会在 SETUP 交换期间被冲掉（Frida 结论）；此后
     * 车机的 marker 都是探测重发，真机（MIB3）回一次 SYN 就不再重发。
     *
     * 注意这里**只发裸 marker**：MIB3 的时序是「它回 SYN → 我们回 SYN|ACK」，
     * 提前把会话表拍过去反而会打乱它的状态机（见 [Iap2LinkEngine.startResponder]
     * 的实测记录）。车机若像 Honda HFT 那样反复重发 marker，才由
     * [startControlRoundLocked] 升级成「marker + 本表会话表」并重试。
     */
    fun armMib3() {
        synchronized(lock) {
            val isCurrentEpoch = epochs.predicate() ?: return
            begin()
            if (mib3Armed) return
            mib3Armed = true
            sendControlIap(Iap2LinkEngine.MARKER, isCurrentEpoch)
            log("IAPLINK: MIB3 detect 已发（控制通道 iAPSendMessage）——等待车机 SYN")
        }
    }

    /**
     * 主动推送一次 0x5001 NowPlayingUpdate（歌名变化时由宿主调用）。
     *
     * 车机只在订阅（0x5000）时收到一次 0x5001；而歌名是用户开始播放音乐后才经
     * CarLife 0x10035 到达的——若订阅先于歌名，就会停在空态。所以歌名就绪后必须
     * 由外部主动补推一次。链路尚未建立（MIB3 未识别）时静默忽略。
     */
    fun pushNowPlaying() {
        synchronized(lock) {
            if (!mib3Identified && !tunnelIdentified) return
            val nowPlaying = nowPlayingProvider?.invoke() ?: return
            lastPushedNowPlayingKey = nowPlayingKey(nowPlaying)
            sendRawCsmLocked(buildNowPlayingUpdate(nowPlaying))
            log(
                "IAPLINK: 主动补推 0x5001（歌名=${nowPlaying.title ?: "?"}" +
                    " 歌手=${nowPlaying.artist ?: "?"}）",
            )
        }
    }

    /**
     * 0x5001 内容键：**排除 elapsedMs**（播放进度每拍都在变，算进去等于每 1.5s 重推一次）。
     *
     * 只要歌名/歌手/专辑/来源/状态/时长/播放模式任一变化就认为该重推；逐行歌词被
     * 某些播放器写进 title，也属于「内容变化」，与宿主 `pushNowPlayingAll` 的
     * `title|artist` 去重语义一致。
     */
    internal fun nowPlayingKey(np: CarPlayNowPlaying): String =
        listOf(
            np.title,
            np.artist,
            np.album,
            np.appName,
            np.status,
            np.durationMs,
            np.shuffleMode,
            np.repeatMode,
        ).joinToString("|")

    /**
     * 车机订阅 0x5000 之后，按 [NOW_PLAYING_POLL_MILLIS] 周期主动查一次歌名。
     *
     * 存在的理由：0x5000 应答是**一次性**的，空态之后车机不会再来问；而补推原本
     * 完全依赖 MediaSession / CarLife 的**变化事件**。若歌名先于订阅就存在（音乐
     * 早就在放），或通知监听器晚绑定（vivo Android 17 上 requestRebind 常不生效），
     * 就再也不会有事件 → 仪表永久空白，且手机侧日志一条错误都没有。
     *
     * 这里只查不猜：provider 返回 null 就静默等下一拍，绝不伪造歌名；内容键与
     * 上次发出的一致也不重发。挂在既有 200ms 节拍上，不新增线程、不额外唤醒。
     */
    private fun maybePollNowPlayingLocked() {
        if (!nowPlayingSubscribed || (!mib3Identified && !tunnelIdentified)) return
        val now = nowMillis()
        if (lastNowPlayingPollMs != 0L && now - lastNowPlayingPollMs < NOW_PLAYING_POLL_MILLIS) return
        lastNowPlayingPollMs = now
        val np = nowPlayingProvider?.invoke() ?: return
        val key = nowPlayingKey(np)
        if (key == lastPushedNowPlayingKey) return
        lastPushedNowPlayingKey = key
        sendRawCsmLocked(buildNowPlayingUpdate(np))
        log(
            "IAPLINK: 0x5000 订阅后周期补推 0x5001（歌名=${np.title ?: "?"}" +
                " 歌手=${np.artist ?: "?"} 状态=${np.status}）",
        )
    }

    /**
     * 通话状态变化时主动补推 0x4155。
     *
     * 调用时机有讲究（[SessionController.switchAudioScene] 负责顺序）：**先把 telephony
     * 音频档 SETUP 好，再推状态**。车机收到「通话中」会立刻把音频路由切到通话通路，
     * 此时若通话流还没建，它切过去只能拿到一条空的媒体流——「车机显示通话中却没有声音」
     * 正是这个顺序错的典型症状。
     */
    fun pushCallState() {
        synchronized(lock) {
            if (!mib3Identified) return
            val state = currentCallState()
            sendCallStateLocked(state)
            log("IAPLINK: 主动补推 0x4155（通话状态=$state）")
        }
    }

    /** 当前通话状态（provider 缺省或越界一律回「无通话」，绝不发未知值给车机）。 */
    private fun currentCallState(): Int =
        (callStateProvider?.invoke() ?: IAP2_CALL_STATE_NONE)
            .coerceIn(IAP2_CALL_STATE_NONE, IAP2_CALL_STATE_ACTIVE)

    /** 发一条 0x4155（订阅应答与主动补推共用，保证两条路径的参数形态完全一致）。 */
    private fun sendCallStateLocked(state: Int) {
        sendCsmLocked(CALL_STATE_UPDATE, Iap2Csm.param(CALL_STATE_PARAM, byteArrayOf(state.toByte())))
    }

    /**
     * 开一轮控制通道协商：marker + 本表同步包（会话 10/11/12），刷新引擎重发节拍。
     */
    private fun startControlRoundLocked(reason: String) {
        val now = nowMillis()
        controlSynRounds++
        controlSynRoundStartedAtMs = now
        engine.initiate(now)
        log(
            "IAPLINK: 控制通道 marker + 本表同步包（第 $controlSynRounds/$MAX_CONTROL_SYN_ROUNDS 轮，$reason）" +
                "——等车机同步包（FF5A）",
        )
    }

    /** DETECT is parsed by the engine, not classified by transport-write boundaries. */
    private fun handlePeerDetectLocked() {
        if (!mib3Armed && tunnelWriter() == null) return
        if (engine.state == Iap2LinkEngine.State.NORMAL) {
            if (!epochs.restart()) return
            engine.restartResponder()
            parser = Iap2Csm.Parser()
            authentication.reset()
            tunnelCsmStarted = false
            tunnelIdentified = false
            tunnelEchoDelivered = false
            pendingEchoFlush = false
            mib3SynAckQueued = false
            mib3CsmStarted = false
            mib3Identified = false
            deviceState.reset()
            eventCsmStarted = false
            eventLinkArmed = false
            nowPlayingSubscribed = false
            lastPushedNowPlayingKey = null
            lastNowPlayingPollMs = 0L
            lastHidUsages = emptySet()
            hidDescriptor = null
            peerDetectCount = 0
            lastDetectResponseAtMs = null
            log("IAPLINK: peer DETECT restarted link; old identification/authentication invalidated")
        }
        val now = nowMillis()
        val previous = lastDetectResponseAtMs
        // One response per peer probe, rate-limited against an echo loop. No responder timer.
        if (previous != null && now - previous < DETECT_RESPONSE_INTERVAL_MS) return
        lastDetectResponseAtMs = now
        peerDetectCount++
        if (tunnelWriter() != null) {
            engine.respondToDetect()
            return
        }
        // Preserve the bounded Honda initiator fallback, but never restart on every DETECT.
        when {
            !eventLinkArmed && peerDetectCount >= EVENT_LINK_MARKER_THRESHOLD &&
                !controlSynGaveUp && controlSynRounds < MAX_CONTROL_SYN_ROUNDS -> {
                eventLinkArmed = true
                startControlRoundLocked("peer repeats DETECT without SYN")
            }
            eventLinkArmed && !controlSynGaveUp &&
                now - controlSynRoundStartedAtMs >= CONTROL_SYN_ROUND_MILLIS &&
                controlSynRounds < MAX_CONTROL_SYN_ROUNDS -> {
                startControlRoundLocked("peer still detecting")
            }
            else -> engine.respondToDetect()
        }
    }

    /** Incoming bytes are a stream: DETECT and FF5A may be split or coalesced arbitrarily. */
    fun onCarBytes(bytes: ByteArray, isCurrentEpoch: (() -> Boolean)? = null, fromControlReply: Boolean = false) {
        synchronized(lock) {
            if (!epochs.open || isCurrentEpoch?.invoke() == false) return
            diagnostics.onInput(bytes.size, fromControlReply)
            engine.echoFirst = tunnelWriter() != null
            engine.feed(bytes, nowMillis())
            drainLocked()
        }
    }


    /**
     * 引擎出站字节落盘：SYN|ACK 真正投递到对应通道后才允许启动 CSM——
     * 对端引擎未见到 SYN|ACK 前收到裸 ACK 会直接 die（DiPlay 实测）。
     */
    private fun flushOutputLocked() {
        val isCurrentEpoch = epochs.predicate() ?: return
        val tunnel = tunnelWriter()
        var echoDelivered = false
        while (true) {
            if (!isCurrentEpoch()) return
            val frame = engine.pollOutputFrame() ?: break
            val delivered =
                when {
                    tunnel != null -> tunnel.writeIapBytes(frame)
                    mib3Armed || eventLinkArmed -> {
                        sendControlIap(frame, isCurrentEpoch)
                        true
                    }
                    else -> sendEventIap(frame)
                }
            if (pendingEchoFlush && frame.size >= Iap2LinkEngine.HEADER_BYTES &&
                frame[1] == 0x5A.toByte() && (frame[4].toInt() and 0xC0) == 0xC0
            ) {
                echoDelivered = delivered
            }
        }
        if (!pendingEchoFlush || !echoDelivered) return
        pendingEchoFlush = false
        when {
            tunnel != null -> tunnelEchoDelivered = true
            mib3Armed || eventLinkArmed -> mib3SynAckQueued = true
        }
        log("IAPLINK: SYN|ACK output ordered before control session output; peer acceptance unconfirmed")
    }

    private fun drainLocked() {
        while (true) {
            when (val event = engine.pollEvent()) {
                null -> break
                is Iap2LinkEngine.Event.Control ->
                    handleFrameLocked(event.bytes)

                is Iap2LinkEngine.Event.Dead -> {
                    log("IAPLINK: 虚拟链路终止 - ${event.reason ?: "EOF"}")
                    return
                }
            }
        }
        if (engine.state == Iap2LinkEngine.State.NEGOTIATING && controlSynRounds > 0 &&
            nowMillis() - controlSynRoundStartedAtMs >= CONTROL_SYN_ROUND_MILLIS
        ) {
            engine.stopNegotiationRetries()
            if (controlSynRounds >= MAX_CONTROL_SYN_ROUNDS) controlSynGaveUp = true
        }
        noteLinkStateLocked()
        // 先冲刷（回显必须先行送达对端），再启动 CSM；超时换档可能新排队帧，最后再冲一次
        flushOutputLocked()
        maybeStartControlCsmLocked()
        authentication.advanceTime()
        // 订阅后的歌名补推要赶在同一次 flush 出去，否则要白等下一拍
        maybePollNowPlayingLocked()
        flushOutputLocked()
        diagnostics.notePhase()
    }

    /** 链路状态迁移只在日志里出现一次（IDLE→NEGOTIATING→NORMAL 是按键通路是否打通的分水岭）。 */
    private fun noteLinkStateLocked() {
        val state = engine.state
        if (state == lastLinkState) return
        lastLinkState = state
        log("IAPLINK: 链路状态 → $state（NORMAL 才可能收到车机 0x6800/0x6802 媒体键）")
    }


    /**
     * 控制会话启动（隧道/MIB3 两形态）：链路 NORMAL 且 SYN|ACK 已投递后，主动发
     * 0x1D00 StartIdentification。缺此步时对端控制端 5 分钟窗口超时即拆掉整条链路
     * （DiPlay 2026-10-11 实测根因；MIB3 的拆除窗口短得多，~10s）。
     */
    private fun maybeStartControlCsmLocked() {
        if (engine.state != Iap2LinkEngine.State.NORMAL) return
        val onTunnel = tunnelWriter() != null
        if (onTunnel) {
            if (tunnelCsmStarted || !tunnelEchoDelivered) return
            tunnelCsmStarted = true
        } else if (eventLinkArmed) {
            // 车机发起方形态：出站走控制通道，只要已采纳对端会话表即可开工
            if (eventCsmStarted || engine.peerSynchronization == null) return
            eventCsmStarted = true
        } else {
            if (!mib3Armed || mib3CsmStarted || !mib3SynAckQueued) return
            mib3CsmStarted = true
        }
        sendCsmLocked(Iap2MessageIds.START_IDENTIFICATION)
    }

    /** 链路上的 CSM 发送（进入引擎 backlog，由引擎按发送窗口 flush）。 */
    private fun sendCsmLocked(
        messageId: Int,
        body: ByteArray = ByteArray(0),
    ) {
        // 与蓝牙侧同一把尺子：越权只记 WARN；0xA100/0xFFF0 按车机可收清单裁掉。
        if (!AccessoryDeclaration.noteSend(messageId, "tunnel", log)) return
        val frame = Iap2Csm.encode(messageId, body)
        val ok = engine.sendControl(frame, nowMillis())
        log(
            "IAPLINK: 隧道 tx 0x${messageId.toString(16)} " +
                "(${Iap2MessageIds.nameOf(messageId)}) ${frame.size}B ok=$ok",
        )
        SessionTrace.csm(SessionTrace.Dir.TX, "tunnel", messageId, body, if (ok) "" else "send-failed")
    }

    /** 直接投递完整 CSM 帧（Frida 抓的逐字节常量，勿重新编码）。 */
    private fun sendRawCsmLocked(frame: ByteArray) {
        // 帧头：0..1 起始字、2..3 总长度、4..5 messageId、6.. body。
        // 旧实现从 2..3 取 ID（那是长度），日志里的「隧道 tx 0x0100」全是假 ID。
        val messageId =
            if (frame.size >= Iap2Csm.HEADER_BYTES) {
                ((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF)
            } else {
                -1
            }
        // 必须在写进引擎 backlog **之前**判：MIB3 突发里的 0xFFF0 就是走这条出口，
        // 车机没声明可收时要整帧丢掉，不能只丢日志。
        if (messageId >= 0 && !AccessoryDeclaration.noteSend(messageId, "tunnel", log)) return
        val ok = engine.sendControl(frame, nowMillis())
        log(
            "IAPLINK: 隧道 tx 0x${messageId.toString(16)} " +
                "(${Iap2MessageIds.nameOf(messageId)}) ${frame.size}B raw ok=$ok",
        )
        if (messageId >= 0) {
            SessionTrace.csm(
                SessionTrace.Dir.TX,
                "tunnel",
                messageId,
                frame.copyOfRange(Iap2Csm.HEADER_BYTES, frame.size),
                if (ok) "raw" else "raw,send-failed",
            )
        }
    }

    /**
     * 控制会话应答（隧道与 MIB3 共用识别/MFi 骨架，MIB3 另有突发与订阅应答）：
     *   0x1D01 → 0x1D02 + 0xAA00；0xAA06 → 再发 0xAA00；
     *   0xAA01 → 0xAA02 挑战（MIB3 20B / DiPlay 32B）；0xAA03 → 0xAA05（MIB3 随发突发）。
     * 订阅（仅 MIB3）：0x4157→0x4158、0x4154→0x4155、0x4170→0x4171、0x5000→0x5001 空态。
     * 0xEA02（requestAppLaunch）/0xA101（VehicleStatus）/0xFFFB（PASCD 定位样本 ~1Hz）
     * 只链路 ACK（引擎自动）——**0xFFFB 漏 ACK 车机同样 ~10s 后关控制通道**（Frida 结论）。
     */
    private fun maybeRespondControlCsmLocked(frame: Iap2Csm.Frame) {
        val onTunnel = tunnelWriter() != null
        // 车机发起方形态复用 MIB3 那套识别/MFi 骨架（出站同样走控制通道）
        val mib3 = (mib3Armed || eventLinkArmed) && !onTunnel
        if (!onTunnel && !mib3) return
        val ids = Iap2MessageIds
        when (frame.messageId) {
            ids.IDENTIFICATION_INFORMATION -> {
                val identified = if (onTunnel) tunnelIdentified else mib3Identified
                if (identified) return
                if (onTunnel) tunnelIdentified = true else mib3Identified = true
                val params =
                    Iap2Csm
                        .parseParams(frame.body)
                        .associateBy { it.id }
                log(
                    "IAPLINK: 隧道 rx 0x1d01 接收端身份 " +
                        "name=${params[0]?.value?.decodeToString()?.trimEnd('\u0000')} " +
                        "model=${params[1]?.value?.decodeToString()?.trimEnd('\u0000')}",
                )
                // 参 6/7 = 车机声明「它会发 / 它可收」的消息清单。三层输出见 Iap2CapabilityReport
                // （原始清单 / 按子系统分组 / 目录里没有的陌生 ID）：
                //   · 媒体键排查看**发送清单**里有没有 0x6800/0x6802；
                //   · 投仪表/HUD 看**可收清单**里有没有导航引导 0x52xx（MIB3 4398 起）。
                // 清单汇入跨链路的 AccessoryDeclaration：MIB3 突发的裁剪判据可能来自
                // 蓝牙侧早到的那帧 0x1D01，两条链路共用同一把尺子。
                val sends = params[6]?.value?.let(::u16List)
                val receives = params[7]?.value?.let(::u16List)
                val components = AccessoryDeclaration.parseComponents(frame.body)
                AccessoryDeclaration.onIdentification(sends?.toSet(), receives?.toSet(), components, "tunnel")
                log(AccessoryDeclaration.describe())
                sends?.let { ids ->
                    Iap2CapabilityReport.report(log, "IAPLINK: ", "发送清单", ids, isReceiveList = false)
                    if (!ids.contains(Iap2MessageIds.START_HID)) {
                        log(
                            "IAPLINK: 发送清单无 0x6800 StartHID → 这台车机不用 iAP2 HID 送按键，" +
                                "不必在这条通路耗（改查蓝牙/AVRCP 侧）",
                        )
                    }
                }
                receives?.let { ids ->
                    Iap2CapabilityReport.report(
                        log,
                        "IAPLINK: ",
                        "可收清单",
                        ids,
                        isReceiveList = true,
                    )
                }
                if (!components.isEmpty) {
                    log(
                        "IAPLINK: 0x1d01 组件声明 语言=${components.currentLanguage ?: "未提供"}" +
                            " 无线CarPlay(参24)=${components.wirelessCarPlayDeclared}" +
                            " 导航显示(参30)=${components.navigationDisplayDeclared}",
                    )
                }
                sendCsmLocked(ids.IDENTIFICATION_ACCEPTED)
                authentication.begin(
                    mfiIdentityOf(params) + " tunnel",
                    onTunnel,
                    sends?.contains(ids.MFI_AUTH_SERIAL_NUMBER) == true,
                )
            }

            ids.MFI_AUTH_SERIAL_NUMBER,
            ids.MFI_AUTHENTICATION_FAILED,
            ids.MFI_CERTIFICATE,
            ids.MFI_RESPONSE,
            -> authentication.onFrame(frame)

            // Working BMW box: AD00 -> AD01, parameter 0 is one byte (firmware 0x63cb4).
            0xAD00 -> {
                if (mib3 && authentication.finished) sendCsmLocked(0xAD01, Iap2Csm.param(0, byteArrayOf(1)))
            }

            START_COMMUNICATIONS_UPDATES -> {
                if (!mib3) return
                sendRawCsmLocked(Iap2SubscriptionReplies.COMMUNICATIONS_UPDATE_IDLE)
                log("IAPLINK: MIB3 0x4157 → 0x4158（空态）")
            }

            // 车机点名要 CarPlay 导航引导数据（MIB3 4398/CL3B 投仪表/HUD 的入口）。
            // 没有 maneuver 数据源，所以只回 0x5201 空态保住控制通道，不编造报文体；
            // 车机可收清单里明确没有 0x52xx 时不回，避免越权。
            Iap2MessageIds.START_ROUTE_GUIDANCE_UPDATES -> {
                if (AccessoryDeclaration.acceptsRouteGuidance() == false) {
                    log("IAPLINK: 0x5200 订阅但可收清单未声明导航引导 → 不回 0x5201（按声明裁剪）")
                    return
                }
                val body = Iap2SubscriptionReplies.idleBodyFor(Iap2SubscriptionReplies.ROUTE_GUIDANCE_UPDATE)
                if (body != null) sendCsmLocked(Iap2SubscriptionReplies.ROUTE_GUIDANCE_UPDATE, body)
                log("IAPLINK: 0x5200 → 0x5201（空态；无 maneuver 数据源，暂不主动推导航报文）")
            }

            Iap2SubscriptionReplies.STOP_ROUTE_GUIDANCE_UPDATES -> {
                log("IAPLINK: 0x5203 车机停止导航引导订阅")
            }

            START_CALL_STATE_UPDATES -> {
                if (!mib3) return
                // 车机会一直把订阅时的答案当「当前状态」，之后靠主动补推更新。
                val state = currentCallState()
                sendCallStateLocked(state)
                log(
                    "IAPLINK: MIB3 0x4154 → 0x4155（通话状态=$state" +
                        (if (state == IAP2_CALL_STATE_NONE) " 无通话" else "") + "）",
                )
            }

            START_LIST_UPDATES -> {
                if (!mib3) return
                sendCsmLocked(
                    LIST_UPDATE,
                    Iap2Csm.param(0x0000, byteArrayOf(0)) +
                        Iap2Csm.param(0x0002, byteArrayOf(0, 0)) +
                        Iap2Csm.param(0x0005, byteArrayOf(0)) +
                        Iap2Csm.param(0x0007, byteArrayOf(0, 0)),
                )
                log("IAPLINK: MIB3 0x4170 → 0x4171（空列表）")
            }

            Iap2MessageIds.START_NOW_PLAYING_UPDATES -> {
                if (!mib3) return
                // 每次（重）订阅都是全新上下文：清掉上次的内容键，允许同歌再推一次；
                // 同时挂起周期补推——空态不是终点，歌名晚到时由节拍自愈。
                nowPlayingSubscribed = true
                lastPushedNowPlayingKey = null
                lastNowPlayingPollMs = nowMillis()
                val nowPlaying = nowPlayingProvider?.invoke()
                if (nowPlaying != null) {
                    lastPushedNowPlayingKey = nowPlayingKey(nowPlaying)
                    sendRawCsmLocked(buildNowPlayingUpdate(nowPlaying))
                    log(
                        "IAPLINK: MIB3 0x5000 → 0x5001（歌名=${nowPlaying.title ?: "?"}" +
                            " 歌手=${nowPlaying.artist ?: "?"} 状态=${nowPlaying.status})",
                    )
                } else {
                    sendRawCsmLocked(MIB3_NOW_PLAYING_UPDATE_IDLE)
                    log("IAPLINK: MIB3 0x5000 → 0x5001（空态，无正在播放；已挂起周期补推）")
                }
            }

            ids.START_HID -> {
                // 车机声明「我要开始发 HID 报告了」，通常带描述符（决定报告字节语义）
                val params = Iap2Csm.parseParams(frame.body)
                val descriptor = params.map { it.value }.firstOrNull { it.size > 8 }
                if (descriptor != null) {
                    hidDescriptor = descriptor
                    log(
                        "IAPLINK: 车机 0x6800 StartHID 描述符 ${descriptor.size}B hex=" +
                            descriptor.joinToString(" ") { "%02x".format(it) }.take(120),
                    )
                } else {
                    log("IAPLINK: 车机 0x6800 StartHID（无描述符）参数=${params.map { it.id }}")
                }
            }

            ids.ACCESSORY_HID_REPORT -> {
                // 方向盘/面板媒体键。
                //
                // 2026-10-20 本田 HFT 实测帧（11B）：
                //   00 06 00 00 00 03 | 00 05 00 01 04
                //   param id=0 值 00 03（报告类型/描述，恒定）← 之前被当成报告值
                //   param id=1 值 04（真正的 HID 报文）
                // 旧代码取「第一个 1~8 字节的 param」→ 永远拿到 00 03，位图里一位都不置，
                // 于是日志一直打 `usage=无 keyCode=0`，按键到了却点不出任何动作。
                val params = Iap2Csm.parseParams(frame.body)
                val report = hidReportFromParams(params)
                val keyMap = hidDescriptor?.let(ConsumerKeys::parseDescriptor)
                val usages =
                    when {
                        report == null -> emptyList()
                        keyMap != null -> keyMap.usagesFor(report)
                        // 无描述符：退化为「单字节即 Usage ID」
                        else -> listOf(report[0].toInt() and 0xFF).filter { it != 0 }
                    }
                if (hidReportFrames < HID_REPORT_VERBOSE_FRAMES) {
                    hidReportFrames++
                    log(
                        "IAPLINK: 车机 0x6802 params=" +
                            params.joinToString(",") { "${it.id}=" + it.value.joinToString("") { b -> "%02x".format(b) } } +
                            " 报告=" + (report?.joinToString(" ") { "%02x".format(it) } ?: "空") +
                            " 位图=" + (keyMap?.isArray?.let { if (it) "Array" else "Variable" } ?: "无描述符"),
                    )
                }
                fireHidKeysLocked(usages)
            }

            ids.LOCATION_INFORMATION -> {
                // 车机定位样本（~1Hz）。以前这条落进「未处理的 CSM」分支，日志里只有
                // 一行 hex，既看不出是不是坐标、也白白刷满 20 条上限。
                //
                // 注意：**必须继续让引擎回链路 ACK**（漏 ACK 车机 ~10s 后关控制通道），
                // 这条分支只做观测量解析，不改变应答行为。
                val sentences = NmeaLocation.splitSentences(frame.body)
                locationFrames++
                if (locationFrames <= 3 || locationFrames % 60 == 0) {
                    log(
                        "IAPLINK: 车机定位样本 #$locationFrames " +
                            (sentences.joinToString(" | ").ifEmpty { "（报文体里没有 ASCII 句子）" }) +
                            " 原始=" + frame.body.joinToString(" ") { "%02x".format(it) }.take(80),
                    )
                }
                if (sentences.isNotEmpty()) onLocationSamples?.invoke(sentences)
            }

            else -> {
                // 未处理的消息也留痕：车机若用非常规 ID 下发媒体键/按键，这里能第一时间
                // 在日志里看到对应消息号（切歌按键排查的关键观测点）。
                // 带全量 hex：宝马 iD9 的 0xad00/0xad02/0xad03 家族既不在 Apple 公开表里，
                // 也没有参考实现的命名——只能靠报文体结构反推它是不是输入（触摸/HID）通路。
                unknownCsmIds++
                val navFrame =
                    Iap2CapabilityReport.groupOf(frame.messageId) == Iap2CapabilityReport.Group.NAVIGATION
                when {
                    // 导航引导单独一支：不设条数上限、不截断。车机若主动下发 0x52xx
                    // （MIB3 4398/CL3B 起可能为投仪表而订阅），报文体就是最直接的样本——
                    // 参数编号、字节序、是否带 maneuver 字段，全在这行里。
                    navFrame -> {
                        val navParams = Iap2Csm.parseParams(frame.body)
                        log(
                            "IAPLINK: ★ 导航引导 CSM 0x${frame.messageId.toString(16)} " +
                                "(${Iap2MessageIds.nameOf(frame.messageId)}) ${frame.body.size}B " +
                                "params=" + navParams.joinToString(",") {
                                    "#${it.id}=" + it.value.joinToString("") { b -> "%02x".format(b) }
                                } +
                                " hex=" + frame.body.joinToString(" ") { "%02x".format(it) },
                        )
                    }
                    unknownCsmIds <= 20 -> {
                        log(
                            "IAPLINK: 未处理的 CSM 0x${frame.messageId.toString(16)} " +
                                "(${Iap2MessageIds.nameOf(frame.messageId)}) ${frame.body.size}B hex=" +
                                frame.body.joinToString(" ") { "%02x".format(it) },
                        )
                    }
                }
            }
        }
    }

    /**
     * 把一批「本次按下的 Usage」转成 Android 媒体键注入。
     *
     * 车机的 HID 报告是**状态式**的：按住会以 ~10Hz 连推同一个位图，松开推 0x00。
     * 因此只在「新出现」时触发一次（边沿触发），否则一次长按会跳掉几十首歌；
     * 只有在同一按键持续按住超过 [HID_HOLD_REPEAT_MILLIS] 时才允许再触发，
     * 保持方向盘长按「连续切歌」的手感。
     */
    private fun fireHidKeysLocked(usages: List<Int>) {
        val now = nowMillis()
        if (usages.isEmpty()) {
            val released = lastHidUsages
            lastHidUsages = emptySet()
            hidHoldStartedAtMs = 0L
            // 真松手才回调（按住期间的重复报告不会走到这里）。只对「刚才有按键」
            // 的那一次给事件，避免车机空闲时持续推 0x00 把上层刷爆。
            if (released.isNotEmpty()) onHidKeysReleased?.invoke(released)
            return
        }
        val fresh = usages.filter { it !in lastHidUsages }
        val repeat =
            fresh.isEmpty() &&
                hidHoldStartedAtMs > 0 &&
                now - hidHoldStartedAtMs >= HID_HOLD_REPEAT_MILLIS
        lastHidUsages = usages.toSet()
        if (fresh.isNotEmpty()) hidHoldStartedAtMs = now
        if (fresh.isEmpty() && !repeat) return
        if (repeat) hidHoldStartedAtMs = now
        val firing = if (fresh.isNotEmpty()) fresh else usages
        for (usage in firing) {
            val keyCode = ConsumerKeys.keyCodeFor(usage)
            log(
                "IAPLINK: 车机媒体键 ${ConsumerKeys.nameOf(usage)}(0x%02x) → keyCode=".format(usage) +
                    (if (keyCode != 0) "$keyCode 已注入" else "无对应 Android 键（忽略）"),
            )
            if (keyCode != 0) onMediaKey?.invoke(keyCode)
        }
    }

    /**
     * 构造 0x5001 NowPlayingUpdate 完整帧（带真实歌名/歌手/专辑/播放状态）。
     * 字段布局见 [Iap2NowPlaying]，与空态常量 [MIB3_NOW_PLAYING_UPDATE_IDLE] 同构。
     */
    private fun buildNowPlayingUpdate(np: CarPlayNowPlaying): ByteArray = Iap2NowPlaying.encode(np)

    /** 大端 u16 数组（识别信息里的消息 ID 清单）。 */
    private fun u16List(bytes: ByteArray): List<Int> =
        (0 until bytes.size / 2).map {
            ((bytes[it * 2].toInt() and 0xFF) shl 8) or (bytes[it * 2 + 1].toInt() and 0xFF)
        }

    /** 0x1D01 身份参数（name/model/manufacturer）→ 记忆键素材（NUL 去净、空档跳过）。 */
    private fun mfiIdentityOf(params: Map<Int, Iap2Csm.Param>): String =
        listOf(0, 1, 2)
            .mapNotNull { id ->
                params[id]
                    ?.value
                    ?.decodeToString()
                    ?.trimEnd('\u0000')
                    ?.takeIf { it.isNotBlank() }
            }.joinToString(" ")

    private fun handleFrameLocked(bytes: ByteArray) {
        frameCount++
        val frames = parser.offer(bytes)
        for (frame in frames) {
            val hex = frame.body.joinToString(" ") { "%02x".format(it) }
            if (frameCount <= 30 || frameCount % 100 == 0) {
                log(
                    "IAPLINK: rx 0x${frame.messageId.toString(16)} " +
                        "(${Iap2MessageIds.nameOf(frame.messageId)}) ${frame.body.size}B hex=${hex.take(160)}",
                )
            }
            // 摘要行按条数抽样（上），录制行全量（下）：事后分析拆链时，被抽掉的那几条
            // 往往正是关键（例如某条订阅的应答没发）。
            SessionTrace.csm(SessionTrace.Dir.RX, "tunnel", frame.messageId, frame.body)
            maybeRespondControlCsmLocked(frame)
        }
        if (frames.isEmpty() && frameCount <= 10) {
            log("IAPLINK: rx ${bytes.size}B（非 CSM）hex=" + bytes.joinToString(" ") { "%02x".format(it) }.take(160))
            SessionTrace.mark("隧道 rx ${bytes.size}B 非 CSM: " + bytes.joinToString(" ") { "%02x".format(it) }.take(400))
        }
    }

    /**
     * 引擎回调：iAP2 控制会话下发的 HID 报告解析出的 Android 媒体键。
     *
     * 车机方向盘/面板的上一曲、下一曲在 CarPlay 里走 `0x6802 AccessoryHIDReport`，
     * 只有 iAP2 链路真正建立后才会来（见 [eventLinkArmed]）。
     */
    var onMediaKey: ((Int) -> Unit)? = null

    /**
     * 引擎回调：HID 报告从「有按键」变成「全松开」时，本次释放的 usage 集合。
     *
     * 1.2.22 新增，给语音键长按 PTT 的 `KEYCODE_VR_STOP` 收尾用。这是状态式报告的
     * **边沿**：按住时 ~10Hz 连推同一个 usage，只有真正松开才会给空集合，不会刷屏。
     */
    var onHidKeysReleased: ((Set<Int>) -> Unit)? = null

    /**
     * 引擎回调：车机定位样本（0xFFFB LocationInformation）里的 NMEA 句子。
     *
     * 车机每秒推一条，报文体是 `[len][id=0][ASCII]`，可能是 `$PASCD`（奥迪，无经纬度）
     * 也可能是标准 `$GPGGA/$GPRMC/$GPGSA`（有经纬度）。解析在 [NmeaLocation]，
     * 这里只负责「把句子捞出来交出去」并做低频日志，不猜任何语义。
     */
    var onLocationSamples: ((List<String>) -> Unit)? = null

    /** 已收到的定位报文数（日志抽样用）。 */
    private var locationFrames = 0

    /** 车机在 0x6800 StartHID 里给出的 HID 描述符（解析报告用；null = 未提供）。 */
    private var hidDescriptor: ByteArray? = null

    companion object {
        /**
         * 车机订阅 0x5000 后主动查歌名的周期。
         *
         * 真机时序里订阅常**早于**歌名：通知监听器晚绑定、音乐早就在放（歌名先于
         * 订阅存在，不会再有 metadata 事件）时，事件驱动的补推永远不触发，车机就
         * 一直停在 0x5001 空态。1.5s 既能让歌名及时补上，又不会给控制链路刷帧
         * （只有内容键变化才真正发，见 [nowPlayingKey]）。
         */
        const val NOW_PLAYING_POLL_MILLIS = 1_500L

        /**
         * 车机在事件通道上连续发到第几个 SYN marker 才判定「它在等我们回 marker」。
         * 真机（MIB3）在我们主动 detect 后就不再重发；Honda HFT 实测连发上百次，
         * 取 2 既能避开 MIB3 的一次探测重发，又不会让车机白等太久。
         */
        const val EVENT_LINK_MARKER_THRESHOLD = 2

        /**
         * 控制通道协商：每轮多久无进展就重开下一轮，最多几轮。
         * 车机 marker 是 1Hz，4s 一轮 ⇒ 每轮能收到 4 次重发机会；5 轮 ≈ 20s，
         * 之后停手静默等待（它真发 0x1D01 仍会被正常处理）。
         */
        const val CONTROL_SYN_ROUND_MILLIS = 4_000L
        const val MAX_CONTROL_SYN_ROUNDS = 5

        private const val DETECT_RESPONSE_INTERVAL_MS = 200L

        /**
         * 0x6802 AccessoryHIDReport 的报文参数号。
         *
         * 本田 HFT 实测：param 0 = 报告类型/描述（`00 03`，永远不变），
         * param 1 = 真正的 HID 报文（如 `04` = 下一首）。取错参数号等于所有按键都解码成「无」。
         */
        const val HID_REPORT_DATA_PARAM = 1

        /** 参数明细只打前几帧（之后每帧都会触发按键动作，日志已足够）。 */
        const val HID_REPORT_VERBOSE_FRAMES = 8

        /** 同一按键持续按住多久才允许再次触发（模拟长按连发，短按绝不重复）。 */
        const val HID_HOLD_REPEAT_MILLIS = 1_500L

        /**
         * 0x6802 的参数表 → HID 报文。
         *
         * 优先取 param [HID_REPORT_DATA_PARAM]；缺省（个别车机只给一个参数）时退回
         * 「最后一个 1~8 字节的参数」——注意**不能取第一个**：本田 HFT 的 param 0 恒为
         * `00 03`（报告类型），当成报文值会让所有按键都解成「无」。
         */
        fun hidReportFromParams(params: List<Iap2Csm.Param>): ByteArray? =
            params.firstOrNull { it.id == HID_REPORT_DATA_PARAM }?.value?.takeIf { it.isNotEmpty() }
                ?: params.lastOrNull { it.value.isNotEmpty() && it.value.size <= 8 }?.value

        // ---- MIB3 消息号（无正式名/未入 Iap2MessageIds 的小集合） ----
        const val START_COMMUNICATIONS_UPDATES = 0x4157
        const val CALL_STATE_UPDATE = 0x4155
        const val START_CALL_STATE_UPDATES = 0x4154

        /** 0x4155 里承载通话状态的参数号（MIB3 空态实测就是这个号，值 1 字节）。 */
        const val CALL_STATE_PARAM = 0x0002

        /** iAP2 CallState：无通话。 */
        const val IAP2_CALL_STATE_NONE = 0

        /** iAP2 CallState：来电（车机据此显示来电界面）。 */
        const val IAP2_CALL_STATE_INCOMING = 1

        /** iAP2 CallState：拨出。 */
        const val IAP2_CALL_STATE_OUTGOING = 2

        /** iAP2 CallState：通话已建立。 */
        const val IAP2_CALL_STATE_ACTIVE = 3
        const val START_LIST_UPDATES = 0x4170
        const val LIST_UPDATE = 0x4171

        /** ---- 以下为 Frida 抓包的逐字节常量（MIB3 真机 iPhone → Audi MMI）---- */
        private val HEX_DIGITS = "0123456789abcdef"

        /** 0x5001 NowPlayingUpdate 空态（无媒体/无播放）。 */
        internal val MIB3_NOW_PLAYING_UPDATE_IDLE: ByteArray =
            hex(
                "404000555001002a00000005000100000800040000000000050006000005000c0000" +
                    "0500100000050012000005001a8000250001000500000000080001000000000005" +
                    "000500000500060000050007000005001000",
            )

        /** Shared communications idle reply, also used by the Bluetooth path. */
        internal val MIB3_COMMUNICATIONS_UPDATE_IDLE: ByteArray
            get() = Iap2SubscriptionReplies.COMMUNICATIONS_UPDATE_IDLE

        private fun hex(s: String): ByteArray {
            val clean = s.replace(" ", "")
            return ByteArray(clean.length / 2) { i ->
                val hi = HEX_DIGITS.indexOf(clean[i * 2])
                val lo = HEX_DIGITS.indexOf(clean[i * 2 + 1])
                ((hi shl 4) or lo).toByte()
            }
        }
    }
}
