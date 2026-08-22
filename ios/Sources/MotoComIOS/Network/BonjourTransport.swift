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

public final class BonjourTransport: @unchecked Sendable {
    public static let serviceType = "_motocom._tcp."
    public static let tcpPort: UInt16 = 8890

    private let queue = DispatchQueue(label: "com.motocom.bonjour")
    private var listener: NWListener?
    private var browser: NWBrowser?
    private var connections = [ObjectIdentifier: NWConnection]()

    public var onPeerFound: ((NWEndpoint, BonjourServiceAdvertisement?) -> Void)?
    public var onConnection: ((NWConnection) -> Void)?
    public var onError: ((Error) -> Void)?

    public init() {}

    public func start(advertisement: BonjourServiceAdvertisement) throws {
        stop()
        let parameters = NWParameters.tcp
        parameters.includePeerToPeer = true
        guard let port = NWEndpoint.Port(rawValue: Self.tcpPort) else {
            throw MotoComError.invalidField("tcpPort")
        }
        let listener = try NWListener(using: parameters, on: port)
        listener.service = NWListener.Service(
            name: "motocom-\(advertisement.deviceID.prefix(8))",
            type: Self.serviceType,
            domain: nil,
            txtRecord: advertisement.txtRecord()
        )
        listener.newConnectionHandler = { [weak self] connection in
            self?.accept(connection)
        }
        listener.stateUpdateHandler = { [weak self] state in
            if case .failed(let error) = state { self?.onError?(error) }
        }
        listener.start(queue: queue)
        self.listener = listener
    }

    public func startBrowsing() {
        let parameters = NWParameters.tcp
        parameters.includePeerToPeer = false
        let browser = NWBrowser(
            for: .bonjourWithTXTRecord(type: Self.serviceType, domain: nil),
            using: parameters
        )
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            for result in results {
                let advertisement: BonjourServiceAdvertisement?
                if case .bonjour(let record) = result.metadata {
                    advertisement = try? BonjourServiceAdvertisement(txtRecord: record)
                } else {
                    advertisement = nil
                }
                self?.onPeerFound?(result.endpoint, advertisement)
            }
        }
        browser.stateUpdateHandler = { [weak self] state in
            if case .failed(let error) = state { self?.onError?(error) }
        }
        browser.start(queue: queue)
        self.browser = browser
    }

    public func connect(
        to endpoint: NWEndpoint,
        includePeerToPeer: Bool = true,
        completion: @escaping (Result<NWConnection, Error>) -> Void
    ) {
        let parameters = NWParameters.tcp
        parameters.includePeerToPeer = includePeerToPeer
        let connection = NWConnection(to: endpoint, using: parameters)
        let completionLock = NSLock()
        var completed = false
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                completionLock.lock()
                guard !completed else {
                    completionLock.unlock()
                    return
                }
                completed = true
                completionLock.unlock()
                self?.connections[ObjectIdentifier(connection)] = connection
                completion(.success(connection))
            case .failed(let error):
                completionLock.lock()
                guard !completed else {
                    completionLock.unlock()
                    return
                }
                completed = true
                completionLock.unlock()
                completion(.failure(error))
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    public func stop() {
        listener?.cancel()
        browser?.cancel()
        listener = nil
        browser = nil
        connections.values.forEach { $0.cancel() }
        connections.removeAll()
    }

    private func accept(_ connection: NWConnection) {
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                self?.connections[ObjectIdentifier(connection)] = connection
                self?.onConnection?(connection)
            case .failed:
                self?.connections.removeValue(forKey: ObjectIdentifier(connection))
            default:
                break
            }
        }
        connection.start(queue: queue)
    }
}
#endif
