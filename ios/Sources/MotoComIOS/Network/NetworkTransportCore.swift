import Foundation
#if canImport(Network)
import Network

public protocol IOSControlTransport: AnyObject {
    var onPeerFound: ((NWEndpoint, BonjourServiceAdvertisement?) -> Void)? { get set }
    var onConnection: ((NWConnection) -> Void)? { get set }
    var onError: ((Error) -> Void)? { get set }
    func start(advertisement: BonjourServiceAdvertisement) throws
    func startBrowsing()
    func connect(to endpoint: NWEndpoint, includePeerToPeer: Bool, completion: @escaping (Result<NWConnection, Error>) -> Void)
    func stop()
}

/// One queue owns listener, browser and every pending/ready connection. Native
/// closures capture both epoch and object, never consult newer callbacks.
/// Wrappers provide queue-confined registration through this shared core.
final class NetworkTransportCore: @unchecked Sendable {
    let queue: DispatchQueue
    private let key = DispatchSpecificKey<Bool>()
    private var epoch = UUID()
    private var listener: NWListener?
    private var browser: NWBrowser?
    private var entries = [ObjectIdentifier: Entry]()
    var peerHandler: ((NWEndpoint, BonjourServiceAdvertisement?) -> Void)?
    var connectionHandler: ((NWConnection) -> Void)?
    var errorHandler: ((Error) -> Void)?
    private final class Entry {
        let connection: NWConnection
        let epoch: UUID
        var completed = false
        var timeout: DispatchWorkItem?
        let completion: (Result<NWConnection, Error>) -> Void
        init(_ connection: NWConnection, _ epoch: UUID, _ completion: @escaping (Result<NWConnection, Error>) -> Void) {
            self.connection = connection; self.epoch = epoch; self.completion = completion
        }
    }
    init(label: String) {
        queue = DispatchQueue(label: label); queue.setSpecific(key: key, value: true)
    }
    func owned<T>(_ action: () throws -> T) rethrows -> T {
        if DispatchQueue.getSpecific(key: key) == true { return try action() }
        return try queue.sync(execute: action)
    }
    func startListener(serviceName: String, txtRecord: NWTXTRecord) throws {
        try owned {
            stop()
            let run = epoch
            let parameters = NWParameters.tcp; parameters.includePeerToPeer = true
            let listener = try NWListener(using: parameters, on: NWEndpoint.Port(rawValue: BonjourTransport.tcpPort)!)
            listener.service = NWListener.Service(name: serviceName, type: BonjourTransport.serviceType, domain: nil, txtRecord: txtRecord)
            let incoming = connectionHandler; let errors = errorHandler
            listener.newConnectionHandler = { [weak self, weak listener] connection in
                guard let self, let listener, self.epoch == run, self.listener === listener else { connection.cancel(); return }
                self.track(connection, epoch: run) { result in
                    if case .success(let connection) = result { incoming?(connection) }
                }
            }
            listener.stateUpdateHandler = { [weak self, weak listener] state in
                guard let self, let listener, self.epoch == run, self.listener === listener else { return }
                if case .failed(let error) = state { errors?(error) }
            }
            self.listener = listener; listener.start(queue: queue)
        }
    }
    func startBrowsing(includePeerToPeer: Bool) {
        owned {
            browser?.cancel()
            let run = epoch
            let parameters = NWParameters.tcp; parameters.includePeerToPeer = includePeerToPeer
            let browser = NWBrowser(for: .bonjourWithTXTRecord(type: BonjourTransport.serviceType, domain: nil), using: parameters)
            let peers = peerHandler; let errors = errorHandler
            browser.browseResultsChangedHandler = { [weak self, weak browser] results, _ in
                guard let self, let browser, self.epoch == run, self.browser === browser else { return }
                for result in results {
                    let advertisement: BonjourServiceAdvertisement?
                    if case .bonjour(let record) = result.metadata { advertisement = try? BonjourServiceAdvertisement(txtRecord: record) }
                    else { advertisement = nil }
                    peers?(result.endpoint, advertisement)
                }
            }
            browser.stateUpdateHandler = { [weak self, weak browser] state in
                guard let self, let browser, self.epoch == run, self.browser === browser else { return }
                if case .failed(let error) = state { errors?(error) }
            }
            self.browser = browser; browser.start(queue: queue)
        }
    }
    func connect(to endpoint: NWEndpoint, includePeerToPeer: Bool, completion: @escaping (Result<NWConnection, Error>) -> Void) {
        owned {
            let parameters = NWParameters.tcp; parameters.includePeerToPeer = includePeerToPeer
            track(NWConnection(to: endpoint, using: parameters), epoch: epoch, completion: completion)
        }
    }
    private func track(_ connection: NWConnection, epoch run: UUID, completion: @escaping (Result<NWConnection, Error>) -> Void) {
        guard run == epoch, entries.count < 8 else {
            connection.cancel(); completion(.failure(MotoComError.unavailable("connection admission is closed"))); return
        }
        let id = ObjectIdentifier(connection); let entry = Entry(connection, run, completion)
        entries[id] = entry // pending registration precedes start and every callback
        connection.stateUpdateHandler = { [weak self, weak entry] state in
            guard let self, let entry, self.epoch == run, self.entries[id] === entry else { connection.cancel(); return }
            switch state {
            case .ready:
                guard !entry.completed else { return }
                entry.completed = true; entry.timeout?.cancel(); entry.timeout = nil
                entry.completion(.success(connection))
            case .failed(let error): self.remove(entry, error: error)
            case .cancelled: self.remove(entry, error: MotoComError.unavailable("TCP cancelled"))
            default: break
            }
        }
        let timeout = DispatchWorkItem { [weak self, weak entry] in
            guard let self, let entry, self.entries[id] === entry, !entry.completed else { return }
            self.remove(entry, error: MotoComError.unavailable("TCP deadline exceeded"))
        }
        entry.timeout = timeout
        queue.asyncAfter(deadline: .now() + .seconds(10), execute: timeout)
        connection.start(queue: queue)
    }
    private func remove(_ entry: Entry, error: Error) {
        let id = ObjectIdentifier(entry.connection)
        guard entries[id] === entry else { return }
        entries.removeValue(forKey: id); entry.timeout?.cancel(); entry.timeout = nil
        entry.connection.cancel()
        if !entry.completed { entry.completed = true; entry.completion(.failure(error)) }
    }
    func stop() {
        owned {
            epoch = UUID() // synchronous retirement before SDK calls
            let oldListener = listener; let oldBrowser = browser; let old = Array(entries.values)
            listener = nil; browser = nil; entries.removeAll()
            oldListener?.cancel(); oldBrowser?.cancel()
            for entry in old {
                entry.timeout?.cancel(); entry.connection.cancel()
                if !entry.completed {
                    entry.completed = true; entry.completion(.failure(MotoComError.unavailable("transport stopped")))
                }
            }
        }
    }
}
#endif
