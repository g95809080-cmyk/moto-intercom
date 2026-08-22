import Foundation

public enum IOSRuntimeCapabilityProvider {
    public static func make(deviceName: String = "iPhone") throws -> RuntimeCapabilities {
        var values: Set<NetworkCapability> = [.lan, .wifiJoin, .iosPersonalHotspotManual]
        #if canImport(CoreBluetooth)
        values.insert(.bleBootstrap)
        #endif
        #if canImport(Network)
        values.insert(.iosPeerToPeer)
        #endif
        return try RuntimeCapabilities(
            platform: .ios,
            platformVersion: ProcessInfo.processInfo.operatingSystemVersionString,
            deviceName: deviceName,
            capabilities: values,
            networkRole: .either,
            tcpPort: 8890
        )
    }
}
