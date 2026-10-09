package com.kuma.motointercom

/** RTC dispatch is FIFO. Every revocation stops the old producer; only the latest grant applies. */
internal class AudioIoGate(
    private val lock: Any,
    initialEnabled: Boolean,
    private val dispatch: (() -> Unit) -> Unit,
    private val apply: (Boolean) -> Unit
) {
    private data class Snapshot(val revision: Long, val enabled: Boolean, val closed: Boolean = false)
    private val state = java.util.concurrent.atomic.AtomicReference(Snapshot(0, initialEnabled))
    fun request(value: Boolean) = synchronized(lock) {
        val old = state.get()
        if (old.closed) return
        val requested = old.revision + 1
        state.set(Snapshot(requested, value))
        dispatch {
            synchronized(lock) {
                if (!value) apply(false)
                else if (allows(requested)) apply(true)
            }
        }
    }
    fun applyCurrent(action: (Boolean) -> Unit) = synchronized(lock) { action(state.get().let { it.enabled && !it.closed }) }
    // Native stop can join PCM while holding the I/O lock. PCM reads must never wait on it.
    fun revision(): Long = state.get().revision
    fun allows(expected: Long): Boolean = state.get().let { !it.closed && it.enabled && it.revision == expected }
    fun invalidate() = synchronized(lock) { state.set(state.get().let { it.copy(revision = it.revision + 1) }) }
    fun close() = synchronized(lock) {
        if (state.get().closed) return
        request(false)
        state.set(state.get().copy(closed = true))
    }
}
