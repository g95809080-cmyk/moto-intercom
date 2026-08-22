import Foundation

public enum SignalingMessageType: String, Equatable, Sendable {
    case hello = "HELLO"
    case connectRequest = "CONNECT_REQUEST"
    case connectAccept = "CONNECT_ACCEPT"
    case connectReject = "CONNECT_REJECT"
    case busy = "BUSY"
    case disconnect = "DISCONNECT"
    case offer = "OFFER"
    case answer = "ANSWER"
    case candidate = "CANDIDATE"
}

public enum RequestRole: String, Equatable, Sendable {
    case requester = "REQUESTER"
    case responder = "RESPONDER"

    public var isOfferer: Bool { self == .requester }
}

public enum RequestTrigger: String, Equatable, Sendable {
    case user = "USER"
    case autoPaired = "AUTO_PAIRED"
    case inbound = "INBOUND"
    case recovery = "RECOVERY"
}

public enum TransportHint: String, Equatable, Sendable {
    case lan = "LAN"
    case wifiDirect = "WIFI_DIRECT"
}

public enum RejectReason: String, Equatable, Sendable {
    case supersededChannel = "SUPERSEDED_CHANNEL"
    case identityMismatch = "IDENTITY_MISMATCH"
    case protocolError = "PROTOCOL_ERROR"
    case userRejected = "USER_REJECTED"
    case timeout = "TIMEOUT"
    case confirmationUnavailable = "CONFIRMATION_UNAVAILABLE"
    case canceled = "CANCELED"
    case glareLost = "GLARE_LOST"
    case unsupportedVersion = "UNSUPPORTED_VERSION"
}

public enum SignalingMessage: Equatable, Sendable {
    case hello(requestRole: RequestRole, nickname: String?, deviceName: String?, capabilities: Set<String>)
    case connectRequest(trigger: RequestTrigger, preferredTransportHint: TransportHint?)
    case connectAccept(nickname: String, deviceName: String)
    case connectReject(reason: RejectReason, retryable: Bool)
    case busy(reason: String, retryAfterMilliseconds: Int64?)
    case disconnect(reason: String)
    case offer(sdpJSON: String)
    case answer(sdpJSON: String)
    case candidate(json: Data)

    public var type: SignalingMessageType {
        switch self {
        case .hello: return .hello
        case .connectRequest: return .connectRequest
        case .connectAccept: return .connectAccept
        case .connectReject: return .connectReject
        case .busy: return .busy
        case .disconnect: return .disconnect
        case .offer: return .offer
        case .answer: return .answer
        case .candidate: return .candidate
        }
    }
}

public struct SignalingEnvelope: Equatable, Sendable {
    public static let protocolVersion = 2

    public let protocolVersion: Int
    public let attemptID: String
    public let sourceDeviceID: String
    public let targetDeviceID: String
    public let sourceSessionID: String
    public let message: SignalingMessage

    public init(
        protocolVersion: Int = SignalingEnvelope.protocolVersion,
        attemptID: String,
        sourceDeviceID: String,
        targetDeviceID: String,
        sourceSessionID: String,
        message: SignalingMessage
    ) throws {
        guard protocolVersion == Self.protocolVersion else {
            throw MotoComError.unsupportedVersion(protocolVersion)
        }
        try UUIDValidator.requireCanonical(attemptID, field: "attemptId")
        try UUIDValidator.requireCanonical(sourceDeviceID, field: "sourceDeviceId")
        try UUIDValidator.requireCanonical(targetDeviceID, field: "targetDeviceId")
        try UUIDValidator.requireCanonical(sourceSessionID, field: "sourceSessionId")
        guard sourceDeviceID != targetDeviceID else {
            throw MotoComError.invalidField("sourceDeviceId and targetDeviceId must differ")
        }
        self.protocolVersion = protocolVersion
        self.attemptID = attemptID
        self.sourceDeviceID = sourceDeviceID
        self.targetDeviceID = targetDeviceID
        self.sourceSessionID = sourceSessionID
        self.message = message
    }
}

public final class SignalingV2Codec {
    public static let maxFrameBytes = 128 * 1024
    public static let maxSDPBytes = 64 * 1024
    public static let maxCandidateBytes = 4 * 1024
    public static let maxCandidates = 256
    public static let maxNicknameCodePoints = 64
    public static let maxDeviceNameCodePoints = 128
    public static let maxCapabilities = 32
    public static let maxCapabilityCodePoints = 64

    private var encodedCandidateCount = 0
    private var decodedCandidateCount = 0

    public init() {}

    public func encode(_ envelope: SignalingEnvelope) throws -> Data {
        let root: [String: Any] = [
            "protocolVersion": envelope.protocolVersion,
            "type": envelope.message.type.rawValue,
            "attemptId": envelope.attemptID,
            "sourceDeviceId": envelope.sourceDeviceID,
            "targetDeviceId": envelope.targetDeviceID,
            "sourceSessionId": envelope.sourceSessionID,
            "payload": try encodePayload(envelope.message)
        ]
        let data = try jsonData(root)
        try requireByteLimit(data, name: "frame", maximum: Self.maxFrameBytes)
        return data
    }

    public func decode(_ data: Data) throws -> SignalingEnvelope {
        try requireByteLimit(data, name: "frame", maximum: Self.maxFrameBytes)
        let root = try object(from: data, name: "frame")
        try requireExactKeys(root, expected: [
            "protocolVersion", "type", "attemptId", "sourceDeviceId",
            "targetDeviceId", "sourceSessionId", "payload"
        ])
        let version = try integer(root, key: "protocolVersion")
        guard version == SignalingEnvelope.protocolVersion else {
            throw MotoComError.unsupportedVersion(version)
        }
        let type = try enumValue(SignalingMessageType.self, root, key: "type")
        let payload = try object(root, key: "payload")
        let attemptID = try canonical(root, key: "attemptId")
        let sourceDeviceID = try canonical(root, key: "sourceDeviceId")
        let targetDeviceID = try canonical(root, key: "targetDeviceId")
        let sourceSessionID = try canonical(root, key: "sourceSessionId")
        let message = try decodePayload(type, payload)
        return try SignalingEnvelope(
            protocolVersion: version,
            attemptID: attemptID,
            sourceDeviceID: sourceDeviceID,
            targetDeviceID: targetDeviceID,
            sourceSessionID: sourceSessionID,
            message: message
        )
    }

    private func encodePayload(_ message: SignalingMessage) throws -> [String: Any] {
        switch message {
        case .hello(let role, let nickname, let deviceName, let capabilities):
            var payload: [String: Any] = [
                "requestRole": role.rawValue,
                "capabilities": try encodeCapabilities(capabilities)
            ]
            if let nickname {
                payload["nickname"] = try boundedText(nickname, name: "nickname", maximum: Self.maxNicknameCodePoints)
            }
            if let deviceName {
                payload["deviceName"] = try boundedText(deviceName, name: "deviceName", maximum: Self.maxDeviceNameCodePoints)
            }
            return payload
        case .connectRequest(let trigger, let hint):
            var payload: [String: Any] = ["trigger": trigger.rawValue]
            if let hint { payload["preferredTransportHint"] = hint.rawValue }
            return payload
        case .connectAccept(let nickname, let deviceName):
            return [
                "nickname": try boundedText(nickname, name: "nickname", maximum: Self.maxNicknameCodePoints),
                "deviceName": try boundedText(deviceName, name: "deviceName", maximum: Self.maxDeviceNameCodePoints)
            ]
        case .connectReject(let reason, let retryable):
            return ["reason": reason.rawValue, "retryable": retryable]
        case .busy(let reason, let retryAfterMilliseconds):
            var payload: [String: Any] = ["reason": try wireReason(reason, field: "busy reason")]
            if let retryAfterMilliseconds {
                guard retryAfterMilliseconds >= 0 else { throw MotoComError.invalidField("retryAfterMs") }
                payload["retryAfterMs"] = retryAfterMilliseconds
            }
            return payload
        case .disconnect(let reason):
            return ["reason": try wireReason(reason, field: "disconnect reason")]
        case .offer(let sdpJSON):
            return ["sdp": try boundedPayload(sdpJSON, name: "sdp", maximum: Self.maxSDPBytes)]
        case .answer(let sdpJSON):
            return ["sdp": try boundedPayload(sdpJSON, name: "sdp", maximum: Self.maxSDPBytes)]
        case .candidate(let json):
            encodedCandidateCount += 1
            guard encodedCandidateCount <= Self.maxCandidates else {
                throw MotoComError.invalidFrame("too many encoded candidates")
            }
            try requireByteLimit(json, name: "candidate", maximum: Self.maxCandidateBytes)
            let candidate = try object(from: json, name: "candidate")
            return ["candidate": candidate]
        }
    }

    private func decodePayload(_ type: SignalingMessageType, _ payload: [String: Any]) throws -> SignalingMessage {
        switch type {
        case .hello:
            try requireKeys(payload, required: ["requestRole", "capabilities"], allowed: ["requestRole", "nickname", "deviceName", "capabilities"])
            return .hello(
                requestRole: try enumValue(RequestRole.self, payload, key: "requestRole"),
                nickname: try optionalBoundedText(payload, key: "nickname", maximum: Self.maxNicknameCodePoints),
                deviceName: try optionalBoundedText(payload, key: "deviceName", maximum: Self.maxDeviceNameCodePoints),
                capabilities: try decodeCapabilities(payload["capabilities"])
            )
        case .connectRequest:
            try requireKeys(payload, required: ["trigger"], allowed: ["trigger", "preferredTransportHint"])
            return .connectRequest(
                trigger: try enumValue(RequestTrigger.self, payload, key: "trigger"),
                preferredTransportHint: try optionalEnumValue(TransportHint.self, payload, key: "preferredTransportHint")
            )
        case .connectAccept:
            try requireExactKeys(payload, expected: ["nickname", "deviceName"])
            return .connectAccept(
                nickname: try boundedText(try string(payload, key: "nickname"), name: "nickname", maximum: Self.maxNicknameCodePoints),
                deviceName: try boundedText(try string(payload, key: "deviceName"), name: "deviceName", maximum: Self.maxDeviceNameCodePoints)
            )
        case .connectReject:
            try requireExactKeys(payload, expected: ["reason", "retryable"])
            return .connectReject(
                reason: try enumValue(RejectReason.self, payload, key: "reason"),
                retryable: try boolean(payload, key: "retryable")
            )
        case .busy:
            try requireKeys(payload, required: ["reason"], allowed: ["reason", "retryAfterMs"])
            let retry = try optionalInteger(payload, key: "retryAfterMs")
            if let retry, retry < 0 { throw MotoComError.invalidField("retryAfterMs") }
            return .busy(reason: try wireReason(try string(payload, key: "reason"), field: "busy reason"), retryAfterMilliseconds: retry)
        case .disconnect:
            try requireExactKeys(payload, expected: ["reason"])
            return .disconnect(reason: try wireReason(try string(payload, key: "reason"), field: "disconnect reason"))
        case .offer:
            try requireExactKeys(payload, expected: ["sdp"])
            return .offer(sdpJSON: try boundedPayload(try string(payload, key: "sdp"), name: "sdp", maximum: Self.maxSDPBytes))
        case .answer:
            try requireExactKeys(payload, expected: ["sdp"])
            return .answer(sdpJSON: try boundedPayload(try string(payload, key: "sdp"), name: "sdp", maximum: Self.maxSDPBytes))
        case .candidate:
            try requireExactKeys(payload, expected: ["candidate"])
            decodedCandidateCount += 1
            guard decodedCandidateCount <= Self.maxCandidates else {
                throw MotoComError.invalidFrame("too many decoded candidates")
            }
            let candidate = try jsonData(try object(payload, key: "candidate"))
            try requireByteLimit(candidate, name: "candidate", maximum: Self.maxCandidateBytes)
            return .candidate(json: candidate)
        }
    }

    private func encodeCapabilities(_ values: Set<String>) throws -> [String] {
        guard values.count <= Self.maxCapabilities else { throw MotoComError.invalidFrame("too many capabilities") }
        let bounded = try values.map {
            try boundedText($0, name: "capability", maximum: Self.maxCapabilityCodePoints)
        }
        guard Set(bounded).count == bounded.count else { throw MotoComError.invalidFrame("duplicate capabilities") }
        return bounded.sorted()
    }

    private func decodeCapabilities(_ value: Any?) throws -> Set<String> {
        guard let values = value as? [Any] else { throw MotoComError.invalidField("capabilities") }
        guard values.count <= Self.maxCapabilities else { throw MotoComError.invalidFrame("too many capabilities") }
        var result = Set<String>()
        for (index, value) in values.enumerated() {
            guard let string = value as? String else { throw MotoComError.invalidField("capabilities[\(index)]") }
            let bounded = try boundedText(string, name: "capabilities[\(index)]", maximum: Self.maxCapabilityCodePoints)
            guard result.insert(bounded).inserted else { throw MotoComError.invalidFrame("duplicate capabilities") }
        }
        return result
    }

    private func jsonData(_ object: [String: Any]) throws -> Data {
        guard JSONSerialization.isValidJSONObject(object) else { throw MotoComError.invalidFrame("not JSON") }
        return try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
    }

    private func object(from data: Data, name: String) throws -> [String: Any] {
        guard let object = try? JSONSerialization.jsonObject(with: data), let dictionary = object as? [String: Any] else {
            throw MotoComError.invalidFrame("\(name) must be a JSON object")
        }
        return dictionary
    }

    private func object(_ dictionary: [String: Any], key: String) throws -> [String: Any] {
        guard let value = dictionary[key] as? [String: Any] else { throw MotoComError.invalidField(key) }
        return value
    }

    private func string(_ dictionary: [String: Any], key: String) throws -> String {
        guard let value = dictionary[key] as? String else { throw MotoComError.invalidField(key) }
        return value
    }

    private func boolean(_ dictionary: [String: Any], key: String) throws -> Bool {
        guard let value = dictionary[key],
              StrictJSONNumber.isBoolean(value),
              let result = value as? Bool else {
            throw MotoComError.invalidField(key)
        }
        return result
    }

    private func integer(_ dictionary: [String: Any], key: String) throws -> Int {
        guard let result = StrictJSONNumber.integer(dictionary[key]) else {
            throw MotoComError.invalidField(key)
        }
        return result
    }

    private func optionalInteger(_ dictionary: [String: Any], key: String) throws -> Int64? {
        guard dictionary[key] != nil else { return nil }
        guard let result = StrictJSONNumber.int64(dictionary[key]) else {
            throw MotoComError.invalidField(key)
        }
        return result
    }

    private func canonical(_ dictionary: [String: Any], key: String) throws -> String {
        let value = try string(dictionary, key: key)
        try UUIDValidator.requireCanonical(value, field: key)
        return value
    }

    private func boundedText(_ value: String, name: String, maximum: Int) throws -> String {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw MotoComError.invalidField(name) }
        guard trimmed.unicodeScalars.count <= maximum else { throw MotoComError.invalidField("\(name) is too long") }
        return trimmed
    }

    private func optionalBoundedText(_ dictionary: [String: Any], key: String, maximum: Int) throws -> String? {
        guard let value = dictionary[key] else { return nil }
        guard let string = value as? String else { throw MotoComError.invalidField(key) }
        return try boundedText(string, name: key, maximum: maximum)
    }

    private func boundedPayload(_ value: String, name: String, maximum: Int) throws -> String {
        let data = Data(value.utf8)
        try requireByteLimit(data, name: name, maximum: maximum)
        return value
    }

    private func wireReason(_ value: String, field: String) throws -> String {
        let scalars = value.unicodeScalars
        guard scalars.count <= 64,
              let first = scalars.first,
              isUppercaseASCII(first),
              scalars.dropFirst().allSatisfy({ isUppercaseASCII($0) || isDigitASCII($0) || $0.value == 95 }) else {
            throw MotoComError.invalidField(field)
        }
        return value
    }

    private func isUppercaseASCII(_ scalar: UnicodeScalar) -> Bool {
        (65...90).contains(scalar.value)
    }

    private func isDigitASCII(_ scalar: UnicodeScalar) -> Bool {
        (48...57).contains(scalar.value)
    }

    private func requireByteLimit(_ data: Data, name: String, maximum: Int) throws {
        guard !data.isEmpty, data.count <= maximum else {
            throw MotoComError.invalidFrame("\(name) bytes=\(data.count) max=\(maximum)")
        }
    }

    private func requireExactKeys(_ dictionary: [String: Any], expected: Set<String>) throws {
        try requireKeys(dictionary, required: expected, allowed: expected)
    }

    private func requireKeys(_ dictionary: [String: Any], required: Set<String>, allowed: Set<String>) throws {
        let actual = Set(dictionary.keys)
        let missing = required.subtracting(actual)
        guard missing.isEmpty else { throw MotoComError.missingField(missing.sorted().joined(separator: ",")) }
        let unknown = actual.subtracting(allowed)
        guard unknown.isEmpty else { throw MotoComError.unexpectedFields(unknown) }
    }

    private func enumValue<T: RawRepresentable>(_ type: T.Type, _ dictionary: [String: Any], key: String) throws -> T where T.RawValue == String {
        let value = try string(dictionary, key: key)
        guard let result = T(rawValue: value) else { throw MotoComError.unknownEnum(field: key, value: value) }
        return result
    }

    private func optionalEnumValue<T: RawRepresentable>(_ type: T.Type, _ dictionary: [String: Any], key: String) throws -> T? where T.RawValue == String {
        guard dictionary[key] != nil else { return nil }
        return try enumValue(type, dictionary, key: key)
    }
}
