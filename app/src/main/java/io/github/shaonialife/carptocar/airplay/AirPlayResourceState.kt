package io.github.shaonialife.carptocar.airplay

/** AIS Table 23-57: inactive, speaking, and recognizing speech. */
enum class AirPlaySpeechMode(val wireValue: Long) {
    NONE(-1L),
    SPEAKING(1L),
    RECOGNIZING(2L),
}

/** Authoritative controller-side modes. Entity 1 is the phone, 2 is the car.
 * Borrowing changes current ownership, never permanent ownership. Car requests commit
 * atomically; local acquisitions commit only after an accepted, still-current publication.
 */
internal class AirPlayResourceState {
    private data class Resource(
        var entity: Long = 1,
        var owner: Long = 1,
        var take: Long = 100,
        var borrow: Long = 100,
        var anonymousBorrows: List<Long> = emptyList(),
        var borrowIds: Map<String, Long> = emptyMap(),
    ) {
        val unborrow: Long get() = maxOf(anonymousBorrows.maxOrNull() ?: 100, borrowIds.values.maxOrNull() ?: 100)

        fun clearBorrows() {
            anonymousBorrows = emptyList()
            borrowIds = emptyMap()
        }

        fun apply(request: Map<*, *>): Boolean {
            val priority = request.number("transferPriority")
            val takeConstraint = request.number("takeConstraint")
            val borrowConstraint = request.number("borrowConstraint")
            val unborrowConstraint = request.number("unborrowConstraint")
            if (priority != null && priority != 100L && priority != 500L) return false
            if (!validConstraint(takeConstraint) || !validConstraint(borrowConstraint) || !validConstraint(unborrowConstraint)) return false
            return when (request.number("transferType")) {
                1L -> {
                    if (priority == null || takeConstraint == null || borrowConstraint == null) return false
                    if (owner != 2L && !allows(priority, take)) return false
                    entity = 2
                    owner = 2
                    take = takeConstraint
                    borrow = borrowConstraint
                    clearBorrows()
                    true
                }
                2L -> {
                    if (priority != null || takeConstraint != null || borrowConstraint != null || unborrowConstraint != null) return false
                    if (owner == 2L) {
                        entity = 0
                        owner = 0
                        take = 100
                        borrow = 100
                        clearBorrows()
                    }
                    true
                }
                3L -> {
                    if (priority == null || unborrowConstraint == null || takeConstraint != null || borrowConstraint != null) return false
                    if (owner == 2L || (entity != 2L && !allows(priority, borrow))) return false
                    entity = 2
                    owner = 1
                    val id = request["borrowID"]?.toString()
                    if (id == null) anonymousBorrows = anonymousBorrows + unborrowConstraint
                    else borrowIds = borrowIds + (id to unborrowConstraint)
                    true
                }
                4L -> {
                    if (priority != null || takeConstraint != null || borrowConstraint != null || unborrowConstraint != null) return false
                    if (entity == 1L && owner == 2L) return false
                    val id = request["borrowID"]?.toString()
                    if (id != null) borrowIds = borrowIds - id
                    else if (anonymousBorrows.isNotEmpty()) anonymousBorrows = anonymousBorrows.dropLast(1)
                    if (entity == 2L && owner == 1L && anonymousBorrows.isEmpty() && borrowIds.isEmpty()) entity = owner
                    true
                }
                else -> false
            }
        }

        fun acquire(priority: Long, takeConstraint: Long): Boolean {
            if (priority != 100L && priority != 500L) return false
            if (entity == 2L && owner == 1L && !allows(priority, unborrow)) return false
            if (owner != 1L && !allows(priority, take)) return false
            entity = 1
            owner = 1
            take = takeConstraint
            borrow = 100
            clearBorrows()
            return true
        }

        fun borrowForPhone(priority: Long): Boolean {
            if (priority != 100L && priority != 500L) return false
            if (entity == 1L) return true
            if (owner != 2L) return acquire(priority, priority)
            if (!allows(priority, borrow)) return false
            entity = 1
            return true
        }
    }

    private var screen = Resource()
    private var audio = Resource()
    private var phoneCall = 0L
    private var navigation = 0L
    private var speechEntity = 0L
    private var speechMode = -1L
    private var ownSpeech = AirPlaySpeechMode.NONE
    private var ownPhoneCall = false
    private var revision = 0L
    private var carResourceRevision = 0L
    @Volatile var audioRevision = 0L
        private set

    /** Missing advertised entries retain controller-owned defaults. Present Take entries
     * grant the car ownership; projection must explicitly acquire the screen.
     */
    @Synchronized
    fun initialize(params: Map<*, *>?): Boolean {
        val nextScreen = Resource()
        val nextAudio = Resource()
        val permanent = params?.get("initialPermanentEntity") ?: emptyList<Any>()
        if (permanent !is List<*>) return false
        for (entry in permanent) {
            val resource = entry as? Map<*, *> ?: return false
            val next = when (resource.number("resourceID")) {
                1L -> nextScreen
                2L -> nextAudio
                else -> return false
            }
            val owner = resource.number("permanentEntity") ?: return false
            if (owner != 1L && owner != 2L) return false
            next.owner = owner
            next.entity = owner
            next.take = resource.number("takeConstraint") ?: 100
            next.borrow = resource.number("borrowConstraint") ?: 100
            if (!validConstraint(next.take) || !validConstraint(next.borrow)) return false
        }
        val resources = params?.get("resources") ?: emptyList<Any>()
        if (resources !is List<*>) return false
        for (entry in resources) {
            val request = entry as? Map<*, *> ?: return false
            val next = when (request.number("resourceID")) {
                1L -> nextScreen
                2L -> nextAudio
                else -> return false
            }
            when (request.number("transferType")) {
                1L -> {
                    next.entity = 2
                    next.owner = 2
                    next.take = request.number("takeConstraint") ?: 100
                    next.borrow = request.number("borrowConstraint") ?: 100
                    next.clearBorrows()
                }
                3L -> if (next.owner != 2L) {
                    next.entity = 2
                    next.owner = 1
                    val constraint = request.number("unborrowConstraint") ?: 100
                    if (!validConstraint(constraint)) return false
                    val id = request["borrowID"]?.toString()
                    if (id == null) next.anonymousBorrows = next.anonymousBorrows + constraint
                    else next.borrowIds = next.borrowIds + (id to constraint)
                }
                2L, 4L -> Unit
                null -> {
                    val owner = request.number("permanentEntity") ?: next.owner
                    val entity = request.number("entity") ?: owner
                    if (owner !in 0L..2L || entity !in 0L..2L) return false
                    next.owner = owner
                    next.entity = entity
                    next.take = request.number("takeConstraint") ?: 100
                    next.borrow = request.number("borrowConstraint") ?: 100
                }
                else -> return false
            }
            if (!validConstraint(next.take) || !validConstraint(next.borrow)) return false
        }
        val states = params?.get("appStates") ?: emptyList<Any>()
        if (states !is List<*>) return false
        var nextPhone = 0L
        var nextNavigation = 0L
        var nextSpeechEntity = 0L
        var nextSpeechMode = -1L
        for (entry in states) {
            val state = entry as? Map<*, *> ?: return false
            when (state.number("appStateID")) {
                1L -> {
                    nextSpeechMode = state.number("speechMode") ?: return false
                    if (nextSpeechMode != -1L && nextSpeechMode != 1L && nextSpeechMode != 2L) return false
                    nextSpeechEntity = if (nextSpeechMode == -1L) 0 else 2
                }
                2L -> nextPhone = if (state["state"] as? Boolean ?: return false) 2 else 0
                3L -> nextNavigation = if (state["state"] as? Boolean ?: return false) 2 else 0
                else -> return false
            }
        }
        screen = nextScreen
        audio = nextAudio
        audioRevision++
        revision++
        carResourceRevision++
        phoneCall = nextPhone
        navigation = nextNavigation
        speechEntity = nextSpeechEntity
        speechMode = nextSpeechMode
        ownSpeech = AirPlaySpeechMode.NONE
        ownPhoneCall = false
        return true
    }

    fun acquireScreen(
        onInvalidated: () -> Unit = {},
        publish: (Map<String, Any?>) -> Boolean = { true },
    ): Boolean = updateResource(false, publish, onInvalidated) { it.acquire(priority = 500, takeConstraint = 500) }

    fun acquireAudio(
        priority: Long = 100,
        temporary: Boolean = false,
        onInvalidated: () -> Unit = {},
        isCurrentIntent: () -> Boolean = { true },
        publish: (Map<String, Any?>) -> Boolean = { true },
    ): Boolean = updateResource(true, publish, onInvalidated, isCurrentIntent) { next ->
        if (temporary) next.borrowForPhone(priority) else next.acquire(priority, priority)
    }

    fun releaseAudioBorrow(
        onInvalidated: () -> Unit = {},
        publish: (Map<String, Any?>) -> Boolean,
    ): Boolean = updateResource(true, publish, onInvalidated) { next ->
            if (next.entity == 1L && next.owner == 2L) next.entity = 2
            true
        }

    /** Never hold the model monitor across I/O: changeModes can arrive before the reply.
     * The complete announcement is revision-checked, including changes to other resources
     * and app states, so a stale acknowledgement cannot overwrite the receiver's decision.
     */
    private fun updateResource(
        isAudio: Boolean,
        publish: (Map<String, Any?>) -> Boolean,
        onInvalidated: () -> Unit = {},
        isCurrentIntent: () -> Boolean = { true },
        update: (Resource) -> Boolean,
    ): Boolean {
        val next: Resource
        val expectedRevision: Long
        val expectedCarResourceRevision: Long
        val announcement: Map<String, Any?>
        if (!isCurrentIntent()) return false
        synchronized(this) {
            val current = if (isAudio) audio else screen
            next = current.copy()
            if (!update(next)) return false
            // Screen acquisition also carries the initial announcement when already owned.
            if (next == current && isAudio) return true
            expectedRevision = revision
            expectedCarResourceRevision = carResourceRevision
            announcement = if (isAudio) snapshot(screen, next) else snapshot(next, audio)
        }
        if (!isCurrentIntent() || !publish(announcement)) return false
        if (!isCurrentIntent()) {
            // The receiver accepted the take after STOP/PAUSE. Re-publish actual current
            // ownership rather than activating a stream for an expired playback episode.
            check(publish(snapshot())) { "Cancelled audio acquisition ownership could not be restored" }
            return false
        }
        val invalidatedByAppState = synchronized(this) {
            if (revision != expectedRevision) {
                carResourceRevision == expectedCarResourceRevision
            } else {
                if (isAudio) {
                    audio = next
                    audioRevision++
                } else screen = next
                revision++
                return true
            }
        }
        if (invalidatedByAppState && isCurrentIntent()) onInvalidated()
        return false
    }

    @Synchronized
    fun setOwnSpeech(mode: AirPlaySpeechMode): Boolean {
        if (ownSpeech == mode) return false
        ownSpeech = mode
        revision++
        return true
    }

    @Synchronized
    fun setOwnPhoneCall(active: Boolean): Boolean {
        if (ownPhoneCall == active) return false
        ownPhoneCall = active
        revision++
        return true
    }

    val ownSpeechMode: AirPlaySpeechMode get() = synchronized(this) { ownSpeech }
    val ownPhoneCallActive: Boolean get() = synchronized(this) { ownPhoneCall }

    @Synchronized
    fun describeAudio(): String =
        "owner=${audio.owner} current=${audio.entity} take=${audio.take} borrow=${audio.borrow} " +
            "unborrow=${audio.unborrow} borrowers=${audio.anonymousBorrows.size + audio.borrowIds.size} revision=$audioRevision"

    @Synchronized
    fun hasScreen(): Boolean = screen.entity == 1L

    @Synchronized
    fun hasAudio(): Boolean = audio.entity == 1L

    @Synchronized
    fun hasPhoneAudioBorrow(): Boolean = audio.entity == 1L && audio.owner == 2L

    @Synchronized
    fun change(params: Map<*, *>): Map<String, Any?> {
        val nextScreen = screen.copy()
        val nextAudio = audio.copy()
        var nextPhone = phoneCall
        var nextNavigation = navigation
        var nextSpeechEntity = speechEntity
        var nextSpeechMode = speechMode
        val resources = params["resources"] ?: emptyList<Any>()
        val states = params["appStates"] ?: emptyList<Any>()
        if (resources !is List<*> || states !is List<*>) return rejected()
        for (entry in resources) {
            val request = entry as? Map<*, *> ?: return rejected()
            val resource = when (request.number("resourceID")) {
                1L -> nextScreen
                2L -> nextAudio
                else -> return rejected()
            }
            if (!resource.apply(request)) return rejected()
        }
        for (entry in states) {
            val state = entry as? Map<*, *> ?: return rejected()
            when (state.number("appStateID")) {
                1L -> {
                    val mode = state.number("speechMode") ?: return rejected()
                    if (mode != -1L && mode != 1L && mode != 2L) return rejected()
                    nextSpeechMode = mode
                    nextSpeechEntity = if (mode == -1L) 0 else 2
                }
                2L -> {
                    val active = state["state"] as? Boolean ?: return rejected()
                    if (active) nextPhone = 2 else if (nextPhone == 2L) nextPhone = 0
                }
                3L -> {
                    val active = state["state"] as? Boolean ?: return rejected()
                    if (active) nextNavigation = 2 else if (nextNavigation == 2L) nextNavigation = 0
                }
                else -> return rejected()
            }
        }
        screen = nextScreen
        if (audio != nextAudio) audioRevision++
        audio = nextAudio
        phoneCall = nextPhone
        navigation = nextNavigation
        speechEntity = nextSpeechEntity
        speechMode = nextSpeechMode
        revision++
        if (resources.isNotEmpty()) carResourceRevision++
        return linkedMapOf("status" to 0L, "params" to snapshot())
    }

    @Synchronized
    fun snapshot(): Map<String, Any?> = snapshot(screen, audio)

    private fun snapshot(screen: Resource, audio: Resource): Map<String, Any?> = linkedMapOf(
        "appStates" to listOf(
            linkedMapOf<String, Any?>("appStateID" to 2L, "entity" to if (ownPhoneCall) 1L else phoneCall).apply {
                if (ownPhoneCall) put("speechMode", -1L)
            },
            linkedMapOf("appStateID" to 3L, "entity" to navigation),
            linkedMapOf(
                "appStateID" to 1L,
                "entity" to if (ownSpeech != AirPlaySpeechMode.NONE) 1L else speechEntity,
                "speechMode" to if (ownSpeech != AirPlaySpeechMode.NONE) ownSpeech.wireValue else speechMode,
            ),
        ),
        "resources" to listOf(
            linkedMapOf("resourceID" to 1L, "entity" to screen.entity, "permanentEntity" to screen.owner),
            linkedMapOf("resourceID" to 2L, "entity" to audio.entity, "permanentEntity" to audio.owner),
        ),
    )

    private companion object {
        fun validConstraint(value: Long?) = value == null || value == 100L || value == 500L || value == 1000L
        fun Map<*, *>.number(key: String): Long? = (this[key] as? Number)?.toLong()
        fun allows(priority: Long, constraint: Long) = constraint == 100L || constraint == 500L && priority == 500L
        fun rejected(): Map<String, Any?> = mapOf("status" to 1L)
    }
}
