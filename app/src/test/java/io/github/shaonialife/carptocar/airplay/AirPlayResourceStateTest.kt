package io.github.shaonialife.carptocar.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Failure modes: silent background takeover; losing permanent ownership on borrow;
 * duplicate named borrow; early anonymous return; partial invalid commit; rejected/stale
 * publication claiming resources; screen recovery stealing audio; state leaking across sessions;
 * mixed nested borrow constraints; local app state masking and restoring car state;
 * unrelated app-state invalidation retry versus denial or car-selected radio;
 * explicit car TAKE superseding in-flight media claims across reconnect and helper overlap.
 */
class AirPlayResourceStateTest {
    @Test
    fun `car TAKE cancels an in-flight first media claim but not an unclaimed episode`() {
        val episodes = CarPlayAudioEpisodes()
        episodes.setPlayback(media = true, navigation = false)
        val episode = episodes.snapshot().media!!
        episodes.carTookAudio()
        assertTrue(episode.isCurrent)
        val claimRevision = episodes.beginAudioClaim(episode)
        val state = AirPlayResourceState()
        var firstPublication = true
        val acquired = state.acquireAudio(500, isCurrentIntent = { episode.isCurrent }) {
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
    fun `retiring helper cannot clear replacement helper claim protection`() {
        val episodes = CarPlayAudioEpisodes()
        episodes.setPlayback(media = true, navigation = false)
        val episode = episodes.snapshot().media!!
        val oldClaim = episodes.beginAudioClaim(episode)
        val replacementClaim = episodes.beginAudioClaim(episode)
        episodes.finishAudioClaim(episode, acquired = false, expectedCarTakeRevision = oldClaim)
        episodes.carTookAudio()
        assertFalse(episode.isCurrent)
        episodes.finishAudioClaim(episode, acquired = false, expectedCarTakeRevision = replacementClaim)
        episodes.setPlayback(media = true, navigation = false)
        assertEquals(null, episodes.snapshot().media)
    }

    @Test
    fun `advertised car ownership survives explicit phone screen acquisition`() {
        val state = AirPlayResourceState()
        assertTrue(state.initialize(mapOf("resources" to listOf(take(1), take(2)))))
        assertResource(state, 1, 2, 2)
        assertResource(state, 2, 2, 2)
        assertTrue(state.acquireScreen())
        assertResource(state, 1, 1, 1)
        assertResource(state, 2, 2, 2)
    }

    @Test
    fun `initial permanent snapshot and forbidden take constrain requests`() {
        val state = AirPlayResourceState()
        assertTrue(state.initialize(mapOf("initialPermanentEntity" to listOf(
            mapOf("resourceID" to 1, "permanentEntity" to 2, "takeConstraint" to 1000),
        ))))
        assertFalse(state.acquireScreen())
        assertResource(state, 1, 2, 2)
        assertTrue(state.initialize(mapOf("resources" to listOf(
            mapOf("resourceID" to 1, "permanentEntity" to 2, "entity" to 2, "takeConstraint" to 100),
        ))))
        assertTrue(state.acquireScreen())
    }

    @Test
    fun `media intent can acquire user constrained audio while background cannot`() {
        val state = AirPlayResourceState()
        state.initialize(mapOf("resources" to listOf(take(1), take(2))))
        assertFalse(state.acquireAudio())
        assertResource(state, 2, 2, 2)
        assertTrue(state.acquireAudio(priority = 500))
        assertResource(state, 2, 1, 1)
        assertResource(state, 1, 2, 2)
    }

    @Test
    fun `navigation temporarily borrows audio and returns it to the car`() {
        val state = AirPlayResourceState()
        state.initialize(mapOf("resources" to listOf(take(2))))
        assertTrue(state.acquireAudio(priority = 500, temporary = true))
        assertResource(state, 2, 1, 2)
        assertTrue(state.releaseAudioBorrow { true })
        assertResource(state, 2, 2, 2)
        assertTrue(state.acquireAudio(priority = 500, temporary = true))
        assertTrue(state.acquireAudio(priority = 500))
        assertTrue(state.releaseAudioBorrow { true })
        assertResource(state, 2, 1, 1)
    }

    @Test
    fun `Never constraints reject media and temporary requests without publishing`() {
        val state = AirPlayResourceState()
        state.initialize(mapOf("resources" to listOf(take(2, 1000, 1000))))
        val publish: (Map<String, Any?>) -> Boolean = { throw AssertionError("Forbidden ownership was published") }
        assertFalse(state.acquireAudio(500, false, publish = publish))
        assertFalse(state.acquireAudio(500, true, publish = publish))
        assertResource(state, 2, 2, 2)
    }

    @Test
    fun `rejected publication leaves car ownership unchanged`() {
        val state = AirPlayResourceState()
        state.initialize(mapOf("resources" to listOf(take(2))))
        assertFalse(state.acquireAudio(500, false) { false })
        assertFalse(state.hasAudio())
        assertResource(state, 2, 2, 2)
        assertTrue(state.acquireAudio(500, false) { true })
        assertTrue(state.hasAudio())
    }

    @Test
    fun `car change during publication invalidates pending acquisition`() {
        val state = AirPlayResourceState()
        state.initialize(mapOf("resources" to listOf(take(2))))
        assertFalse(state.acquireAudio(500, false) {
            assertFalse(state.hasAudio())
            assertEquals(0L, state.change(mapOf("resources" to listOf(take(2, 1000))))["status"])
            true
        })
        assertResource(state, 2, 2, 2)
        assertFalse(state.acquireAudio(500))
    }

    @Test
    fun `app state invalidation requests a retry without committing stale audio`() {
        val state = AirPlayResourceState()
        state.initialize(mapOf("resources" to listOf(take(2))))
        var retry = false
        assertFalse(state.acquireAudio(500, onInvalidated = { retry = true }) {
            state.setOwnSpeech(AirPlaySpeechMode.RECOGNIZING)
            true
        })
        assertTrue(retry)
        assertFalse(state.hasAudio())
        assertTrue(state.acquireAudio(500) { announcement ->
            val speech = (announcement["appStates"] as List<*>).map { it as Map<*, *> }
                .first { it["appStateID"] == 1L }
            assertEquals(2L, speech["speechMode"])
            true
        })
        state.change(mapOf("resources" to listOf(take(2))))
        retry = false
        assertFalse(state.acquireAudio(500, onInvalidated = { retry = true }) {
            // Re-selecting the same radio ownership must still defeat the pending request.
            state.change(mapOf("resources" to listOf(take(2))))
            true
        })
        assertFalse(retry)
        assertFalse(state.hasAudio())
        assertFalse(state.acquireAudio(500, onInvalidated = { retry = true }) { false })
        assertFalse(retry)
    }

    @Test
    fun `app state invalidation requests navigation return and screen claim retries`() {
        val state = AirPlayResourceState()
        state.initialize(mapOf("resources" to listOf(take(1), take(2))))
        assertTrue(state.acquireAudio(500, temporary = true))
        var retryReturn = false
        assertFalse(state.releaseAudioBorrow(onInvalidated = { retryReturn = true }) {
            state.setOwnSpeech(AirPlaySpeechMode.SPEAKING)
            true
        })
        assertTrue(retryReturn)
        assertResource(state, 2, 1, 2)
        assertTrue(state.releaseAudioBorrow { true })
        assertResource(state, 2, 2, 2)
        var retryScreen = false
        assertFalse(state.acquireScreen(onInvalidated = { retryScreen = true }) {
            state.setOwnPhoneCall(true)
            true
        })
        assertTrue(retryScreen)
        assertFalse(state.hasScreen())
        assertTrue(state.acquireScreen())
        assertTrue(state.hasScreen())
    }

    @Test
    fun `screen ownership commits only after accepted current publication`() {
        val state = AirPlayResourceState()
        state.initialize(mapOf("resources" to listOf(take(1))))
        assertFalse(state.acquireScreen { false })
        assertResource(state, 1, 2, 2)
        assertFalse(state.acquireScreen {
            assertFalse(state.hasScreen())
            assertEquals(0L, state.change(mapOf("resources" to listOf(take(1, 1000))))["status"])
            true
        })
        assertResource(state, 1, 2, 2)
        assertFalse(state.acquireScreen())
    }

    @Test
    fun `named screen borrows are idempotent and preserve independent audio`() {
        val state = AirPlayResourceState()
        val borrow = screenBorrow + ("borrowID" to "hmi")
        repeat(2) { assertEquals(0L, state.change(mapOf("resources" to listOf(borrow)))["status"]) }
        assertResource(state, 1, 2, 1)
        assertResource(state, 2, 1, 1)
        assertFalse(state.acquireScreen())
        state.change(mapOf("resources" to listOf(mapOf("resourceID" to 1L, "transferType" to 4L, "borrowID" to "hmi"))))
        assertResource(state, 1, 1, 1)
    }

    @Test
    fun `nested anonymous borrow needs matching number of returns`() {
        val state = AirPlayResourceState()
        repeat(2) { state.change(mapOf("resources" to listOf(screenBorrow))) }
        assertFalse(state.acquireScreen())
        val release = mapOf("resourceID" to 1L, "transferType" to 4L)
        state.change(mapOf("resources" to listOf(release)))
        assertResource(state, 1, 2, 1)
        state.change(mapOf("resources" to listOf(release)))
        assertResource(state, 1, 1, 1)
    }

    @Test
    fun `returning inner anonymous borrow restores outer constraint`() {
        val state = AirPlayResourceState()
        val outer = screenBorrow + ("unborrowConstraint" to 500L)
        state.change(mapOf("resources" to listOf(outer)))
        state.change(mapOf("resources" to listOf(screenBorrow)))
        assertFalse(state.acquireScreen())
        state.change(mapOf("resources" to listOf(mapOf("resourceID" to 1L, "transferType" to 4L))))
        assertTrue(state.acquireScreen())
        assertResource(state, 1, 1, 1)
    }

    @Test
    fun `new connection resets borrowed screen and prior app states`() {
        val state = AirPlayResourceState()
        state.change(mapOf("resources" to listOf(screenBorrow), "appStates" to listOf(
            mapOf("appStateID" to 1L, "speechMode" to 2L), mapOf("appStateID" to 3L, "state" to true),
        )))
        assertFalse(state.hasScreen())
        state.initialize(null)
        assertTrue(state.hasScreen())
        val apps = state.snapshot()["appStates"] as List<*>
        assertEquals(0L, (apps.first { (it as Map<*, *>)["appStateID"] == 3L } as Map<*, *>)["entity"])
        assertEquals(-1L, (apps.first { (it as Map<*, *>)["appStateID"] == 1L } as Map<*, *>)["speechMode"])
    }

    @Test
    fun `invalid resource transaction does not partially commit`() {
        val state = AirPlayResourceState()
        assertEquals(1L, state.change(mapOf("resources" to listOf(take(1), take(99))))["status"])
        assertResource(state, 1, 1, 1)
        assertEquals(1L, state.change(mapOf("resources" to listOf(mapOf("resourceID" to 1L, "transferType" to 1L))))["status"])
        assertResource(state, 1, 1, 1)
        assertEquals(1L, state.change(mapOf("resources" to listOf(mapOf("resourceID" to 1L, "transferType" to 9L))))["status"])
        assertResource(state, 1, 1, 1)
    }

    // ---- 本机应用态（AIS Table 23-56 / 23-72）----

    @Test
    fun `inactive local app states report no active entity`() {
        val state = AirPlayResourceState()
        state.initialize(null)
        val states = state.snapshot()["appStates"] as List<*>
        // PhoneCall has no speechMode while inactive; Speech explicitly reports None.
        assertEquals(listOf(0L, 0L, 0L), states.map { (it as Map<*, *>)["entity"] })
        assertEquals(null, (states[0] as Map<*, *>)["speechMode"])
        assertEquals(null, (states[1] as Map<*, *>)["speechMode"])
        assertEquals(-1L, (states[2] as Map<*, *>)["speechMode"])
    }

    @Test
    fun `收音时报 Speech=Controller 且 speechMode 为 RecognizingSpeech`() {
        val state = AirPlayResourceState()
        state.initialize(null)
        assertTrue(state.setOwnSpeech(AirPlaySpeechMode.RECOGNIZING))
        val speech = appState(state, 1)
        // entity=1 是 Controller（= 手机），2 是 Accessory（= 车机）；这里必须是手机
        assertEquals(1L, speech["entity"])
        assertEquals(2L, speech["speechMode"])
        // 同值重复设置不该再触发一次上报
        assertFalse(state.setOwnSpeech(AirPlaySpeechMode.RECOGNIZING))
    }

    @Test
    fun `说话时报 Speaking，结束后回到 None`() {
        val state = AirPlayResourceState()
        state.initialize(null)
        state.setOwnSpeech(AirPlaySpeechMode.SPEAKING)
        assertEquals(1L, appState(state, 1)["speechMode"])
        state.setOwnSpeech(AirPlaySpeechMode.NONE)
        assertEquals(0L, appState(state, 1)["entity"])
        assertEquals(-1L, appState(state, 1)["speechMode"])
    }

    @Test
    fun `通话中报 PhoneCall=Controller，并补上规范要求的 speechMode`() {
        val state = AirPlayResourceState()
        state.initialize(null)
        assertTrue(state.setOwnPhoneCall(true))
        val call = appState(state, 2)
        assertEquals(1L, call["entity"])
        assertEquals("Table 23-72 要求 PhoneCall 带 speechMode", -1L, call["speechMode"])
        state.setOwnPhoneCall(false)
        assertEquals(0L, appState(state, 2)["entity"])
    }

    @Test
    fun `本机语音态优先于车机自己报的那条`() {
        val state = AirPlayResourceState()
        state.initialize(
            mapOf<Any?, Any?>(
                "appStates" to listOf(mapOf<Any?, Any?>("appStateID" to 1, "speechMode" to 1)),
            ),
        )
        assertEquals("车机在说话时先照抄", 2L, appState(state, 1)["entity"])
        state.setOwnSpeech(AirPlaySpeechMode.RECOGNIZING)
        assertEquals("我们自己收音时以本机为准", 1L, appState(state, 1)["entity"])
        assertEquals(2L, appState(state, 1)["speechMode"])
        state.setOwnSpeech(AirPlaySpeechMode.NONE)
        assertEquals("本机结束后恢复车机语音态", 2L, appState(state, 1)["entity"])
        assertEquals(1L, appState(state, 1)["speechMode"])
    }

    @Test
    fun `新一轮初始化会清掉本机上一轮的语音态`() {
        val state = AirPlayResourceState()
        state.initialize(null)
        state.setOwnSpeech(AirPlaySpeechMode.RECOGNIZING)
        state.setOwnPhoneCall(true)
        state.initialize(null)
        assertEquals(AirPlaySpeechMode.NONE, state.ownSpeechMode)
        assertFalse(state.ownPhoneCallActive)
        assertEquals(0L, appState(state, 1)["entity"])
    }

    private fun appState(
        state: AirPlayResourceState,
        id: Int,
    ): Map<*, *> =
        (state.snapshot()["appStates"] as List<*>)
            .map { it as Map<*, *> }
            .first { it["appStateID"] == id.toLong() }

    @Test
    fun `car audio release and application state changes preserve screen`() {
        val state = AirPlayResourceState()
        state.change(mapOf("resources" to listOf(take(2))))
        state.change(mapOf("resources" to listOf(mapOf("resourceID" to 2L, "transferType" to 2L)),
            "appStates" to listOf(mapOf("appStateID" to 1L, "speechMode" to 2L), mapOf("appStateID" to 3L, "state" to true))))
        assertResource(state, 2, 0, 0)
        assertResource(state, 1, 1, 1)
        val apps = state.snapshot()["appStates"] as List<*>
        assertEquals(2L, (apps.first { (it as Map<*, *>)["appStateID"] == 1L } as Map<*, *>)["speechMode"])
        assertEquals(2L, (apps.first { (it as Map<*, *>)["appStateID"] == 3L } as Map<*, *>)["entity"])
    }

    private val screenBorrow = mapOf("resourceID" to 1L, "transferType" to 3L,
        "transferPriority" to 500L, "unborrowConstraint" to 1000L)

    private fun take(id: Long, constraint: Long = 500, borrow: Long = 100) = mapOf(
        "resourceID" to id, "transferType" to 1L, "transferPriority" to 500L,
        "takeConstraint" to constraint, "borrowConstraint" to borrow,
    )

    private fun assertResource(state: AirPlayResourceState, id: Long, entity: Long, owner: Long) {
        val resources = state.snapshot()["resources"] as List<*>
        val resource = resources.first { (it as Map<*, *>)["resourceID"] == id } as Map<*, *>
        assertEquals(entity, resource["entity"])
        assertEquals(owner, resource["permanentEntity"])
    }
}
