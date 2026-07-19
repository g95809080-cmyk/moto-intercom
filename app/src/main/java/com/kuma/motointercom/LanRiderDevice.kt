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
    private val devicesByServiceName = linkedMapOf<String, LanRiderDevice>()

    @Synchronized
    fun remember(serviceName: String, device: LanRiderDevice): List<LanRiderDevice> {
        require(serviceName.isNotBlank()) { "LAN service name must not be blank" }
        require(device.discoveryEndpointId == serviceName) {
            "LAN discovery endpoint must match its service name"
        }
        devicesByServiceName[serviceName] = device
        return devicesByServiceName.values.toList()
    }

    @Synchronized
    fun remove(serviceName: String): List<LanRiderDevice> {
        devicesByServiceName.remove(serviceName)
        return devicesByServiceName.values.toList()
    }

    @Synchronized
    fun find(targetLock: TargetLock): LanRiderDevice? = devicesByServiceName.values
        .asSequence()
        .filter { it.matches(targetLock) }
        .minByOrNull(LanRiderDevice::discoveryEndpointId)

    @Synchronized
    fun clear() {
        devicesByServiceName.clear()
    }
}
