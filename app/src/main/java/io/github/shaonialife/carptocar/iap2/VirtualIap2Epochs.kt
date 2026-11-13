package io.github.shaonialife.carptocar.iap2

import java.util.concurrent.atomic.AtomicReference

/** 无锁链路周期令牌：随异步排队的控制通道发送与响应回调传递，旧周期一旦失效立即丢弃。 */
internal class VirtualIap2Epochs {
    private class Epoch {
        @Volatile
        private var active = true
        val isCurrent: () -> Boolean = { active }

        fun invalidate() {
            active = false
        }
    }

    private val current = AtomicReference<Epoch?>(Epoch())
    val open: Boolean get() = current.get() != null

    /** 当前周期的有效性谓词。 */
    fun predicate(): (() -> Boolean)? = current.get()?.isCurrent

    /** 关闭操作具有终局性：并发或已关闭实例不会被 `restart()` 重新打开。 */
    fun restart(): Boolean {
        val previous = current.get() ?: return false
        previous.invalidate()
        return current.compareAndSet(previous, Epoch())
    }

    /** 可在传输锁内安全调用，不等待阻塞中的隧道 I/O。 */
    fun close() {
        current.getAndSet(null)?.invalidate()
    }
}
