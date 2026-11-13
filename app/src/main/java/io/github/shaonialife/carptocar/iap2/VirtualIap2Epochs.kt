package io.github.shaonialife.carptocar.iap2

import java.util.concurrent.atomic.AtomicReference

/** Lock-free validity carried with queued transport writes and their asynchronous replies. */
internal class VirtualIap2Epochs {
    private class Epoch {
        @Volatile private var active = true
        val isCurrent: () -> Boolean = { active }

        fun invalidate() {
            active = false
        }
    }

    private val current = AtomicReference<Epoch?>(Epoch())
    val open: Boolean get() = current.get() != null

    /** The same predicate instance is reused for every frame in this epoch. */
    fun predicate(): (() -> Boolean)? = current.get()?.isCurrent

    /** A concurrent close wins permanently; restarting can never reopen a closed owner. */
    fun restart(): Boolean {
        val previous = current.get() ?: return false
        previous.invalidate()
        return current.compareAndSet(previous, Epoch())
    }

    /** Must remain safe under the transport lock, including while tunnel I/O is blocked. */
    fun close() {
        current.getAndSet(null)?.invalidate()
    }
}
