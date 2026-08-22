import Foundation
import CoreFoundation

public enum MotoComPlatform: String, Codable, CaseIterable, Equatable, Hashable, Sendable {
    case android = "ANDROID"
    case ios = "IOS"
}

public enum NetworkCapability: String, Codable, CaseIterable, Equatable, Hashable, Sendable {
    case bleBootstrap = "BLE_BOOTSTRAP"
    case lan = "LAN"
    case androidLocalOnlyHotspotHost = "ANDROID_LOCAL_ONLY_HOTSPOT_HOST"
    case wifiJoin = "WIFI_JOIN"
    case androidWiFiDirect = "ANDROID_WIFI_DIRECT"
    case iosPeerToPeer = "IOS_PEER_TO_PEER"
    case iosPersonalHotspotManual = "IOS_PERSONAL_HOTSPOT_MANUAL"
}

public enum NetworkRole: String, Codable, Equatable, Hashable, Sendable {
    case host = "HOST"
    case joiner = "JOINER"
    case either = "EITHER"
}

public struct StableIdentity: Codable, Equatable, Sendable {
    public let deviceID: String
    public let sessionID: String
    public let nickname: String
    public let deviceName: String

    public init(
        deviceID: String,
        sessionID: String,
        nickname: String,
        deviceName: String
    ) throws {
        try UUIDValidator.requireCanonical(deviceID, field: "deviceId")
        try UUIDValidator.requireCanonical(sessionID, field: "sessionId")
        guard !nickname.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw MotoComError.invalidField("nickname")
        }
        guard !deviceName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw MotoComError.invalidField("deviceName")
        }
        self.deviceID = deviceID
        self.sessionID = sessionID
        self.nickname = nickname
        self.deviceName = deviceName
    }
}

public struct RuntimeCapabilities: Codable, Equatable, Sendable {
    public let platform: MotoComPlatform
    public let platformVersion: String
    public let apiLevel: Int?
    public let deviceName: String
    public let capabilities: Set<NetworkCapability>
    public let networkRole: NetworkRole
    public let tcpPort: Int

    public init(
        platform: MotoComPlatform,
        platformVersion: String,
        apiLevel: Int? = nil,
        deviceName: String,
        capabilities: Set<NetworkCapability>,
        networkRole: NetworkRole = .either,
        tcpPort: Int = 8890
    ) throws {
        guard (1...65535).contains(tcpPort) else {
            throw MotoComError.invalidField("tcpPort")
        }
        guard !deviceName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw MotoComError.invalidField("deviceName")
        }
        guard capabilities.count <= 32 else {
            throw MotoComError.invalidFrame("too many capabilities")
        }
        self.platform = platform
        self.platformVersion = platformVersion
        self.apiLevel = apiLevel
        self.deviceName = deviceName
        self.capabilities = capabilities
        self.networkRole = networkRole
        self.tcpPort = tcpPort
    }
}

public struct NearbyPeer: Identifiable, Equatable, Sendable {
    public var id: String { deviceID }
    public let deviceID: String
    public let sessionID: String
    public let nickname: String
    public let deviceName: String
    public let capabilities: RuntimeCapabilities

    public init(
        deviceID: String,
        sessionID: String,
        nickname: String,
        deviceName: String,
        capabilities: RuntimeCapabilities
    ) throws {
        try UUIDValidator.requireCanonical(deviceID, field: "deviceId")
        try UUIDValidator.requireCanonical(sessionID, field: "sessionId")
        self.deviceID = deviceID
        self.sessionID = sessionID
        self.nickname = nickname
        self.deviceName = deviceName
        self.capabilities = capabilities
    }
}

public enum NetworkPath: String, Codable, Equatable, Hashable, Sendable {
    case commonLAN = "COMMON_LAN"
    case androidLocalOnlyHotspot = "ANDROID_LOCAL_ONLY_HOTSPOT"
    case iosPersonalHotspotManual = "IOS_PERSONAL_HOTSPOT_MANUAL"
    case applePeerToPeer = "APPLE_PEER_TO_PEER"
    case androidWiFiDirect = "ANDROID_WIFI_DIRECT"
    case manualNetworkSettings = "MANUAL_NETWORK_SETTINGS"
}

public struct BootstrapDecision: Equatable, Sendable {
    public let path: NetworkPath
    public let localRole: NetworkRole
    public let requiresUserAction: Bool
    public let reason: String

    public init(
        path: NetworkPath,
        localRole: NetworkRole,
        requiresUserAction: Bool,
        reason: String
    ) {
        self.path = path
        self.localRole = localRole
        self.requiresUserAction = requiresUserAction
        self.reason = reason
    }
}

public enum SessionPhase: String, Equatable, Sendable {
    case idle = "Idle"
    case discovering = "Discovering"
    case bootstrapNegotiating = "BootstrapNegotiating"
    case networkPreparing = "NetworkPreparing"
    case networkReady = "NetworkReady"
    case awaitingConfirmation = "AwaitingConfirmation"
    case signaling = "Signaling"
    case mediaNegotiating = "MediaNegotiating"
    case audioReady = "AudioReady"
    case connected = "Connected"
    case permissionBlocked = "PermissionBlocked"
    case manualActionRequired = "ManualActionRequired"
    case recovering = "Recovering"
    case failed = "Failed"
    case offline = "Offline"
}

public struct PairingRecord: Codable, Equatable, Sendable, Identifiable {
    public var id: String { remoteDeviceID }
    public let remoteDeviceID: String
    public let remoteNickname: String
    public let deviceName: String
    public let localAlias: String
    public let shortCode: String
    public let pairedAt: Date
    public var lastConnectedAt: Date
    public var isPreferred: Bool
    public var lastTransport: String?
    public var failureCount: Int

    public init(
        remoteDeviceID: String,
        remoteNickname: String,
        deviceName: String,
        localAlias: String = "",
        shortCode: String? = nil,
        pairedAt: Date = Date(),
        lastConnectedAt: Date = Date(),
        isPreferred: Bool = false,
        lastTransport: String? = nil,
        failureCount: Int = 0
    ) throws {
        try UUIDValidator.requireCanonical(remoteDeviceID, field: "remoteDeviceId")
        guard !remoteNickname.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw MotoComError.invalidField("remoteNickname")
        }
        self.remoteDeviceID = remoteDeviceID
        self.remoteNickname = remoteNickname
        self.deviceName = deviceName
        self.localAlias = localAlias
        self.shortCode = shortCode ?? ShortCode.derive(from: remoteDeviceID)
        self.pairedAt = pairedAt
        self.lastConnectedAt = lastConnectedAt
        self.isPreferred = isPreferred
        self.lastTransport = lastTransport
        self.failureCount = max(0, failureCount)
    }
}

public struct AudioReadiness: Equatable, Sendable {
    public var remoteTrackPresent = false
    public var remoteFirstFrameReceived = false
    public var localRouteReady = false
    public var phoneInterrupting = false

    public var isReady: Bool {
        remoteTrackPresent && remoteFirstFrameReceived && localRouteReady && !phoneInterrupting
    }
}

public enum MotoComError: Error, Equatable, CustomStringConvertible, Sendable {
    case invalidField(String)
    case invalidFrame(String)
    case unsupportedVersion(Int)
    case unknownMessageType(String)
    case unknownEnum(field: String, value: String)
    case missingField(String)
    case unexpectedFields(Set<String>)
    case permissionDenied(String)
    case manualActionRequired(String)
    case notAudioReady
    case storageFailure(String)
    case unavailable(String)

    public var description: String {
        switch self {
        case .invalidField(let field): return "invalid field: \(field)"
        case .invalidFrame(let reason): return "invalid frame: \(reason)"
        case .unsupportedVersion(let version): return "unsupported version: \(version)"
        case .unknownMessageType(let value): return "unknown message type: \(value)"
        case .unknownEnum(let field, let value): return "unknown \(field): \(value)"
        case .missingField(let field): return "missing field: \(field)"
        case .unexpectedFields(let fields): return "unexpected fields: \(fields.sorted())"
        case .permissionDenied(let permission): return "permission denied: \(permission)"
        case .manualActionRequired(let action): return "manual action required: \(action)"
        case .notAudioReady: return "audio is not ready"
        case .storageFailure(let reason): return "storage failure: \(reason)"
        case .unavailable(let feature): return "unavailable: \(feature)"
        }
    }
}

public enum UUIDValidator {
    public static func requireCanonical(_ raw: String, field: String) throws {
        guard let uuid = UUID(uuidString: raw), uuid.uuidString.lowercased() == raw else {
            throw MotoComError.invalidField("\(field) must be a canonical lowercase RFC 4122 UUID")
        }
    }
}

public enum ShortCode {
    public static func derive(from deviceID: String) -> String {
        let bytes = Array(deviceID.utf8)
        var hash: UInt32 = 2_166_136_261
        for byte in bytes {
            hash ^= UInt32(byte)
            hash = hash &* 16_777_619
        }
        return String(format: "%04X", hash & 0xFFFF)
    }
}

enum StrictJSONNumber {
    static func isBoolean(_ value: Any) -> Bool {
        guard let number = value as? NSNumber else { return false }
        return CFGetTypeID(number as CFTypeRef) == CFBooleanGetTypeID()
    }

    static func integer(_ value: Any?) -> Int? {
        guard let value,
              !isBoolean(value),
              let number = value as? NSNumber,
              isInteger(number) else { return nil }
        return number.intValue
    }

    static func int64(_ value: Any?) -> Int64? {
        guard let value,
              !isBoolean(value),
              let number = value as? NSNumber,
              isInteger(number) else { return nil }
        return number.int64Value
    }

    private static func isInteger(_ number: NSNumber) -> Bool {
        let type = String(cString: number.objCType)
        return ["c", "s", "i", "l", "q", "C", "S", "I", "L", "Q"].contains(type)
    }
}
