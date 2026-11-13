package io.github.shaonialife.carptocar.airplay

import android.os.SystemClock
import io.github.shaonialife.carptocar.video.VideoSender
import java.util.concurrent.Executors

/** Serializes accepted resource changes with stream lifetime. Borrowing the screen is not a
 * failed CarPlay session: stop that stream only, and negotiate a fresh stream/cipher on return.
 * The adapter trace pauses + TEARDOWNs at 160.529s, then SETUPs again at 163.370s.
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
) {
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "carplay-resource-streams").apply { isDaemon = true }
    }
    private val audioWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "carplay-audio-ownership").apply { isDaemon = true }
    }
    @Volatile private var closed = false
    @Volatile private var active = false
    private var audioActive = false
    private var screenRequested = initialSender == null
    @Volatile private var wantedScreen = client.ownsScreenResource
    @Volatile private var wantedAudio = client.ownsAudioResource
    @Volatile var screenPaused = !wantedScreen
        private set
    @Volatile private var sender: VideoSender? = initialSender
    @Volatile private var screenRevision = 0L
    private var screenNegotiated = initialSender != null
    private var screenActive = false
    private var audioActivated = false
    private val createdAt = SystemClock.elapsedRealtime()
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

    private fun state(): String = synchronized(lock) {
        "active=$active audioActive=$audioActive wantedScreen=$wantedScreen wantedAudio=$wantedAudio " +
            "pendingScreenIntent=$screenRequested revision=$screenRevision negotiated=$screenNegotiated " +
            "attached=$screenActive paused=$screenPaused senderRunning=${sender?.isRunning == true} " +
            "closed=$closed cancelled=${client.cancelled} controlAlive=${client.controlSessionAlive}"
    }

    private fun edge(message: String) {
        log("SCREEN-DIAG: $message helperElapsedMs=${SystemClock.elapsedRealtime() - createdAt} ${state()}")
    }

    /** Only state transitions call this; never stringify a wire result containing key material. */
    private inline fun <T> transition(name: String, operation: () -> T): T {
        val startedAt = SystemClock.elapsedRealtime()
        edge("$name begin")
        try {
            val result = operation()
            val outcome = when (result) {
                null -> "no-result"
                is Boolean -> result.toString()
                else -> "ready"
            }
            edge("$name end result=$outcome elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
            return result
        } catch (error: Exception) {
            edge("$name end result=exception error=${error.javaClass.simpleName} " +
                "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
            throw error
        }
    }

    private fun fail(reason: String) {
        edge("failure reason=${reason.replace('\n', ' ').replace('\r', ' ').take(240)}")
        onFailure(reason)
    }

    /** Screen handling starts before optional audio setup, which may wait for authentication. */
    fun activate(initialScreenAttached: Boolean) {
        val waiting = synchronized(lock) {
            if (closed || active) {
                edge("screen activation ignored initialAttached=$initialScreenAttached")
                return
            }
            screenActive = initialScreenAttached && sender?.isRunning == true
            screenPaused = !screenActive
            !screenNegotiated && !wantedScreen
        }
        edge("screen activation begin initialAttached=$initialScreenAttached waitingOwnership=$waiting")
        // Publish the initial wait before a return can asynchronously attach a producer.
        if (waiting && alive()) onScreenPaused()
        synchronized(lock) {
            if (closed || active) {
                edge("screen activation abandoned")
                return
            }
            active = true
            reconcile()
        }
        edge("screen activation end")
    }

    /** Seed retention only from a sender already activated by RECORD, never /info ownership. */
    fun activateAudio(initialAudioActivated: Boolean) {
        synchronized(lock) {
            if (closed || audioActive) {
                edge("audio activation ignored")
                return
            }
            audioActivated = initialAudioActivated
            audioActive = true
            edge("audio activation independentOfScreen=true")
            reconcile()
        }
    }

    /** Source episode identity is shared across helpers; replaying true is not a START. */
    fun refreshAudioPlayback() {
        val playback = audioEpisodes.snapshot()
        synchronized(lock) {
            if (closed || playback.media === mediaEpisode && playback.navigation === navigationEpisode) return
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
            log("AUDIO-INTENT: media=$media navigation=$navigation pending=$pendingAudioIntent " +
                "revision=$audioIntentRevision deferred=${!audioActive}")
            if (audioActive) audioWorker.execute(::applyAudioOwnership)
        }
    }

    /** requestUI is a user intent, not a grant: Never constraints remain authoritative. */
    fun requestScreen() {
        synchronized(lock) {
            if (closed) {
                edge("requestScreen received outcome=ignored-closed")
                return
            }
            val coalesced = screenRequested
            screenRequested = true
            edge("requestScreen received coalesced=$coalesced deferredUntilActivation=${!active}")
            if (active) worker.execute(::applyOwnership)
        }
    }

    /** State has already been committed and acknowledged by AirPlayClient's event reader. */
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
                edge("ownership reconciled screen=$previousScreen->$wantedScreen audio=$previousAudio->$wantedAudio")
            }
            if (active) worker.execute(::applyOwnership)
            if (audioActive) audioWorker.execute(::applyAudioOwnership)
        }
    }

    fun close() {
        synchronized(lock) {
            edge("close begin scope=resource-helper streamRetirementOnly=false alreadyClosed=$closed")
            closed = true
            screenPaused = true
            worker.shutdownNow()
            audioWorker.shutdownNow()
            sender?.stop()
        }
        edge("close end scope=resource-helper")
    }

    private fun alive(): Boolean = !closed && !client.cancelled && client.controlSessionAlive

    private fun applyOwnership() {
        if (!alive()) {
            edge("screen ownership work skipped reason=session-not-alive")
            return
        }
        try {
            val requested = synchronized(lock) {
                screenRequested.also { screenRequested = false }
            }
            if (requested) edge("screen intent consumed alreadyOwned=${client.ownsScreenResource}")
            if (requested && client.ownsScreenResource) edge("screen intent outcome=already-owned")
            if (requested && !client.ownsScreenResource) {
                var invalidated = false
                val acquired = transition("screen acquire") {
                    client.acquireScreenResource(onInvalidated = { invalidated = true })
                }
                if (!alive()) {
                    edge("screen acquire outcome=abandoned-session-ended")
                    return
                }
                if (!acquired && client.ownsScreenResource) {
                    fail("主屏取得后资源状态通知失败")
                    return
                }
                synchronized(lock) {
                    if (!acquired) screenRequested = true
                    if (invalidated && !closed) worker.execute(::applyOwnership)
                    val screen = client.ownsScreenResource
                    if (screen != wantedScreen) {
                        wantedScreen = screen
                        screenRevision++
                    }
                }
                edge("screen acquire outcome=${if (acquired) "granted" else if (invalidated) "app-state-invalidated" else "denied-or-constrained"} " +
                    "retryPolicy=${if (acquired) "none" else if (invalidated) "current-state" else "next-resource-event"}")
            }
            if (wantedScreen != screenActive) {
                if (!transition("screen assertModes") { client.assertModes() } && alive()) {
                    fail("资源状态通知失败")
                    return
                }
            }
            if (!alive()) return
            if (screenNegotiated && (!wantedScreen || !screenActive)) {
                if (!retireScreen(if (!wantedScreen) "ownership-released" else "negotiated-but-unattached")) return
            }
            if (wantedScreen && !screenNegotiated) resumeScreen()
            if (alive() && wantedScreen && screenActive) screenPaused = false
        } catch (error: Exception) {
            if (alive()) fail("资源切换失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun applyAudioOwnership() {
        if (!alive()) {
            edge("audio ownership work skipped reason=session-not-alive")
            return
        }
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
            val attempted = intent != null &&
                (intentRevision != lastAudioAttemptRevision || resourceRevision != lastAudioResourceRevision)
            if (attempted) {
                val claimRevision = if (intent == AudioIntent.MEDIA && episode != null) {
                    audioEpisodes.beginAudioClaim(episode)
                } else audioEpisodes.snapshot().carTakeRevision
                var invalidated = false
                var acquired = false
                try {
                    onAudioStatus(if (intent == AudioIntent.MEDIA) "申请媒体音频" else "申请导航临时音频")
                    acquired = episode?.isCurrent == true && client.acquireAudioResource(
                        priority = 500,
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
                    if (acquired && episode?.isCurrent == true && audioIntentRevision == intentRevision) pendingAudioIntent = null
                    if (invalidated && episode?.isCurrent == true && !closed) {
                        // A newer app-state snapshot, not a resource denial: enqueue one
                        // current-episode retry. Explicit car takes never use this path.
                        audioWorker.execute(::applyAudioOwnership)
                    }
                }
                log("AUDIO-INTENT: intent=$intent acquired=$acquired invalidated=$invalidated ${client.audioResourceDescription}")
                if (!acquired && !invalidated) onAudioStatus("车机暂未允许音频")
            }
            val returnRevision = client.audioResourceRevision
            val releaseBorrow = synchronized(lock) {
                releaseNavigationBorrow && !navigationPlaying && mediaEpisode == null &&
                    (audioIntentRevision != lastReturnIntentRevision || returnRevision != lastReturnResourceRevision)
            }
            if (releaseBorrow && client.hasBorrowedAudioResource) {
                // Stop the sender before publishing that radio owns the resource. Doing
                // this only after the response permits RTP to race the accepted return.
                if (audioActivated) {
                    audioActivated = transition("audio retire before borrow return") {
                        onAudioOwnership(false, { false })
                    }
                    if (audioActivated) {
                        onAudioStatus("音频仍活动，暂不归还导航资源")
                        return
                    }
                }
                // A new prompt or media action can arrive while retirement waits for I/O;
                // its queued ownership work must run without returning its grant here.
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
                // Navigation did not borrow a permanently phone-owned media grant.
                // Its STOP must not retire the stream or erase its activation lease.
                synchronized(lock) {
                    if (!navigationPlaying && mediaEpisode == null) releaseNavigationBorrow = false
                }
            }
            if (!alive()) return
            val playback = audioEpisodes.snapshot()
            val carTakeRevision = playback.carTakeRevision
            val alreadyActivated = audioActivated
            // A track/PCM gap is not loss of the receiver resource. Keeping the stream
            // retains its encoder, cipher and sample clock; explicit car TAKE still
            // invalidates the callback even if ownership returns before SETUP finishes.
            val currentGrant = {
                alive() && client.ownsAudioResource &&
                    audioEpisodes.isCarTakeRevisionCurrent(carTakeRevision) &&
                    (alreadyActivated || audioEpisodes.hasPlaybackIntent())
            }
            val owned = client.ownsAudioResource &&
                (alreadyActivated || playback.media != null || playback.navigation != null)
            wantedAudio = client.ownsAudioResource
            if (audioActivated == owned && !(attempted && owned)) return
            edge("audio ownership begin activated=$audioActivated wanted=$owned independentOfScreen=true")
            val activated = transition("audio ownership callback owned=$owned") { onAudioOwnership(owned, currentGrant) }
            audioActivated = owned && activated
            edge("audio ownership end activated=$audioActivated wanted=$owned independentOfScreen=true")
        } catch (error: Exception) {
            if (alive()) {
                log("AUDIO-INTENT: failed ${error.javaClass.simpleName}:${error.message}")
                onAudioStatus("音频协商失败，详见日志")
                fail("音频资源事务异常，终止会话以回收接收端资源")
            }
        }
    }

    private fun retireScreen(reason: String): Boolean {
        edge("screen retirement begin reason=$reason scope=screen-stream fullSessionClose=false")
        screenPaused = true
        screenActive = false
        if (alive()) onScreenPaused()
        sender?.stop()
        sender = null
        if (!screenNegotiated) {
            edge("screen retirement end teardown=not-negotiated")
            return alive()
        }
        // Retirement is unconditional once begun: a later return cannot reuse this stopped stream.
        screenNegotiated = false
        if (!alive()) {
            edge("screen retirement end teardown=skipped-session-ended")
            return false
        }
        if (!transition("screen TEARDOWN") { client.teardownStream(110, displayUuid) }) {
            edge("screen retirement end teardown=failed")
            if (alive()) fail("主屏资源让出时 TEARDOWN 失败")
            return false
        }
        edge("screen retirement end teardown=complete")
        return alive()
    }

    private fun resumeScreen() {
        val revision = synchronized(lock) {
            if (!alive() || !wantedScreen) {
                edge("screen resume skipped reason=${if (!alive()) "session-not-alive" else "awaiting-ownership"}")
                return
            }
            screenRevision
        }
        edge("screen resume begin expectedRevision=$revision")
        val screen = transition("screen fresh SETUP") { client.runScreenStream(sessionKey, displayUuid) }
        if (!alive()) {
            edge("screen resume end outcome=session-ended-after-setup")
            return
        }
        if (screen == null) {
            fail("主屏资源归还后 SETUP 失败")
            return
        }
        screenNegotiated = true
        val replacement = createSender(screen)
        sender = replacement
        if (!resumeStillWanted(revision)) {
            retireScreen("ownership-changed-after-setup expectedRevision=$revision")
            return
        }
        if (!transition("screen data-port open") { replacement.openDataPort() } ||
            !transition("screen fresh RECORD") { client.record() } ||
            !transition("screen producer start") { replacement.startPassthrough() }
        ) {
            replacement.stop()
            if (alive()) fail("主屏资源归还后视频通道未能恢复")
            return
        }
        if (!resumeStillWanted(revision)) {
            retireScreen("ownership-changed-after-record expectedRevision=$revision")
            return
        }
        screenPaused = false
        if (!transition("screen attachment callback") { onScreenResumed(replacement) }) {
            retireScreen("attachment-callback-rejected")
            return
        }
        val accepted = synchronized(lock) {
            if (resumeStillWanted(revision)) {
                screenActive = true
                screenPaused = false
                true
            } else {
                false
            }
        }
        edge("screen resume end attachmentAccepted=$accepted expectedRevision=$revision")
        if (!accepted) retireScreen("ownership-changed-during-attachment expectedRevision=$revision")
    }

    private fun resumeStillWanted(revision: Long): Boolean =
        alive() && wantedScreen && screenRevision == revision
}
