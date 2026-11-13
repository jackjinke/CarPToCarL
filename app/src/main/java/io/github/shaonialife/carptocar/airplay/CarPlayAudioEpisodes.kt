package io.github.shaonialife.carptocar.airplay

/**
 * 源端播放周期追踪：跨 AirPlay 会话与流辅助器保留「当前是否为一次新的用户播放意图」。
 *
 * 车机显式接管或静音借用主音频后，旧播放周期即告失效，避免源端状态重放被误当成新启动而反抢车机音源。
 */
internal class CarPlayAudioEpisodes {
    class Episode internal constructor() {
        @Volatile
        var isCurrent: Boolean = true
            internal set
    }

    data class Snapshot(
        val media: Episode?,
        val navigation: Episode?,
        val carTakeRevision: Long,
    )

    /** 车机音频资源转移对手机侧媒体播放器的联动动作。 */
    enum class MediaCommand {
        NONE,
        PAUSE,
        RESUME,
    }

    private var mediaPlaying = false
    private var navigationPlaying = false
    private var media: Episode? = null
    private var navigation: Episode? = null
    private var claimedMedia: Episode? = null
    private var inFlightMediaClaims = 0
    private var carTakeRevision = 0L
    private var pausedByCarAudioBorrow = false

    /** 显式播放指令可通过 [newMediaIntent] 在布尔值未翻转时刷新新的播放周期。 */
    @Synchronized
    fun setPlayback(
        media: Boolean,
        navigation: Boolean,
        newMediaIntent: Boolean = false,
    ) {
        if (media != mediaPlaying || (newMediaIntent && media)) {
            this.media?.isCurrent = false
            this.media = if (media) Episode() else null
            claimedMedia = null
            inFlightMediaClaims = 0
            if (media) pausedByCarAudioBorrow = false
            mediaPlaying = media
        }
        if (navigation != navigationPlaying) {
            this.navigation?.isCurrent = false
            this.navigation = if (navigation) Episode() else null
            navigationPlaying = navigation
        }
    }

    /** 仅当主音频申请被车机实际接受后才消费该媒体周期；后台探测失败或 `/info` 初始归属不消费。 */
    @Synchronized
    fun audioClaimed(
        episode: Episode? = null,
        expectedCarTakeRevision: Long? = null,
    ) {
        val claimed = episode ?: media
        if (claimed != null && media === claimed && claimed.isCurrent) {
            if (expectedCarTakeRevision != null && expectedCarTakeRevision != carTakeRevision) {
                claimed.isCurrent = false
            } else {
                claimedMedia = claimed
            }
        }
    }

    /** 登记一次在飞的媒体音频申请；车机在此期间接管音频可立即作废该周期。 */
    @Synchronized
    fun beginAudioClaim(episode: Episode): Long {
        if (media === episode) inFlightMediaClaims++
        return carTakeRevision
    }

    @Synchronized
    fun finishAudioClaim(
        episode: Episode,
        acquired: Boolean,
        expectedCarTakeRevision: Long,
    ) {
        if (acquired) audioClaimed(episode, expectedCarTakeRevision)
        if (media === episode) inFlightMediaClaims--
    }

    /** 车机显式接管或受限借用主音频时调用，作废已认领或在飞的旧媒体周期。 */
    @Synchronized
    fun carTookAudio() {
        carTakeRevision++
        val current = media
        if (current != null && (claimedMedia === current || inFlightMediaClaims > 0)) {
            current.isCurrent = false
        }
    }

    @Synchronized
    fun resetBorrowState() {
        pausedByCarAudioBorrow = false
    }

    /**
     * 处理车机下发的 `MainAudio` 归属转移：
     *  - 永久接管（如切到原车 FM）：作废旧媒体周期并暂停播放，后续不再因解除静音自动恢复；
     *  - 受限借用（如按下车机静音键 `unborrowConstraint >= 500` 或原车通话）：记录借用前是否在播，暂停媒体并禁止导航抢回；
     *  - 归还手机（如再次按静音键解除静音）：仅当借用前确由本机在播时刷新媒体周期并恢复播放。
     */
    @Synchronized
    fun onCarAudioTransfer(
        transfer: AirPlayResourceState.CarAudioTransfer,
        currentlyPlayingFallback: Boolean = false,
    ): MediaCommand {
        val wasPlaying = mediaPlaying || currentlyPlayingFallback
        return when (transfer) {
            AirPlayResourceState.CarAudioTransfer.TAKEN_PERMANENTLY -> {
                pausedByCarAudioBorrow = false
                carTookAudio()
                if (wasPlaying) {
                    setPlayback(media = false, navigation = navigationPlaying)
                    MediaCommand.PAUSE
                } else {
                    MediaCommand.NONE
                }
            }

            AirPlayResourceState.CarAudioTransfer.BORROWED_CONSTRAINED -> {
                if (wasPlaying) pausedByCarAudioBorrow = true
                carTookAudio()
                if (wasPlaying) {
                    setPlayback(media = false, navigation = navigationPlaying)
                    MediaCommand.PAUSE
                } else {
                    MediaCommand.NONE
                }
            }

            AirPlayResourceState.CarAudioTransfer.UNBORROWED_TO_PHONE -> {
                if (!pausedByCarAudioBorrow) {
                    MediaCommand.NONE
                } else {
                    pausedByCarAudioBorrow = false
                    setPlayback(media = true, navigation = navigationPlaying, newMediaIntent = true)
                    MediaCommand.RESUME
                }
            }
        }
    }

    @Synchronized
    fun currentCarTakeRevision(): Long = carTakeRevision

    @Synchronized
    fun hasPlaybackIntent(): Boolean = media?.isCurrent == true || navigation?.isCurrent == true

    @Synchronized
    fun isCarTakeRevisionCurrent(revision: Long): Boolean = revision == carTakeRevision

    @Synchronized
    fun snapshot(): Snapshot =
        Snapshot(
            media = media?.takeIf { it.isCurrent },
            navigation = navigation?.takeIf { it.isCurrent },
            carTakeRevision = carTakeRevision,
        )
}
