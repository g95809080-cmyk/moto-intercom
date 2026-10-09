package com.kuma.motointercom

/** Also covers a destroyed Service whose asynchronous Wi-Fi cleanup has not completed. */
internal object LegacyRuntimeOwnership {
    fun acquire(): Any? = com.kuma.motointercom.group.LegacyRuntimeOwnership.acquire()
    fun release(token: Any) = com.kuma.motointercom.group.LegacyRuntimeOwnership.release(token)
    fun hasOwner() = com.kuma.motointercom.group.LegacyRuntimeOwnership.hasOwner()
}
