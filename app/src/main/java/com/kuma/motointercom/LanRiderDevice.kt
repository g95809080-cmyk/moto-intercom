package com.kuma.motointercom

internal data class LanRiderDevice(
    val deviceId: String?,
    val sessionId: RuntimeSessionId?,
    val name: String,
    val deviceName: String,
    val protocolVersion: Int,
    val ip: String,
    val port: Int
)
