package com.kuma.motointercom

internal data class PresenceConnectRequest(
    val runtimeSessionId: RuntimeSessionId,
    val targetDeviceId: String,
    val targetSessionId: RuntimeSessionId,
    val requestId: String
)
