package io.github.shaonialife.carptocar.carlife

/** Facts from CarLife's command/media sockets, not guesses from elapsed time or audio amplitude. */
enum class CarLifeMediaActivity {
    INITIALIZED, PCM, STOP, PAUSE, RESUME, FOCUS_GAINED, FOCUS_RELEASED, TRANSPORT_CLOSED,
}

/**
 * Correlates the two independent CarLife sockets into one observable playback episode.
 *
 * CarLife+ 8.8.5 MusicAudioHelper.onMusicSocketInit sends INIT/STOP/INIT on the old-vehicle
 * path; onMusicGetFocus reports module 3 = 1. AudioPlayTask.streamPlay initializes and
 * writes PCM in its playing path, but the resampling path need not send RESUME. Thus neither
 * INIT, module focus, nor arbitrary PCM alone is play intent: all three must agree.
 *
 * A focus episode can authorize only once. A format rebuild, residual PCM, duplicate module
 * snapshot or socket replacement cannot reclaim radio after the car explicitly yielded us.
 * Only a fresh focus release/gain or explicit RESUME can authorize another episode.
 * Access is serialized by CarLifeHijack's audioPlaybackLock.
 */
internal class CarLifeMediaPlayback {
    private var focused: Boolean? = null
    private var eligible = true
    private var initialized = false
    private var pcmAfterInit = false
    private var wirePlaying = false
    private var sessionPlaying: Boolean? = null
    private var lastKnownSessionPlaying: Boolean? = null
    private var sourcePaused = false

    val playing: Boolean get() = sessionPlaying ?: wirePlaying
    val canForwardPcm: Boolean get() = sessionPlaying != false && !sourcePaused

    fun onSessionPlayback(playing: Boolean?) {
        // A replayed PLAYING snapshot must not undo a later native PAUSE. Only a
        // real known paused→playing transition supplies independent resume evidence.
        if (playing == true && lastKnownSessionPlaying == false) sourcePaused = false
        if (playing != null) lastKnownSessionPlaying = playing
        sessionPlaying = playing
        if (playing != null) {
            // Losing notification access must not turn previously vetoed wire data into a tap.
            eligible = false
            if (!playing) {
                sourcePaused = true
                wirePlaying = false
            }
        }
    }

    fun onActivity(activity: CarLifeMediaActivity) {
        when (activity) {
            CarLifeMediaActivity.INITIALIZED -> {
                initialized = true
                pcmAfterInit = false
            }
            CarLifeMediaActivity.PCM -> if (initialized) {
                pcmAfterInit = true
            }
            CarLifeMediaActivity.STOP -> {
                // STOP is also a track/format boundary: sources can resume PCM without
                // a fresh module-focus edge or RESUME. Keep an authorized focus episode
                // continuous, so a track gap neither retires its stream nor creates a
                // new intent that could reclaim radio after an explicit car TAKE.
                // PAUSE, focus release and real MediaSession pause still end playback.
                initialized = false
                pcmAfterInit = false
            }
            CarLifeMediaActivity.PAUSE -> {
                sourcePaused = true
                wirePlaying = false
                eligible = false
                initialized = false
                pcmAfterInit = false
            }
            CarLifeMediaActivity.RESUME -> {
                if (sessionPlaying != false) sourcePaused = false
                eligible = false
                wirePlaying = sessionPlaying != false
            }
            CarLifeMediaActivity.FOCUS_GAINED -> {
                if (focused != true) {
                    // CMD and MEDIA are independent sockets: focus can overtake STOP.
                    // PCM observed before this focus cannot authorize a new episode.
                    pcmAfterInit = false
                    if (sessionPlaying != false) sourcePaused = false
                }
                focused = true
            }
            CarLifeMediaActivity.FOCUS_RELEASED -> {
                sourcePaused = true
                if (focused != false) {
                    eligible = true
                    pcmAfterInit = false
                }
                focused = false
                wirePlaying = false
            }
            CarLifeMediaActivity.TRANSPORT_CLOSED -> transportChanged()
        }
        if (eligible && focused == true && initialized && pcmAfterInit) {
            eligible = false
            wirePlaying = sessionPlaying != false
        }
    }

    /** Replace transport evidence, not the already-authorized logical playback episode. */
    fun transportChanged() {
        initialized = false
        pcmAfterInit = false
    }

    fun reset() {
        focused = null
        eligible = true
        sessionPlaying = null
        lastKnownSessionPlaying = null
        sourcePaused = false
        wirePlaying = false
        transportChanged()
    }
}
