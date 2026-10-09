import Foundation

#if canImport(Network)
import Network

public struct BonjourServiceAdvertisement: Sendable, Equatable {
    public let deviceID: String
    public let sessionID: String
    public let nickname: String
    public let deviceName: String
    public let platform: MotoComPlatform
    public let capabilities: Set<NetworkCapability>
    public let networkRole: NetworkRole
    public let tcpPort: Int

    public init(
        deviceID: String,
        sessionID: String,
        nickname: String,
        deviceName: String,
        platform: MotoComPlatform = .ios,
        capabilities: Set<NetworkCapability>,
        networkRole: NetworkRole = .either,
        tcpPort: Int = 8890
    ) throws {
        try UUIDValidator.requireCanonical(deviceID, field: "deviceId")
        try UUIDValidator.requireCanonical(sessionID, field: "sessionId")
        guard !nickname.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              nickname.unicodeScalars.count <= 64 else {
            throw MotoComError.invalidField("name")
        }
        guard !deviceName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              deviceName.unicodeScalars.count <= 128 else {
            throw MotoComError.invalidField("deviceName")
        }
        guard capabilities.count <= 32 else {
            throw MotoComError.invalidFrame("too many Bonjour capabilities")
        }
        guard (1...65535).contains(tcpPort) else { throw MotoComError.invalidField("tcpPort") }
        self.deviceID = deviceID
        self.sessionID = sessionID
        self.nickname = nickname
        self.deviceName = deviceName
        self.platform = platform
        self.capabilities = capabilities
        self.networkRole = networkRole
        self.tcpPort = tcpPort
    }

    public init(txtRecord: NWTXTRecord) throws {
        let values = txtRecord.dictionary
        let requiredKeys: Set<String> = [
            "id", "sessionId", "name", "deviceName", "protocolVersion",
            "platform", "capabilities", "networkRole"
        ]
        let allowedKeys = requiredKeys.union(["tcpPort"])
        guard requiredKeys.isSubset(of: Set(values.keys)) else {
            throw MotoComError.missingField(requiredKeys.subtracting(values.keys).sorted().joined(separator: ","))
        }
        guard Set(values.keys).isSubset(of: allowedKeys) else {
            throw MotoComError.unexpectedFields(Set(values.keys).subtracting(allowedKeys))
        }
        guard values["protocolVersion"] == "2" else {
            throw MotoComError.unsupportedVersion(Int(values["protocolVersion"] ?? "0") ?? 0)
        }
        guard let platformValue = values["platform"],
              let platform = MotoComPlatform(rawValue: platformValue) else {
            throw MotoComError.invalidField("platform")
        }
        guard let networkRoleValue = values["networkRole"],
              let networkRole = NetworkRole(rawValue: networkRoleValue),
              let tcpPort = Int(values["tcpPort"] ?? "8890") else {
            throw MotoComError.invalidField("networkRole/tcpPort")
        }
        let capabilities = Set(
            (values["capabilities"] ?? "")
                .split(separator: ",")
                .compactMap { NetworkCapability(rawValue: String($0)) }
        )
        guard capabilities.count <= 32 else {
            throw MotoComError.invalidFrame("too many Bonjour capabilities")
        }
        try self.init(
            deviceID: values["id"] ?? "",
            sessionID: values["sessionId"] ?? "",
            nickname: values["name"] ?? "",
            deviceName: values["deviceName"] ?? "",
            platform: platform,
            capabilities: capabilities,
            networkRole: networkRole,
            tcpPort: tcpPort
        )
    }

    public func txtRecord() -> NWTXTRecord {
        let values: [String: String] = [
            "id": deviceID,
            "sessionId": sessionID,
            "name": nickname,
            "deviceName": deviceName,
            "protocolVersion": "2",
            "platform": platform.rawValue,
            "capabilities": capabilities.map(\.rawValue).sorted().joined(separator: ","),
            "networkRole": networkRole.rawValue,
            "tcpPort": String(tcpPort)
        ]
        return NWTXTRecord(values)
    }
}

public final class BonjourTransport: IOSControlTransport, @unchecked Sendable {
    public static let serviceType = "_motocom._tcp."
    public static let tcpPort: UInt16 = 8890
    private let core = NetworkTransportCore(label: "com.motocom.bonjour")
    public var onPeerFound: ((NWEndpoint, BonjourServiceAdvertisement?) -> Void)? {
        get { core.owned { core.peerHandler } } set { core.owned { core.peerHandler = newValue } }
    }
    public var onConnection: ((NWConnection) -> Void)? {
        get { core.owned { core.connectionHandler } } set { core.owned { core.connectionHandler = newValue } }
    }
    public var onError: ((Error) -> Void)? {
        get { core.owned { core.errorHandler } } set { core.owned { core.errorHandler = newValue } }
    }
    public init() {}
    public func start(advertisement: BonjourServiceAdvertisement) throws {
        try core.startListener(serviceName: "motocom-\(advertisement.deviceID.prefix(8))", txtRecord: advertisement.txtRecord())
    }
    public func startBrowsing() { core.startBrowsing(includePeerToPeer: false) }
    @discardableResult public func connect(to endpoint: NWEndpoint, includePeerToPeer: Bool = true, completion: @escaping (Result<NWConnection, Error>) -> Void) -> IOSConnectionCancellation {
        core.connect(to: endpoint, includePeerToPeer: includePeerToPeer, completion: completion)
    }
    public func stop() { core.stop() }
}
#endif
