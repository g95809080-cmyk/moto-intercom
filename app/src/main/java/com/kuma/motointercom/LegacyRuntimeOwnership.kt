package com.kuma.motointercom

/** Covers a destroyed Service until both asynchronous Wi-Fi AND native audio disposal finish. */
internal object LegacyRuntimeOwnership {
    private class Owner(var releaseRequested: Boolean = false, var audioResources: Int = 0)
    private var owner: Owner? = null
    @Synchronized fun acquire(): Any? = if (owner == null) Owner().also { owner = it } else null
    @Synchronized fun release(token: Any) {
        if (owner === token) {
            owner!!.releaseRequested = true
            if (owner!!.audioResources == 0) owner = null
        }
    }
    /** Also covers a factory throwing before a controller is returned to its caller. */
    @Synchronized fun retainAudioResources(): java.io.Closeable {
        val token = checkNotNull(owner) { "Audio resources require a runtime owner" }
        check(!token.releaseRequested) { "Runtime is already closing" }
        token.audioResources++
        var released = false
        return java.io.Closeable {
            synchronized(this) {
                if (!released) {
                    released = true
                    token.audioResources--
                    if (owner === token && token.releaseRequested && token.audioResources == 0) owner = null
                }
            }
        }
    }
    @Synchronized fun hasOwner() = owner != null

    fun <T> withOwner(block: () -> T): T {
        val token = acquire() ?: error("Production runtime already owns audio")
        try { return block() } finally { release(token) }
    }
}
