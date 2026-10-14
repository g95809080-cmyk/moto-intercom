package com.kuma.motointercom

/** A resume request does not revive callbacks from the stopped native I/O thread. */
internal class NativeAudioProducerOwner {
    private var enabled = true
    private var producer: Thread? = null
    private val revoked = java.util.WeakHashMap<Thread, Boolean>()

    @Synchronized fun authorize(value: Boolean, nativeProducer: () -> Thread? = { null }) {
        enabled = value
        if (!value) {
            producer?.let { revoked[it] = true }
            nativeProducer()?.let { revoked[it] = true }
            producer = null
        }
    }

    @Synchronized fun start(thread: Thread, isNativeCurrent: () -> Boolean, action: () -> Unit) {
        if (!enabled || revoked.containsKey(thread) || !isNativeCurrent()) return
        producer = thread
        action()
    }

    @Synchronized fun current(thread: Thread, action: () -> Unit) {
        if (enabled && producer === thread) action()
    }
    @Synchronized fun isCurrent(thread: Thread) = enabled && producer === thread

    @Synchronized fun stop(thread: Thread, action: () -> Unit) {
        if (!enabled || producer !== thread) return
        producer = null
        action()
    }

    @Synchronized fun authorized(action: () -> Unit) {
        if (enabled) action()
    }
}
