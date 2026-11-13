package io.github.shaonialife.carptocar.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AirPlay 资源归属与音频播放周期仲裁单测：
 *  - `/info.modes` 初始归属、`changeModes` 四类转移语义、发布失败/并发车机抢占回滚；
 *  - 车机静音受限借用（`unborrowConstraint=500`）联动暂停媒体、阻止导航临时抢声、解除静音后按需恢复；
 *  - 倒车雷达/影像高约束借屏（`unborrowConstraint=1000`）结束后经车机 `requestUI` 主动归还屏幕。
 */
class AirPlayResourceStateTest {
    @Test
    fun `默认初始化保持手机持有主屏与主音频`() {
        val state = AirPlayResourceState()
        assertTrue(state.initialize(null))
        assertTrue(state.phoneOwnsScreen)
        assertTrue(state.phoneOwnsAudio)
        assertResource(state, 1, 1, 1)
        assertResource(state, 2, 1, 1)
        assertEquals(-1L, appState(state, 1)["speechMode"])
    }

    @Test
    fun `车机显式接管音频会作废在飞的媒体取权但保留尚未认领的新意图`() {
        val episodes = CarPlayAudioEpisodes()
        episodes.setPlayback(media = true, navigation = false)
        val episode = episodes.snapshot().media!!
        episodes.carTookAudio()
        assertTrue(episode.isCurrent)
        val claimRevision = episodes.beginAudioClaim(episode)
        val state = AirPlayResourceState()
        var firstPublication = true
        val acquired =
            state.acquireAudio(500, isCurrentIntent = { episode.isCurrent }) {
                if (firstPublication) {
                    firstPublication = false
                    state.change(mapOf("resources" to listOf(take(2))))
                    episodes.carTookAudio()
                }
                true
            }
        episodes.finishAudioClaim(episode, acquired, claimRevision)
        assertFalse(acquired)
        assertFalse(episode.isCurrent)
        assertFalse(state.hasAudio())
        episodes.setPlayback(media = true, navigation = false)
        assertEquals(null, episodes.snapshot().media)
        episodes.setPlayback(media = false, navigation = false)
        episodes.setPlayback(media = true, navigation = false)
        assertTrue(episodes.snapshot().media?.isCurrent == true)
    }

    @Test
    fun `车机初始声明接管双资源时，申请主屏不会顺带抢走主音频`() {
        val state = AirPlayResourceState()
        assertTrue(state.initialize(mapOf("resources" to listOf(take(1), take(2)))))
        assertResource(state, 1, 2, 2)
        assertResource(state, 2, 2, 2)
        assertTrue(state.acquireScreen())
        assertResource(state, 1, 1, 1)
        assertResource(state, 2, 2, 2)
    }

    @Test
    fun `takeConstraint 限制生效，Never 约束禁止发布`() {
        val state = AirPlayResourceState()
        assertTrue(
            state.initialize(
                mapOf(
                    "initialPermanentEntity" to
                        listOf(
                            mapOf("resourceID" to 1, "permanentEntity" to 2, "takeConstraint" to 1000),
                        ),
                ),
            ),
        )
        assertFalse(state.acquireScreen())
        assertResource(state, 1, 2, 2)

        val audioBlocked = AirPlayResourceState()
        audioBlocked.initialize(mapOf("resources" to listOf(take(2, 1000, 1000))))
        val failOnPublish: (Map<String, Any?>) -> Boolean = {
            throw AssertionError("受 1000 约束禁止的资源不应向车机发 modesChanged")
        }
        assertFalse(audioBlocked.acquireAudio(500, false, publish = failOnPublish))
        assertFalse(audioBlocked.acquireAudio(100, true, publish = failOnPublish))
        assertResource(audioBlocked, 2, 2, 2)
    }

    @Test
    fun `导航以低优先级临时借用原车收音机音频并在播报结束后归还`() {
        val state = AirPlayResourceState()
        // 原车 FM 收音机：owner=2, takeConstraint=500, borrowConstraint=100
        state.initialize(mapOf("resources" to listOf(take(id = 2, constraint = 500, borrow = 100))))
        assertTrue(state.acquireAudio(priority = 100, temporary = true))
        assertResource(state, 2, 1, 2)
        assertTrue(state.hasPhoneAudioBorrow())
        assertTrue(state.releaseAudioBorrow { true })
        assertResource(state, 2, 2, 2)
    }

    @Test
    fun `车机静音键受限借用主音频时暂停音乐、阻止导航打断静音并在解除静音后恢复`() {
        val state = AirPlayResourceState()
        val episodes = CarPlayAudioEpisodes()
        state.initialize(null)
        episodes.setPlayback(media = true, navigation = false)

        // 1. 按下车机静音键：transferType=3 (Borrow), resourceID=2, unborrowConstraint=500
        val muteBorrow =
            mapOf(
                "resources" to
                    listOf(
                        mapOf(
                            "resourceID" to 2L,
                            "transferType" to 3L,
                            "transferPriority" to 500L,
                            "unborrowConstraint" to 500L,
                        ),
                    ),
            )
        val (muteReply, muteTransfer) = state.changeWithAudioTransfer(muteBorrow)
        assertEquals(0L, muteReply["status"])
        assertEquals(AirPlayResourceState.CarAudioTransfer.BORROWED_CONSTRAINED, muteTransfer)
        assertResource(state, 2, 2, 1)
        assertEquals(CarPlayAudioEpisodes.MediaCommand.PAUSE, episodes.onCarAudioTransfer(muteTransfer!!))
        assertFalse("静音后旧媒体周期应已停下", episodes.hasPlaybackIntent())

        // 重复收到一次受限借用通知不应抹掉「由静音暂停」的记忆
        val (_, duplicateBorrow) = state.changeWithAudioTransfer(muteBorrow)
        assertEquals(AirPlayResourceState.CarAudioTransfer.BORROWED_CONSTRAINED, duplicateBorrow)
        assertEquals(CarPlayAudioEpisodes.MediaCommand.NONE, episodes.onCarAudioTransfer(duplicateBorrow!!))

        // 2. 静音期间导航 TTS 播报到达：priority=100 < unborrowConstraint=500，绝不能强拆静音
        episodes.setPlayback(media = false, navigation = true)
        var publishedWhileMuted = false
        assertFalse(
            "静音期间导航临时借声不得冲破 unborrowConstraint=500",
            state.acquireAudio(priority = 100, temporary = true) {
                publishedWhileMuted = true
                true
            },
        )
        assertFalse(publishedWhileMuted)
        assertFalse(state.phoneOwnsAudio)

        // 3. 再次按下车机静音键解除静音（两层匿名借用依次归还）：回到手机并恢复此前暂停的音乐
        val unborrow = mapOf("resources" to listOf(mapOf("resourceID" to 2L, "transferType" to 4L)))
        val (_, firstUnborrow) = state.changeWithAudioTransfer(unborrow)
        assertEquals(null, firstUnborrow)
        val (unmuteReply, unmuteTransfer) = state.changeWithAudioTransfer(unborrow)
        assertEquals(0L, unmuteReply["status"])
        assertEquals(AirPlayResourceState.CarAudioTransfer.UNBORROWED_TO_PHONE, unmuteTransfer)
        assertTrue(state.phoneOwnsAudio)
        assertEquals(CarPlayAudioEpisodes.MediaCommand.RESUME, episodes.onCarAudioTransfer(unmuteTransfer!!))
        assertTrue("解除静音后应生成新的媒体播放周期", episodes.snapshot().media?.isCurrent == true)
    }

    @Test
    fun `静音前本来未在播音乐或车机永久切走音源时，解除借用不得误启动音乐`() {
        val state = AirPlayResourceState()
        val episodes = CarPlayAudioEpisodes()
        state.initialize(null)

        // 场景 A：静音前音乐本来处于暂停态
        val (_, idleMute) =
            state.changeWithAudioTransfer(
                mapOf(
                    "resources" to
                        listOf(
                            mapOf(
                                "resourceID" to 2L,
                                "transferType" to 3L,
                                "transferPriority" to 500L,
                                "unborrowConstraint" to 500L,
                            ),
                        ),
                ),
            )
        assertEquals(CarPlayAudioEpisodes.MediaCommand.NONE, episodes.onCarAudioTransfer(idleMute!!))
        val (_, idleUnmute) =
            state.changeWithAudioTransfer(
                mapOf("resources" to listOf(mapOf("resourceID" to 2L, "transferType" to 4L))),
            )
        assertEquals(CarPlayAudioEpisodes.MediaCommand.NONE, episodes.onCarAudioTransfer(idleUnmute!!))
        assertFalse(episodes.hasPlaybackIntent())

        // 场景 B：音乐播放中用户切到原车 FM（transferType=1 永久接管）
        episodes.setPlayback(media = true, navigation = false)
        val (_, permanentTake) = state.changeWithAudioTransfer(mapOf("resources" to listOf(take(2))))
        assertEquals(AirPlayResourceState.CarAudioTransfer.TAKEN_PERMANENTLY, permanentTake)
        assertEquals(CarPlayAudioEpisodes.MediaCommand.PAUSE, episodes.onCarAudioTransfer(permanentTake!!))
        assertEquals(
            CarPlayAudioEpisodes.MediaCommand.NONE,
            episodes.onCarAudioTransfer(AirPlayResourceState.CarAudioTransfer.UNBORROWED_TO_PHONE),
        )
    }

    @Test
    fun `倒车雷达高约束借屏期间禁止私自抢屏，但收到车机 requestUI 时允许主动取回主屏`() {
        val state = AirPlayResourceState()
        state.initialize(null)
        // PDC 借走主屏（transferType=3, unborrowConstraint=1000），永久归属仍为手机（owner=1）
        assertEquals(0L, state.change(mapOf("resources" to listOf(screenBorrow)))["status"])
        assertResource(state, 1, 2, 1)
        assertFalse("未收到 requestUI 前不得突破 unborrowConstraint=1000 私自抢屏", state.acquireScreen(fromRequestUi = false))
        assertResource(state, 1, 2, 1)

        // 倒车结束，车机主动发 requestUI 请手机恢复画面
        assertTrue("车机 requestUI 应解除临时借屏并归还主屏", state.acquireScreen(fromRequestUi = true))
        assertTrue(state.phoneOwnsScreen)
        assertResource(state, 1, 1, 1)

        // 若车机是永久接管且禁止抢占（owner=2, takeConstraint=1000），即使 fromRequestUi 也受 takeConstraint 约束
        state.change(mapOf("resources" to listOf(take(id = 1, constraint = 1000))))
        assertFalse(state.acquireScreen(fromRequestUi = true))
        assertResource(state, 1, 2, 2)
    }

    @Test
    fun `发布期间车机改判或本机应用态变化不会提交过期归属`() {
        val state = AirPlayResourceState()
        state.initialize(mapOf("resources" to listOf(take(2))))
        var retry = false
        assertFalse(
            state.acquireAudio(500, onInvalidated = { retry = true }) {
                state.setOwnSpeech(AirPlaySpeechMode.RECOGNIZING)
                true
            },
        )
        assertTrue(retry)
        assertFalse(state.hasAudio())

        retry = false
        assertFalse(
            state.acquireAudio(500, onInvalidated = { retry = true }) {
                state.change(mapOf("resources" to listOf(take(2, 1000))))
                true
            },
        )
        assertFalse(retry)
        assertFalse(state.hasAudio())
    }

    @Test
    fun `具名借屏幂等且嵌套匿名借屏按栈恢复外层约束`() {
        val state = AirPlayResourceState()
        val named = screenBorrow + ("borrowID" to "pdc")
        repeat(2) { assertEquals(0L, state.change(mapOf("resources" to listOf(named)))["status"]) }
        assertResource(state, 1, 2, 1)
        assertResource(state, 2, 1, 1)
        state.change(mapOf("resources" to listOf(mapOf("resourceID" to 1L, "transferType" to 4L, "borrowID" to "pdc"))))
        assertResource(state, 1, 1, 1)

        val outer = screenBorrow + ("unborrowConstraint" to 500L)
        state.change(mapOf("resources" to listOf(outer)))
        state.change(mapOf("resources" to listOf(screenBorrow)))
        assertFalse(state.acquireScreen())
        state.change(mapOf("resources" to listOf(mapOf("resourceID" to 1L, "transferType" to 4L))))
        assertTrue(state.acquireScreen())
        assertResource(state, 1, 1, 1)
    }

    @Test
    fun `非法事务整体回滚，本机语音与通话应用态优先于车机回显`() {
        val state = AirPlayResourceState()
        assertEquals(1L, state.change(mapOf("resources" to listOf(take(1), take(99))))["status"])
        assertResource(state, 1, 1, 1)

        state.initialize(
            mapOf("appStates" to listOf(mapOf("appStateID" to 1, "speechMode" to 1))),
        )
        assertEquals(2L, appState(state, 1)["entity"])
        assertTrue(state.setOwnSpeech(AirPlaySpeechMode.RECOGNIZING))
        assertEquals(1L, appState(state, 1)["entity"])
        assertEquals(2L, appState(state, 1)["speechMode"])
        assertFalse(state.setOwnSpeech(AirPlaySpeechMode.RECOGNIZING))
        state.setOwnSpeech(AirPlaySpeechMode.NONE)
        assertEquals(2L, appState(state, 1)["entity"])

        assertTrue(state.setOwnPhoneCall(true))
        assertEquals(1L, appState(state, 2)["entity"])
        assertEquals(-1L, appState(state, 2)["speechMode"])
        state.initialize(null)
        assertEquals(AirPlaySpeechMode.NONE, state.ownSpeechMode)
        assertFalse(state.ownPhoneCallActive)
    }

    private fun appState(
        state: AirPlayResourceState,
        id: Int,
    ): Map<*, *> =
        (state.snapshot()["appStates"] as List<*>)
            .map { it as Map<*, *> }
            .first { it["appStateID"] == id.toLong() }

    private val screenBorrow =
        mapOf(
            "resourceID" to 1L,
            "transferType" to 3L,
            "transferPriority" to 500L,
            "unborrowConstraint" to 1000L,
        )

    private fun take(
        id: Long,
        constraint: Long = 500,
        borrow: Long = 100,
    ) = mapOf(
        "resourceID" to id,
        "transferType" to 1L,
        "transferPriority" to 500L,
        "takeConstraint" to constraint,
        "borrowConstraint" to borrow,
    )

    private fun assertResource(
        state: AirPlayResourceState,
        id: Long,
        entity: Long,
        owner: Long,
    ) {
        val resources = state.snapshot()["resources"] as List<*>
        val resource = resources.first { (it as Map<*, *>)["resourceID"] == id } as Map<*, *>
        assertEquals(entity, resource["entity"])
        assertEquals(owner, resource["permanentEntity"])
    }
}
