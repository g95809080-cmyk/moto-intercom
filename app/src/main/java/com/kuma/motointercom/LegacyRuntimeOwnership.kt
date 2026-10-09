package com.kuma.motointercom

/** Also covers a destroyed Service whose asynchronous Wi-Fi cleanup has not completed. */
internal object LegacyRuntimeOwnership {
    private var owner: Any? = null
    @Synchronized fun acquire(): Any? = if (owner == null) Any().also { owner = it } else null
    @Synchronized fun release(token: Any) { if (owner === token) owner = null }
    @Synchronized fun hasOwner() = owner != null
}
