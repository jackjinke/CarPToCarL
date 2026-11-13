package io.github.shaonialife.carptocar.airplay

import io.github.shaonialife.carptocar.video.VideoSender
import java.util.concurrent.Executors

/**
 * 串行化资源归属变更与屏幕/音频流生命周期。
 *
 * 车机借走屏幕（如倒车影像/雷达 PDC）不是 CarPlay 会话断开：仅停止并 `TEARDOWN` 屏幕流，
 * 待屏幕归还或收到 `requestUI` 后重新走全新屏幕流 `SETUP` + `RECORD` 恢复画面。
 */
internal class CarPlayResourceStreams(
    private val client: AirPlayClient,
    private val sessionKey: ByteArray?,
    private val audioEpisodes: CarPlayAudioEpisodes,
    private val displayUuid: String?,
    initialSender: VideoSender?,
    private val createSender: (AirPlayClient.ScreenStreamInfo) -> VideoSender,
    private val onScreenPaused: () -> Unit,
    private val onScreenResumed: (VideoSender) -> Boolean,
    private val onAudioOwnership: (Boolean, () -> Boolean) -> Boolean,
    private val onAudioStatus: (String) -> Unit,
    private val onFailure: (String) -> Unit,
    private val log: (String) -> Unit,
    private val onRecorded: () -> Unit = {},
) {
    private val lock = Any()
    private val worker =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "carplay-resource-streams").apply { isDaemon = true }
        }
    private val audioWorker =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "carplay-audio-ownership").apply { isDaemon = true }
        }

    @Volatile
    private var closed = false

    @Volatile
    private var active = false
    private var audioActive = false
    private var screenRequested = initialSender == null
    private var screenRequestedFromCarUi = false

    @Volatile
    private var wantedScreen = client.ownsScreenResource

    @Volatile
    private var wantedAudio = client.ownsAudioResource

    @Volatile
    var screenPaused = !wantedScreen
        private set

    @Volatile
    private var sender: VideoSender? = initialSender

    @Volatile
    private var screenRevision = 0L
    private var screenNegotiated = initialSender != null
    private var screenActive = false
    private var audioActivated = false

    private enum class AudioIntent { MEDIA, NAVIGATION }

    private var navigationPlaying = false
    private var pendingAudioIntent: AudioIntent? = null
    private var mediaEpisode: CarPlayAudioEpisodes.Episode? = null
    private var navigationEpisode: CarPlayAudioEpisodes.Episode? = null
    private var audioIntentRevision = 0L
    private var lastAudioAttemptRevision = -1L
    private var lastAudioResourceRevision = -1L
    private var releaseNavigationBorrow = false
    private var lastReturnIntentRevision = -1L
    private var lastReturnResourceRevision = -1L

    init {
        refreshAudioPlayback()
    }

    private fun fail(reason: String) {
        log("MODE: 资源流故障 - ${reason.replace('\n', ' ').replace('\r', ' ').take(240)}")
        onFailure(reason)
    }

    /** 屏幕流管理先于可选音频激活启动。 */
    fun activate(initialScreenAttached: Boolean) {
        val waiting =
            synchronized(lock) {
                if (closed || active) return
                screenActive = initialScreenAttached && sender?.isRunning == true
                screenPaused = !screenActive
                !screenNegotiated && !wantedScreen
            }
        if (waiting && alive()) onScreenPaused()
        synchronized(lock) {
            if (closed || active) return
            active = true
            reconcile()
        }
    }

    /** 仅当初始阶段已完成音频 SETUP + RECORD 时才将 [initialAudioActivated] 置为 true。 */
    fun activateAudio(initialAudioActivated: Boolean) {
        synchronized(lock) {
            if (closed || audioActive) return
            audioActivated = initialAudioActivated
            audioActive = true
            reconcile()
        }
    }

    /** 依据 [CarPlayAudioEpisodes] 快照刷新待执行的媒体/导航音频申请或归还动作。 */
    fun refreshAudioPlayback() {
        val playback = audioEpisodes.snapshot()
        synchronized(lock) {
            if (closed || (playback.media === mediaEpisode && playback.navigation === navigationEpisode)) return
            val media = playback.media != null
            val navigation = playback.navigation != null
            if (media && playback.media !== mediaEpisode) pendingAudioIntent = AudioIntent.MEDIA
            if (navigation && playback.navigation !== navigationEpisode && pendingAudioIntent != AudioIntent.MEDIA) {
                pendingAudioIntent = AudioIntent.NAVIGATION
            }
            if (!media && pendingAudioIntent == AudioIntent.MEDIA) {
                pendingAudioIntent = if (navigation) AudioIntent.NAVIGATION else null
            }
            if (!navigation) {
                if (pendingAudioIntent == AudioIntent.NAVIGATION) pendingAudioIntent = null
                if (navigationPlaying) releaseNavigationBorrow = true
            }
            mediaEpisode = playback.media
            navigationEpisode = playback.navigation
            navigationPlaying = navigation
            audioIntentRevision++
            log(
                "AUDIO-INTENT: media=$media navigation=$navigation pending=$pendingAudioIntent " +
                    "revision=$audioIntentRevision deferred=${!audioActive}",
            )
            if (audioActive) audioWorker.execute(::applyAudioOwnership)
        }
    }

    /** 申请主屏；[fromRequestUi] 为 true 表示车机通过 `requestUI` 主动请求切回手机画面。 */
    fun requestScreen(fromRequestUi: Boolean = false) {
        synchronized(lock) {
            if (closed) return
            screenRequested = true
            if (fromRequestUi) screenRequestedFromCarUi = true
            if (active) worker.execute(::applyOwnership)
        }
    }

    /** 车机事件通道已原子提交并应答资源变更后，触发屏幕与音频流对齐。 */
    fun reconcile() {
        synchronized(lock) {
            if (closed) return
            val previousScreen = wantedScreen
            val previousAudio = wantedAudio
            val screen = client.ownsScreenResource
            if (screen != wantedScreen) {
                wantedScreen = screen
                screenRevision++
            }
            wantedAudio = client.ownsAudioResource
            if (!wantedScreen) screenPaused = true
            if (previousScreen != wantedScreen || previousAudio != wantedAudio) {
                log("MODE: 资源对齐 screen=$previousScreen->$wantedScreen audio=$previousAudio->$wantedAudio")
            }
            if (active) worker.execute(::applyOwnership)
            if (audioActive) audioWorker.execute(::applyAudioOwnership)
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            screenPaused = true
            worker.shutdownNow()
            audioWorker.shutdownNow()
            sender?.stop()
        }
    }

    private fun alive(): Boolean = !closed && !client.cancelled && client.controlSessionAlive

    private fun applyOwnership() {
        if (!alive()) return
        try {
            val (requested, fromRequestUi) =
                synchronized(lock) {
                    val req = screenRequested
                    val fromUi = screenRequestedFromCarUi
                    screenRequested = false
                    screenRequestedFromCarUi = false
                    req to fromUi
                }
            var modesPublished = false
            if (requested && !client.ownsScreenResource) {
                var invalidated = false
                val acquired =
                    client.acquireScreenResource(
                        fromRequestUi = fromRequestUi,
                        onInvalidated = { invalidated = true },
                    )
                if (!alive()) return
                if (!acquired && client.ownsScreenResource) {
                    fail("主屏取得后资源状态通知失败")
                    return
                }
                modesPublished = acquired
                synchronized(lock) {
                    if (!acquired) {
                        screenRequested = true
                        if (invalidated && fromRequestUi) screenRequestedFromCarUi = true
                    }
                    if (invalidated && !closed) worker.execute(::applyOwnership)
                    val screen = client.ownsScreenResource
                    if (screen != wantedScreen) {
                        wantedScreen = screen
                        screenRevision++
                    }
                }
                log(
                    "MODE: 申请主屏 fromRequestUi=$fromRequestUi acquired=$acquired invalidated=$invalidated",
                )
            }
            if (!modesPublished && wantedScreen != screenActive) {
                if (!client.assertModes() && alive()) {
                    fail("资源状态通知失败")
                    return
                }
            }
            if (!alive()) return
            if (screenNegotiated && (!wantedScreen || !screenActive)) {
                if (!retireScreen(if (!wantedScreen) "让出主屏" else "屏幕未挂载")) return
            }
            if (wantedScreen && !screenNegotiated) resumeScreen()
            if (alive() && wantedScreen && screenActive) screenPaused = false
        } catch (error: Exception) {
            if (alive()) fail("资源切换失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun applyAudioOwnership() {
        if (!alive()) return
        try {
            val intent: AudioIntent?
            val intentRevision: Long
            val episode: CarPlayAudioEpisodes.Episode?
            val resourceRevision = client.audioResourceRevision
            synchronized(lock) {
                intent = pendingAudioIntent
                intentRevision = audioIntentRevision
                episode = if (intent == AudioIntent.MEDIA) mediaEpisode else navigationEpisode
            }
            val attempted =
                intent != null &&
                    (intentRevision != lastAudioAttemptRevision || resourceRevision != lastAudioResourceRevision)
            if (attempted) {
                val claimRevision =
                    if (intent == AudioIntent.MEDIA && episode != null) {
                        audioEpisodes.beginAudioClaim(episode)
                    } else {
                        audioEpisodes.snapshot().carTakeRevision
                    }
                var invalidated = false
                var acquired = false
                try {
                    onAudioStatus(if (intent == AudioIntent.MEDIA) "申请媒体音频" else "申请导航临时音频")
                    // 导航播报按 AIS 后台临时借用优先级（priority=100, temporary=true）发起：
                    // 可在收音机（borrowConstraint=100）上临时借声并在播报结束后归还，
                    // 但不会强拆车机用户静音或车载电话的受限借用（unborrowConstraint>=500）。
                    val priority = if (intent == AudioIntent.MEDIA) 500L else 100L
                    acquired =
                        episode?.isCurrent == true &&
                        client.acquireAudioResource(
                            priority = priority,
                            temporary = intent == AudioIntent.NAVIGATION,
                            onInvalidated = { invalidated = true },
                            isCurrentIntent = { alive() && episode.isCurrent },
                        )
                } finally {
                    if (intent == AudioIntent.MEDIA && episode != null) {
                        audioEpisodes.finishAudioClaim(episode, acquired, claimRevision)
                    }
                }
                if (!alive()) return
                if (!invalidated) {
                    lastAudioAttemptRevision = intentRevision
                    lastAudioResourceRevision = if (acquired) client.audioResourceRevision else resourceRevision
                }
                synchronized(lock) {
                    if (acquired && episode?.isCurrent == true && audioIntentRevision == intentRevision) {
                        pendingAudioIntent = null
                    }
                    if (invalidated && episode?.isCurrent == true && !closed) {
                        audioWorker.execute(::applyAudioOwnership)
                    }
                }
                log("AUDIO-INTENT: intent=$intent acquired=$acquired invalidated=$invalidated ${client.audioResourceDescription}")
                if (!acquired && !invalidated) onAudioStatus("车机暂未允许音频")
            }
            val returnRevision = client.audioResourceRevision
            val releaseBorrow =
                synchronized(lock) {
                    releaseNavigationBorrow &&
                        !navigationPlaying &&
                        mediaEpisode == null &&
                        (audioIntentRevision != lastReturnIntentRevision || returnRevision != lastReturnResourceRevision)
                }
            if (releaseBorrow && client.hasBorrowedAudioResource) {
                if (audioActivated) {
                    audioActivated = onAudioOwnership(false) { false }
                    if (audioActivated) {
                        onAudioStatus("音频仍活动，暂不归还导航资源")
                        return
                    }
                }
                if (synchronized(lock) { navigationPlaying || mediaEpisode != null }) return
                var invalidated = false
                val returned = client.releaseAudioBorrow(onInvalidated = { invalidated = true })
                if (!alive()) return
                if (!invalidated) {
                    lastReturnIntentRevision = intentRevision
                    lastReturnResourceRevision = if (returned) client.audioResourceRevision else returnRevision
                }
                synchronized(lock) {
                    if (returned && !navigationPlaying && audioIntentRevision == intentRevision) {
                        releaseNavigationBorrow = false
                    }
                    if (invalidated && !navigationPlaying && !closed) audioWorker.execute(::applyAudioOwnership)
                }
                if (!returned && !invalidated) onAudioStatus("导航音频归还未确认")
            } else if (releaseBorrow) {
                synchronized(lock) {
                    if (!navigationPlaying && mediaEpisode != null) releaseNavigationBorrow = false
                }
            }
            if (!alive()) return
            val playback = audioEpisodes.snapshot()
            val carTakeRevision = playback.carTakeRevision
            val alreadyActivated = audioActivated
            val currentGrant = {
                alive() &&
                    client.ownsAudioResource &&
                    audioEpisodes.isCarTakeRevisionCurrent(carTakeRevision) &&
                    (alreadyActivated || audioEpisodes.hasPlaybackIntent())
            }
            val owned =
                client.ownsAudioResource &&
                    (alreadyActivated || playback.media != null || playback.navigation != null)
            wantedAudio = client.ownsAudioResource
            if (audioActivated == owned && !(attempted && owned)) return
            val activated = onAudioOwnership(owned, currentGrant)
            audioActivated = owned && activated
        } catch (error: Exception) {
            if (alive()) {
                log("AUDIO-INTENT: 异常 ${error.javaClass.simpleName}:${error.message}")
                onAudioStatus("音频协商失败，详见日志")
                fail("音频资源事务异常，终止会话以回收接收端资源")
            }
        }
    }

    private fun retireScreen(reason: String): Boolean {
        log("MODE: 停止屏幕流（$reason）")
        screenPaused = true
        screenActive = false
        if (alive()) onScreenPaused()
        sender?.stop()
        sender = null
        if (!screenNegotiated) return alive()
        screenNegotiated = false
        if (!alive()) return false
        if (!client.teardownStream(110, displayUuid)) {
            if (alive()) fail("主屏资源让出时 TEARDOWN 失败")
            return false
        }
        return alive()
    }

    private fun resumeScreen() {
        val revision =
            synchronized(lock) {
                if (!alive() || !wantedScreen) return
                screenRevision
            }
        val screen = client.runScreenStream(sessionKey, displayUuid)
        if (!alive()) return
        if (screen == null) {
            fail("主屏资源归还后 SETUP 失败")
            return
        }
        screenNegotiated = true
        val replacement = createSender(screen)
        sender = replacement
        if (!resumeStillWanted(revision)) {
            retireScreen("SETUP 后主屏归属已变更")
            return
        }
        var recorded = false
        val started =
            replacement.openDataPort() &&
                client.record().also { recorded = it } &&
                replacement.startPassthrough()
        if (!started) {
            replacement.stop()
            if (alive()) fail("主屏资源归还后视频通道未能恢复")
            return
        }
        if (recorded) onRecorded()
        if (!resumeStillWanted(revision)) {
            retireScreen("RECORD 后主屏归属已变更")
            return
        }
        screenPaused = false
        if (!onScreenResumed(replacement)) {
            retireScreen("屏幕恢复挂载被拒")
            return
        }
        val accepted =
            synchronized(lock) {
                if (resumeStillWanted(revision)) {
                    screenActive = true
                    screenPaused = false
                    true
                } else {
                    false
                }
            }
        if (!accepted) retireScreen("挂载期间主屏归属已变更")
    }

    private fun resumeStillWanted(revision: Long): Boolean = alive() && wantedScreen && screenRevision == revision
}
