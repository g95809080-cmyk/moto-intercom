package com.kuma.motointercom

internal data class LanRiderDevice(
    val discoveryEndpointId: String,
    val deviceId: String?,
    val sessionId: RuntimeSessionId?,
    val name: String,
    val deviceName: String,
    val protocolVersion: Int,
    val ip: String,
    val port: Int
) {
    fun matches(targetLock: TargetLock): Boolean =
        deviceId == targetLock.targetDeviceId &&
            sessionId == targetLock.expectedRemoteSessionId
}

internal fun ConnectionAttempt?.acceptsLanPreflightDevice(remoteDeviceId: String?): Boolean =
    this != null && Transport.LAN in channelPlan && targetDeviceId == remoteDeviceId

internal class LanDiscoveryDeviceRegistry {
    private data class Entry(
        val device: LanRiderDevice,
        val expiresAtElapsedRealtimeMs: Long?
    )

    private val devicesByServiceName = linkedMapOf<String, Entry>()

    @Synchronized
    fun remember(
        serviceName: String,
        device: LanRiderDevice,
        expiresAtElapsedRealtimeMs: Long? = null
    ): List<LanRiderDevice> {
        require(serviceName.isNotBlank()) { "LAN service name must not be blank" }
        require(device.discoveryEndpointId == serviceName) {
            "LAN discovery endpoint must match its service name"
        }
        devicesByServiceName[serviceName] = Entry(device, expiresAtElapsedRealtimeMs)
        return snapshot()
    }

    @Synchronized
    fun remove(serviceName: String): List<LanRiderDevice> {
        devicesByServiceName.remove(serviceName)
        return snapshot()
    }

    @Synchronized
    fun expire(nowElapsedRealtimeMs: Long): List<LanRiderDevice>? {
        val removed = devicesByServiceName.entries.removeAll { (_, entry) ->
            entry.expiresAtElapsedRealtimeMs?.let { it <= nowElapsedRealtimeMs } == true
        }
        return snapshot().takeIf { removed }
    }

    @Synchronized
    fun find(targetLock: TargetLock): LanRiderDevice? = devicesByServiceName.values
        .asSequence()
        .map(Entry::device)
        .filter { it.matches(targetLock) }
        .minByOrNull(LanRiderDevice::discoveryEndpointId)

    @Synchronized
    fun clear() {
        devicesByServiceName.clear()
    }

    private fun snapshot(): List<LanRiderDevice> =
        devicesByServiceName.values.map(Entry::device)
}
