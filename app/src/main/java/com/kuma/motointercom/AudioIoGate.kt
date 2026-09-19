package com.kuma.motointercom

/** Shares the engine session registry lock. Native apply must not invoke product callbacks.
 * A queued grant can never override a newer interruption. Cleanup updates intent when the
 * session is removed, not later when its native resources happen to be disposed.
 */
internal class AudioIoGate(
    private val lock: Any,
    initialEnabled: Boolean,
    private val dispatch: (() -> Unit) -> Unit,
    private val apply: (Boolean) -> Unit
) {
    private var enabled = initialEnabled
    private var revision = 0L
    private var closed = false

    fun request(value: Boolean) = synchronized(lock) {
        if (closed) return
        enabled = value
        val requested = ++revision
        dispatch {
            synchronized(lock) {
                if (revision == requested) apply(enabled)
            }
        }
    }

    fun applyCurrent(action: (Boolean) -> Unit) = synchronized(lock) { action(enabled && !closed) }
    fun revision(): Long = synchronized(lock) { revision }
    fun allows(expected: Long): Boolean = synchronized(lock) { !closed && enabled && revision == expected }

    fun close() = synchronized(lock) {
        if (closed) return
        request(false)
        closed = true
    }
}
