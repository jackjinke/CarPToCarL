package io.github.shaonialife.carptocar.airplay

/** Source playback episodes outlive AirPlay helpers and transports. A replayed active
 * source snapshot is not a new user intent, especially after the car selected radio.
 */
internal class CarPlayAudioEpisodes {
    class Episode internal constructor() {
        @Volatile var isCurrent: Boolean = true
            internal set
    }

    data class Snapshot(
        val media: Episode?,
        val navigation: Episode?,
        val carTakeRevision: Long,
    )

    private var mediaPlaying = false
    private var navigationPlaying = false
    private var media: Episode? = null
    private var navigation: Episode? = null
    private var claimedMedia: Episode? = null
    private var inFlightMediaClaims = 0
    private var carTakeRevision = 0L

    /** An explicit play action can renew an invalidated episode without a Boolean edge. */
    @Synchronized
    fun setPlayback(media: Boolean, navigation: Boolean, newMediaIntent: Boolean = false) {
        if (media != mediaPlaying || newMediaIntent && media) {
            this.media?.isCurrent = false
            this.media = if (media) Episode() else null
            claimedMedia = null
            inFlightMediaClaims = 0
            mediaPlaying = media
        }
        if (navigation != navigationPlaying) {
            this.navigation?.isCurrent = false
            this.navigation = if (navigation) Episode() else null
            navigationPlaying = navigation
        }
    }

    /** Only a successful acquisition may consume this episode. Failed background takes
     * and initial /info ownership cannot suppress a real later playback request.
     */
    @Synchronized
    fun audioClaimed(episode: Episode? = null, expectedCarTakeRevision: Long? = null) {
        val claimed = episode ?: media
        if (claimed != null && media === claimed && claimed.isCurrent) {
            if (expectedCarTakeRevision != null && expectedCarTakeRevision != carTakeRevision) {
                claimed.isCurrent = false
            } else claimedMedia = claimed
        }
    }

    /** Only an actual media-priority claim participates, never an unsuccessful background
     * probe. An explicit car TAKE supersedes this request even before it can commit.
     */
    @Synchronized
    fun beginAudioClaim(episode: Episode): Long {
        if (media === episode) inFlightMediaClaims++
        return carTakeRevision
    }

    @Synchronized
    fun finishAudioClaim(episode: Episode, acquired: Boolean, expectedCarTakeRevision: Long) {
        if (acquired) audioClaimed(episode, expectedCarTakeRevision)
        if (media === episode) inFlightMediaClaims--
    }

    /** Called only for an accepted explicit car TAKE, never for an initial snapshot. */
    @Synchronized
    fun carTookAudio() {
        carTakeRevision++
        val current = media
        if (current != null && (claimedMedia === current || inFlightMediaClaims > 0)) current.isCurrent = false
    }

    @Synchronized
    fun currentCarTakeRevision(): Long = carTakeRevision

    @Synchronized
    fun hasPlaybackIntent(): Boolean = media?.isCurrent == true || navigation?.isCurrent == true

    @Synchronized
    fun isCarTakeRevisionCurrent(revision: Long): Boolean = revision == carTakeRevision

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        media = media?.takeIf { it.isCurrent },
        navigation = navigation?.takeIf { it.isCurrent },
        carTakeRevision = carTakeRevision,
    )
}
