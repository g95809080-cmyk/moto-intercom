import Foundation
#if canImport(Network)
import Network

public protocol ApplePeerToPeerTransporting: AnyObject {
    func startListener(serviceName: String, txtRecord: NWTXTRecord) throws
    func startBrowsing()
    func connect(to endpoint: NWEndpoint, completion: @escaping (Result<NWConnection, Error>) -> Void)
    func stop()
}

public final class ApplePeerToPeerTransport: ApplePeerToPeerTransporting, IOSControlTransport, @unchecked Sendable {
    private let core = NetworkTransportCore(label: "com.motocom.apple-p2p")
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
        try startListener(serviceName: "motocom-\(advertisement.deviceID.prefix(8))", txtRecord: advertisement.txtRecord())
    }
    public func startListener(serviceName: String, txtRecord: NWTXTRecord) throws { try core.startListener(serviceName: serviceName, txtRecord: txtRecord) }
    public func startBrowsing() { core.startBrowsing(includePeerToPeer: true) }
    public func connect(to endpoint: NWEndpoint, completion: @escaping (Result<NWConnection, Error>) -> Void) {
        connect(to: endpoint, includePeerToPeer: true, completion: completion)
    }
    @discardableResult public func connect(to endpoint: NWEndpoint, includePeerToPeer: Bool, completion: @escaping (Result<NWConnection, Error>) -> Void) -> IOSConnectionCancellation {
        core.connect(to: endpoint, includePeerToPeer: includePeerToPeer, completion: completion)
    }
    public func stop() { core.stop() }
}
#endif
