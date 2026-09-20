package com.kuma.motointercom.group

/** Process boundary, including a destroyed Service whose asynchronous cleanup is still owned. */
internal object GroupRuntimeOwnership {
    private var owner: Any? = null
    @Synchronized fun acquire(): Any? = if (owner == null) Any().also { owner = it } else null
    @Synchronized fun release(token: Any) { if (owner === token) owner = null }
    @Synchronized fun hasOwner() = owner != null
}

internal object LegacyRuntimeOwnership {
    private var owner: Any? = null
    @Synchronized fun acquire(): Any? = if (owner == null) Any().also { owner = it } else null
    @Synchronized fun release(token: Any) { if (owner === token) owner = null }
    @Synchronized fun hasOwner() = owner != null
}
