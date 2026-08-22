import Foundation

#if canImport(NetworkExtension)
import NetworkExtension
#endif

public struct BootstrapContext: Equatable, Sendable {
    public let commonLANAvailable: Bool
    public let userSelectedIOSHost: Bool
    public let userSelectedAndroidHost: Bool
    public let bluetoothPermissionGranted: Bool

    public init(
        commonLANAvailable: Bool = false,
        userSelectedIOSHost: Bool = false,
        userSelectedAndroidHost: Bool = false,
        bluetoothPermissionGranted: Bool = true
    ) {
        self.commonLANAvailable = commonLANAvailable
        self.userSelectedIOSHost = userSelectedIOSHost
        self.userSelectedAndroidHost = userSelectedAndroidHost
        self.bluetoothPermissionGranted = bluetoothPermissionGranted
    }
}

public enum BootstrapPolicy {
    public static func choose(
        local: RuntimeCapabilities,
        remote: RuntimeCapabilities,
        context: BootstrapContext
    ) throws -> BootstrapDecision {
        guard context.bluetoothPermissionGranted else {
            throw MotoComError.permissionDenied("Bluetooth is required for automatic bootstrap")
        }

        if context.commonLANAvailable,
           local.capabilities.contains(.lan),
           remote.capabilities.contains(.lan) {
            return BootstrapDecision(
                path: .commonLAN,
                localRole: .either,
                requiresUserAction: false,
                reason: "双方已在共同局域网，直接使用 Bonjour/TCP"
            )
        }

        if local.platform == .ios && remote.platform == .ios,
           local.capabilities.contains(.iosPeerToPeer),
           remote.capabilities.contains(.iosPeerToPeer) {
            return BootstrapDecision(
                path: .applePeerToPeer,
                localRole: .either,
                requiresUserAction: false,
                reason: "双方均为 iOS 且声明 Apple peer-to-peer"
            )
        }

        if local.platform == .ios && remote.platform == .android {
            if context.userSelectedIOSHost {
                return BootstrapDecision(
                    path: .iosPersonalHotspotManual,
                    localRole: .host,
                    requiresUserAction: true,
                    reason: "iPhone 作为主机需要用户打开系统个人热点"
                )
            }
            if remote.capabilities.contains(.androidLocalOnlyHotspotHost) {
                return BootstrapDecision(
                    path: .androidLocalOnlyHotspot,
                    localRole: .joiner,
                    requiresUserAction: false,
                    reason: "Android API 支持 App-owned Local-only Hotspot"
                )
            }
            return BootstrapDecision(
                path: .manualNetworkSettings,
                localRole: .either,
                requiresUserAction: true,
                reason: "Android 无法自动创建临时热点，进入系统网络设置"
            )
        }

        if local.platform == .android && remote.platform == .ios {
            if context.userSelectedAndroidHost,
               local.capabilities.contains(.androidLocalOnlyHotspotHost) {
                return BootstrapDecision(
                    path: .androidLocalOnlyHotspot,
                    localRole: .host,
                    requiresUserAction: false,
                    reason: "用户选择 Android 主机且 Local-only Hotspot 可用"
                )
            }
            if remote.capabilities.contains(.iosPersonalHotspotManual) {
                return BootstrapDecision(
                    path: .iosPersonalHotspotManual,
                    localRole: .joiner,
                    requiresUserAction: true,
                    reason: "iPhone 主机需要用户打开系统个人热点"
                )
            }
            if local.capabilities.contains(.androidLocalOnlyHotspotHost) {
                return BootstrapDecision(
                    path: .androidLocalOnlyHotspot,
                    localRole: .host,
                    requiresUserAction: false,
                    reason: "Android 自动作为主机"
                )
            }
            return BootstrapDecision(
                path: .manualNetworkSettings,
                localRole: .either,
                requiresUserAction: true,
                reason: "没有可用的自动热点路径"
            )
        }

        if local.platform == .android && remote.platform == .android,
           local.capabilities.contains(.androidWiFiDirect),
           remote.capabilities.contains(.androidWiFiDirect) {
            return BootstrapDecision(
                path: .androidWiFiDirect,
                localRole: .either,
                requiresUserAction: false,
                reason: "Android↔Android 保持现有 Wi-Fi Direct"
            )
        }

        return BootstrapDecision(
            path: .manualNetworkSettings,
            localRole: .either,
            requiresUserAction: true,
            reason: "没有双方共同的自动网络能力"
        )
    }
}

public actor NetworkBootstrapCoordinator {
    public private(set) var decision: BootstrapDecision?
    public private(set) var currentHotspot: HotspotCredentials?
    public private(set) var state: SessionPhase = .idle

    public init() {}

    public func selectPath(
        local: RuntimeCapabilities,
        remote: RuntimeCapabilities,
        context: BootstrapContext
    ) throws -> BootstrapDecision {
        state = .bootstrapNegotiating
        let selected = try BootstrapPolicy.choose(local: local, remote: remote, context: context)
        decision = selected
        state = selected.requiresUserAction ? .manualActionRequired : .networkPreparing
        return selected
    }

    public func acceptAndroidHotspot(_ hotspot: HotspotCredentials) async throws {
        if decision == nil {
            decision = BootstrapDecision(
                path: .androidLocalOnlyHotspot,
                localRole: .joiner,
                requiresUserAction: false,
                reason: "收到 Android Local-only Hotspot 凭据"
            )
        }
        guard decision?.path == .androidLocalOnlyHotspot else {
            throw MotoComError.invalidField("unexpected hotspot credentials")
        }
        if let expiresAt = hotspot.expiresAt, expiresAt <= Date() {
            throw MotoComError.invalidField("hotspot credentials expired")
        }
        currentHotspot = hotspot
        #if canImport(NetworkExtension)
        let configuration: NEHotspotConfiguration
        if hotspot.password.isEmpty || hotspot.security.uppercased() == "OPEN" {
            configuration = NEHotspotConfiguration(ssid: hotspot.ssid)
        } else {
            configuration = NEHotspotConfiguration(
                ssid: hotspot.ssid,
                passphrase: hotspot.password,
                isWEP: hotspot.security.uppercased() == "WEP"
            )
        }
        configuration.joinOnce = true
        do {
            try await withCheckedThrowingContinuation { continuation in
                NEHotspotConfigurationManager.shared.apply(configuration) { error in
                    if let error {
                        let nsError = error as NSError
                        if nsError.domain == NEHotspotConfigurationErrorDomain,
                           nsError.code == NEHotspotConfigurationError.alreadyAssociated.rawValue {
                            continuation.resume()
                        } else {
                            continuation.resume(throwing: error)
                        }
                    } else {
                        continuation.resume()
                    }
                }
            }
            currentHotspot = nil
            state = .networkReady
        } catch {
            currentHotspot = nil
            state = .failed
            throw MotoComError.unavailable("iOS could not join the Android temporary Wi-Fi: \(error.localizedDescription)")
        }
        #else
        state = .manualActionRequired
        throw MotoComError.manualActionRequired("请在系统 Wi-Fi 设置中加入 \(hotspot.ssid)")
        #endif
    }

    public func markManualActionCompleted() {
        state = .networkReady
    }

    public func markFailed() {
        currentHotspot = nil
        state = .failed
    }

    public func close() {
        currentHotspot = nil
        state = .offline
    }
}
