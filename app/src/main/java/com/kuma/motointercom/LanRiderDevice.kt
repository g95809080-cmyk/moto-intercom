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
)

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
    fun clear() {
        devicesByServiceName.clear()
    }
}
