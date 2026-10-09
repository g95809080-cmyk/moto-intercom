package com.kuma.motointercom

/** A resume request does not revive callbacks from the stopped native I/O thread. */
internal class NativeAudioProducerOwner {
    private var enabled = true
    private var producer: Thread? = null

    @Synchronized fun authorize(value: Boolean) {
        enabled = value
        if (!value) producer = null
    }

    @Synchronized fun start(thread: Thread, isNativeCurrent: () -> Boolean, action: () -> Unit) {
        if (!enabled || !isNativeCurrent()) return
        producer = thread
        action()
    }

    @Synchronized fun current(thread: Thread, action: () -> Unit) {
        if (enabled && producer === thread) action()
    }

    @Synchronized fun stop(thread: Thread, action: () -> Unit) {
        if (!enabled || producer !== thread) return
        producer = null
        action()
    }

    @Synchronized fun authorized(action: () -> Unit) {
        if (enabled) action()
    }
}
