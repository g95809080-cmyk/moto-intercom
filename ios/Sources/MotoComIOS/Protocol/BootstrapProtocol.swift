import Foundation

public enum BootstrapMessageType: String, Equatable, Sendable {
    case request = "BOOTSTRAP_REQUEST"
    case capabilities = "BOOTSTRAP_CAPABILITIES"
    case hotspotReady = "HOTSPOT_READY"
    case wifiJoinResult = "WIFI_JOIN_RESULT"
    case endpointReady = "ENDPOINT_READY"
    case cancel = "BOOTSTRAP_CANCEL"
}

public struct BootstrapAnnouncement: Codable, Equatable, Sendable {
    public let app: String
    public let protocolVersion: Int
    public let platform: MotoComPlatform
    public let deviceID: String
    public let sessionID: String
    public let capabilities: Set<NetworkCapability>
    public let networkRole: NetworkRole
    public let tcpPort: Int

    public init(
        platform: MotoComPlatform,
        deviceID: String,
        sessionID: String,
        capabilities: Set<NetworkCapability>,
        networkRole: NetworkRole,
        tcpPort: Int = 8890
    ) throws {
        try UUIDValidator.requireCanonical(deviceID, field: "deviceId")
        try UUIDValidator.requireCanonical(sessionID, field: "sessionId")
        guard (1...65535).contains(tcpPort) else { throw MotoComError.invalidField("tcpPort") }
        guard capabilities.count <= BootstrapMessage.maxCapabilities else {
            throw MotoComError.invalidFrame("too many capabilities")
        }
        self.app = "motocom"
        self.protocolVersion = 2
        self.platform = platform
        self.deviceID = deviceID
        self.sessionID = sessionID
        self.capabilities = capabilities
        self.networkRole = networkRole
        self.tcpPort = tcpPort
    }
}

public struct HotspotCredentials: Codable, Equatable, Sendable {
    public let ssid: String
    public let security: String
    public let password: String
    public let hostAddress: String?
    public let tcpPort: Int
    public let expiresAt: Date?
    public let expiresAtElapsedRealtimeMs: Int64?

    public var passphrase: String { password }

    public init(
        ssid: String,
        security: String,
        password: String,
        hostAddress: String? = nil,
        tcpPort: Int = 8890,
        expiresAt: Date? = nil,
        expiresAtElapsedRealtimeMs: Int64? = nil
    ) throws {
        guard !ssid.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              ssid.unicodeScalars.count <= 64 else {
            throw MotoComError.invalidField("ssid")
        }
        let normalizedSecurity = security.uppercased()
        guard ["OPEN", "WPA", "WPA2", "WPA3", "WEP"].contains(normalizedSecurity) else {
            throw MotoComError.invalidField("security")
        }
        if normalizedSecurity != "OPEN" {
            guard password.utf8.count >= 8, password.utf8.count <= 63 else {
                throw MotoComError.invalidField("passphrase")
            }
        }
        guard (1...65535).contains(tcpPort) else { throw MotoComError.invalidField("tcpPort") }
        if let expiresAtElapsedRealtimeMs, expiresAtElapsedRealtimeMs < 0 {
            throw MotoComError.invalidField("expiresAtMs")
        }
        self.ssid = ssid
        self.security = normalizedSecurity
        self.password = password
        self.hostAddress = hostAddress
        self.tcpPort = tcpPort
        self.expiresAt = expiresAt
        self.expiresAtElapsedRealtimeMs = expiresAtElapsedRealtimeMs
    }
}

public enum WifiJoinResult: String, Codable, Equatable, Sendable {
    case joined = "JOINED"
    case failed = "FAILED"
    case manualActionRequired = "MANUAL_ACTION_REQUIRED"
}

/// The BLE bootstrap message is intentionally shaped like Android's
/// BleBootstrapMessage. `fields` carries message-specific values while the
/// root keys stay exact across platforms.
public struct BootstrapMessage: Equatable, Sendable {
    public static let protocolVersion = 2
    public static let maxFieldValueCharacters = 1024
    public static let maxCapabilities = 32

    public let type: BootstrapMessageType
    public let requestID: String
    public let fields: [String: String]
    public let capabilities: Set<String>
    public let protocolVersion: Int

    public init(
        type: BootstrapMessageType,
        requestID: String = UUID().uuidString.lowercased(),
        fields: [String: String] = [:],
        capabilities: Set<String> = [],
        protocolVersion: Int = BootstrapMessage.protocolVersion
    ) {
        self.type = type
        self.requestID = requestID
        self.fields = fields
        self.capabilities = capabilities
        self.protocolVersion = protocolVersion
    }

    public init(
        type: BootstrapMessageType,
        announcement: BootstrapAnnouncement? = nil,
        hotspot: HotspotCredentials? = nil,
        joinResult: WifiJoinResult? = nil,
        reason: String? = nil,
        endpointAddress: String? = nil,
        endpointPort: Int? = nil
    ) {
        var fields = [String: String]()
        var capabilities = Set<String>()
        if let announcement {
            fields["app"] = announcement.app
            fields["platform"] = announcement.platform.rawValue
            fields["deviceId"] = announcement.deviceID
            fields["sessionId"] = announcement.sessionID
            fields["networkRole"] = announcement.networkRole.rawValue
            fields["tcpPort"] = String(announcement.tcpPort)
            fields["protocolVersion"] = String(announcement.protocolVersion)
            capabilities = Set(announcement.capabilities.map(\.rawValue))
        }
        if let hotspot {
            fields["ssid"] = hotspot.ssid
            fields["passphrase"] = hotspot.password
            fields["security"] = hotspot.security
            fields["hostAddress"] = hotspot.hostAddress ?? ""
            fields["tcpPort"] = String(hotspot.tcpPort)
            if let expiresAtElapsedRealtimeMs = hotspot.expiresAtElapsedRealtimeMs {
                fields["expiresAtMs"] = String(expiresAtElapsedRealtimeMs)
            }
        }
        if let joinResult { fields["result"] = joinResult.rawValue }
        if let reason { fields["reason"] = reason }
        if let endpointAddress { fields["hostAddress"] = endpointAddress }
        if let endpointPort { fields["tcpPort"] = String(endpointPort) }
        self.init(type: type, fields: fields, capabilities: capabilities)
    }

    public var announcement: BootstrapAnnouncement? {
        guard type == .capabilities,
              fields["app"] == "motocom",
              fields["protocolVersion"] == "2",
              let platform = fields["platform"].flatMap(MotoComPlatform.init(rawValue:)),
              let role = fields["networkRole"].flatMap(NetworkRole.init(rawValue:)),
              let port = fields["tcpPort"].flatMap(Int.init),
              let deviceID = fields["deviceId"],
              let sessionID = fields["sessionId"] else { return nil }
        let knownCapabilities = Set(capabilities.compactMap(NetworkCapability.init(rawValue:)))
        return try? BootstrapAnnouncement(
            platform: platform,
            deviceID: deviceID,
            sessionID: sessionID,
            capabilities: knownCapabilities,
            networkRole: role,
            tcpPort: port
        )
    }

    public var hotspot: HotspotCredentials? {
        guard type == .hotspotReady,
              let ssid = fields["ssid"],
              let passphrase = fields["passphrase"],
              let security = fields["security"],
              let tcpPort = fields["tcpPort"].flatMap(Int.init) else { return nil }
        return try? HotspotCredentials(
            ssid: ssid,
            security: security,
            password: passphrase,
            hostAddress: fields["hostAddress"].flatMap { $0.isEmpty ? nil : $0 },
            tcpPort: tcpPort,
            expiresAtElapsedRealtimeMs: fields["expiresAtMs"].flatMap(Int64.init)
        )
    }

    public var joinResult: WifiJoinResult? {
        guard type == .wifiJoinResult else { return nil }
        return fields["result"].flatMap(WifiJoinResult.init(rawValue:))
    }

    public var reason: String? { fields["reason"] }
    public var endpointAddress: String? { fields["hostAddress"] }
    public var endpointPort: Int? { fields["tcpPort"].flatMap(Int.init) }

    public func validated() throws -> BootstrapMessage {
        guard protocolVersion == Self.protocolVersion else {
            throw MotoComError.unsupportedVersion(protocolVersion)
        }
        try UUIDValidator.requireCanonical(requestID, field: "requestId")
        guard fields.keys.allSatisfy({ !$0.isEmpty && $0.count <= 64 }) else {
            throw MotoComError.invalidField("BLE field name")
        }
        guard fields.values.allSatisfy({ $0.unicodeScalars.count <= Self.maxFieldValueCharacters }) else {
            throw MotoComError.invalidField("BLE field value")
        }
        guard capabilities.count <= Self.maxCapabilities else {
            throw MotoComError.invalidFrame("too many BLE capabilities")
        }
        guard capabilities.allSatisfy({
            !$0.isEmpty && $0.unicodeScalars.count <= 64 &&
                $0.unicodeScalars.allSatisfy {
                    (65...90).contains($0.value) || (48...57).contains($0.value) || $0.value == 95
                }
        }) else {
            throw MotoComError.invalidField("BLE capability")
        }
        return self
    }
}

public enum BootstrapCodec {
    public static let maxMessageBytes = 4 * 1024
    private static let rootKeys: Set<String> = [
        "protocolVersion", "type", "requestId", "fields", "capabilities"
    ]

    public static func encode(_ message: BootstrapMessage) throws -> Data {
        let value = try message.validated()
        let root: [String: Any] = [
            "protocolVersion": value.protocolVersion,
            "type": value.type.rawValue,
            "requestId": value.requestID,
            "fields": value.fields,
            "capabilities": value.capabilities.sorted()
        ]
        guard JSONSerialization.isValidJSONObject(root) else {
            throw MotoComError.invalidFrame("invalid BLE bootstrap JSON")
        }
        let data = try JSONSerialization.data(withJSONObject: root, options: [.sortedKeys])
        guard !data.isEmpty, data.count <= maxMessageBytes else {
            throw MotoComError.invalidFrame("BLE bootstrap message is too large")
        }
        return data
    }

    public static func decode(_ data: Data) throws -> BootstrapMessage {
        guard !data.isEmpty, data.count <= maxMessageBytes else {
            throw MotoComError.invalidFrame("BLE bootstrap message size")
        }
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw MotoComError.invalidFrame("invalid BLE bootstrap JSON")
        }
        guard Set(root.keys) == rootKeys else {
            throw MotoComError.unexpectedFields(Set(root.keys).subtracting(rootKeys))
        }
        guard let protocolVersion = integer(root["protocolVersion"]),
              protocolVersion == BootstrapMessage.protocolVersion else {
            throw MotoComError.unsupportedVersion(integer(root["protocolVersion"]) ?? 0)
        }
        guard let typeValue = root["type"] as? String,
              let type = BootstrapMessageType(rawValue: typeValue) else {
            throw MotoComError.unknownMessageType(String(describing: root["type"]))
        }
        guard let requestID = root["requestId"] as? String else {
            throw MotoComError.invalidField("requestId")
        }
        try UUIDValidator.requireCanonical(requestID, field: "requestId")
        guard let rawFields = root["fields"] as? [String: Any] else {
            throw MotoComError.invalidField("fields")
        }
        var fields = [String: String]()
        for (key, value) in rawFields {
            guard !key.isEmpty, key.count <= 64, let string = value as? String else {
                throw MotoComError.invalidField("fields.\(key)")
            }
            guard string.unicodeScalars.count <= BootstrapMessage.maxFieldValueCharacters else {
                throw MotoComError.invalidField("fields.\(key) is too long")
            }
            fields[key] = string
        }
        guard let rawCapabilities = root["capabilities"] as? [Any] else {
            throw MotoComError.invalidField("capabilities")
        }
        var capabilities = Set<String>()
        for (index, value) in rawCapabilities.enumerated() {
            guard let string = value as? String else {
                throw MotoComError.invalidField("capabilities[\(index)]")
            }
            guard capabilities.insert(string).inserted else {
                throw MotoComError.invalidFrame("duplicate BLE capability")
            }
        }
        return try BootstrapMessage(
            type: type,
            requestID: requestID,
            fields: fields,
            capabilities: capabilities,
            protocolVersion: protocolVersion
        ).validated()
    }

    /// Android exposes the capability advertisement as a compact read value,
    /// not as a BootstrapMessage envelope. Keep that read path compatible too.
    public static func encodeAdvertisement(_ announcement: BootstrapAnnouncement) throws -> Data {
        let root: [String: Any] = [
            "app": announcement.app,
            "protocolVersion": announcement.protocolVersion,
            "platform": announcement.platform.rawValue,
            "deviceId": announcement.deviceID,
            "sessionId": announcement.sessionID,
            "capabilities": announcement.capabilities.map(\.rawValue).sorted(),
            "networkRole": announcement.networkRole.rawValue,
            "tcpPort": announcement.tcpPort
        ]
        let data = try JSONSerialization.data(withJSONObject: root, options: [.sortedKeys])
        guard data.count <= maxMessageBytes else {
            throw MotoComError.invalidFrame("BLE advertisement is too large")
        }
        return data
    }

    public static func decodeAdvertisement(_ data: Data) throws -> BootstrapAnnouncement {
        guard !data.isEmpty, data.count <= maxMessageBytes,
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw MotoComError.invalidFrame("invalid BLE capability advertisement")
        }
        let expected: Set<String> = [
            "app", "protocolVersion", "platform", "deviceId", "sessionId",
            "capabilities", "networkRole", "tcpPort"
        ]
        guard Set(root.keys) == expected, root["app"] as? String == "motocom",
              let version = integer(root["protocolVersion"]), version == 2,
              let platformValue = root["platform"] as? String,
              let platform = MotoComPlatform(rawValue: platformValue),
              let deviceID = root["deviceId"] as? String,
              let sessionID = root["sessionId"] as? String,
              let roleValue = root["networkRole"] as? String,
              let role = NetworkRole(rawValue: roleValue),
              let port = integer(root["tcpPort"]),
              let rawCapabilities = root["capabilities"] as? [Any] else {
            throw MotoComError.invalidFrame("invalid BLE capability advertisement")
        }
        var capabilities = Set<NetworkCapability>()
        for (index, value) in rawCapabilities.enumerated() {
            guard let value = value as? String,
                  let capability = NetworkCapability(rawValue: value) else {
                throw MotoComError.invalidField("capabilities[\(index)]")
            }
            guard capabilities.insert(capability).inserted else {
                throw MotoComError.invalidFrame("duplicate BLE capability")
            }
        }
        return try BootstrapAnnouncement(
            platform: platform,
            deviceID: deviceID,
            sessionID: sessionID,
            capabilities: capabilities,
            networkRole: role,
            tcpPort: port
        )
    }

    private static func integer(_ value: Any?) -> Int? {
        StrictJSONNumber.integer(value)
    }
}

public struct BLEChunk: Equatable, Sendable {
    public let messageID: Int32
    public let index: Int
    public let count: Int
    public let messageBytes: Int
    public let payload: Data

    public init(messageID: Int32, index: Int, count: Int, messageBytes: Int, payload: Data) throws {
        guard count > 0, count <= 512, index >= 0, index < count else {
            throw MotoComError.invalidField("chunk index/count")
        }
        guard messageBytes > 0, messageBytes <= BootstrapCodec.maxMessageBytes else {
            throw MotoComError.invalidField("messageBytes")
        }
        guard !payload.isEmpty else { throw MotoComError.invalidField("chunk payload") }
        self.messageID = messageID
        self.index = index
        self.count = count
        self.messageBytes = messageBytes
        self.payload = payload
    }
}

public enum BLEFragmenter {
    public static let headerBytes = 12
    public static let defaultMaximumPacketBytes = 20
    public static let maximumPayloadBytes = defaultMaximumPacketBytes - headerBytes

    public static func fragment(
        _ data: Data,
        messageID: String = UUID().uuidString.lowercased(),
        maxPacketBytes: Int = defaultMaximumPacketBytes
    ) throws -> [BLEChunk] {
        try UUIDValidator.requireCanonical(messageID, field: "requestId")
        guard !data.isEmpty, data.count <= BootstrapCodec.maxMessageBytes else {
            throw MotoComError.invalidFrame("BLE message size")
        }
        guard maxPacketBytes > headerBytes else {
            throw MotoComError.invalidField("BLE packet size")
        }
        let payloadBytes = maxPacketBytes - headerBytes
        let count = (data.count + payloadBytes - 1) / payloadBytes
        guard count <= 512 else { throw MotoComError.invalidFrame("too many BLE chunks") }
        let numericID = javaStringHash(messageID)
        return try (0..<count).map { index in
            let start = index * payloadBytes
            let end = min(start + payloadBytes, data.count)
            return try BLEChunk(
                messageID: numericID,
                index: index,
                count: count,
                messageBytes: data.count,
                payload: Data(data[start..<end])
            )
        }
    }

    private static func javaStringHash(_ value: String) -> Int32 {
        var hash: Int32 = 0
        for byte in value.utf8 {
            hash = hash &* 31 &+ Int32(byte)
        }
        return hash
    }
}

public struct BLEReassembler: Sendable {
    private struct Pending: Sendable {
        var count: Int
        var messageBytes: Int
        var chunks: [Int: Data]
        var expiresAt: Date
    }

    private var pending = [Int32: Pending]()
    public let timeout: TimeInterval

    public init(timeout: TimeInterval = 5) {
        self.timeout = timeout
    }

    public mutating func append(_ chunk: BLEChunk, now: Date = Date()) -> Data? {
        expire(now: now)
        var entry = pending[chunk.messageID] ?? Pending(
            count: chunk.count,
            messageBytes: chunk.messageBytes,
            chunks: [:],
            expiresAt: now.addingTimeInterval(timeout)
        )
        guard entry.count == chunk.count, entry.messageBytes == chunk.messageBytes else {
            pending.removeValue(forKey: chunk.messageID)
            return nil
        }
        entry.chunks[chunk.index] = chunk.payload
        entry.expiresAt = now.addingTimeInterval(timeout)
        pending[chunk.messageID] = entry
        guard entry.chunks.count == entry.count else { return nil }
        let data = (0..<entry.count)
            .compactMap { entry.chunks[$0] }
            .reduce(into: Data()) { $0.append($1) }
        pending.removeValue(forKey: chunk.messageID)
        guard data.count == entry.messageBytes else { return nil }
        return data
    }

    public mutating func expire(now: Date = Date()) {
        pending = pending.filter { $0.value.expiresAt > now }
    }

    public var pendingMessageCount: Int { pending.count }
}

public enum BLEChunkWireCodec {
    public static func encode(_ chunk: BLEChunk) throws -> Data {
        var data = Data(capacity: BLEFragmenter.headerBytes + chunk.payload.count)
        appendUInt32(UInt32(bitPattern: chunk.messageID), to: &data)
        appendUInt16(chunk.index, to: &data)
        appendUInt16(chunk.count, to: &data)
        appendUInt32(UInt32(chunk.messageBytes), to: &data)
        data.append(chunk.payload)
        return data
    }

    public static func decode(_ data: Data) throws -> BLEChunk {
        guard data.count >= BLEFragmenter.headerBytes else {
            throw MotoComError.invalidFrame("BLE chunk is too small")
        }
        let messageID = Int32(bitPattern: readUInt32(data, offset: 0))
        let index = Int(readUInt16(data, offset: 4))
        let count = Int(readUInt16(data, offset: 6))
        let messageBytes = Int(readUInt32(data, offset: 8))
        return try BLEChunk(
            messageID: messageID,
            index: index,
            count: count,
            messageBytes: messageBytes,
            payload: Data(data.dropFirst(BLEFragmenter.headerBytes))
        )
    }

    private static func appendUInt16(_ value: Int, to data: inout Data) {
        data.append(UInt8((value >> 8) & 0xFF))
        data.append(UInt8(value & 0xFF))
    }

    private static func appendUInt32(_ value: UInt32, to data: inout Data) {
        data.append(UInt8((value >> 24) & 0xFF))
        data.append(UInt8((value >> 16) & 0xFF))
        data.append(UInt8((value >> 8) & 0xFF))
        data.append(UInt8(value & 0xFF))
    }

    private static func readUInt16(_ data: Data, offset: Int) -> UInt16 {
        (UInt16(data[offset]) << 8) | UInt16(data[offset + 1])
    }

    private static func readUInt32(_ data: Data, offset: Int) -> UInt32 {
        (UInt32(data[offset]) << 24)
            | (UInt32(data[offset + 1]) << 16)
            | (UInt32(data[offset + 2]) << 8)
            | UInt32(data[offset + 3])
    }
}
