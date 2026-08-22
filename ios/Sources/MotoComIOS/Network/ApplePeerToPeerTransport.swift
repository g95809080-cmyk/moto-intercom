import Foundation

#if canImport(Network)
import Network

public protocol ApplePeerToPeerTransporting: AnyObject {
    func startListener(serviceName: String, txtRecord: NWTXTRecord) throws
    func startBrowsing()
    func connect(to endpoint: NWEndpoint, completion: @escaping (Result<NWConnection, Error>) -> Void)
    func stop()
}

public final class ApplePeerToPeerTransport: ApplePeerToPeerTransporting, @unchecked Sendable {
    private let queue = DispatchQueue(label: "com.motocom.apple-p2p")
    private var listener: NWListener?
    private var browser: NWBrowser?
    private var connections = [ObjectIdentifier: NWConnection]()

    public var onConnection: ((NWConnection) -> Void)?
    public var onPeerFound: ((NWEndpoint, BonjourServiceAdvertisement?) -> Void)?
    public var onError: ((Error) -> Void)?

    public init() {}

    public func startListener(serviceName: String, txtRecord: NWTXTRecord) throws {
        stop()
        let parameters = NWParameters.tcp
        parameters.includePeerToPeer = true
        guard let port = NWEndpoint.Port(rawValue: BonjourTransport.tcpPort) else {
            throw MotoComError.invalidField("tcpPort")
        }
        let listener = try NWListener(using: parameters, on: port)
        listener.service = NWListener.Service(
            name: serviceName,
            type: BonjourTransport.serviceType,
            domain: nil,
            txtRecord: txtRecord
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
        browser?.cancel()
        let parameters = NWParameters.tcp
        parameters.includePeerToPeer = true
        let browser = NWBrowser(
            for: .bonjourWithTXTRecord(type: BonjourTransport.serviceType, domain: nil),
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

    public func connect(to endpoint: NWEndpoint, completion: @escaping (Result<NWConnection, Error>) -> Void) {
        let parameters = NWParameters.tcp
        parameters.includePeerToPeer = true
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
            if case .ready = state {
                self?.connections[ObjectIdentifier(connection)] = connection
                self?.onConnection?(connection)
            }
        }
        connection.start(queue: queue)
    }
}
#endif
