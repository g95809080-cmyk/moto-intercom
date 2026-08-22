import Foundation

#if canImport(Network)
import Network

public final class NWControlChannel: @unchecked Sendable {
    public let connection: NWConnection
    public var onFrame: ((Data) -> Void)?
    public var onEnvelope: ((SignalingEnvelope) -> Void)?
    public var onClosed: ((Error?) -> Void)?

    private let queue = DispatchQueue(label: "com.motocom.control-channel")
    private var decoder = LengthPrefixedFrameDecoder()
    private let codec = SignalingV2Codec()
    private var started = false

    public init(connection: NWConnection) {
        self.connection = connection
    }

    public func start() {
        guard !started else { return }
        started = true
        receiveNext()
    }

    public func send(frame: Data, completion: ((Error?) -> Void)? = nil) {
        queue.async { [weak self] in
            guard let self else { return }
            do {
                let framed = try LengthPrefixedFraming.encode(frame)
                self.connection.send(content: framed, completion: .contentProcessed { error in
                    completion?(error)
                })
            } catch {
                completion?(error)
            }
        }
    }

    public func send(envelope: SignalingEnvelope, completion: ((Error?) -> Void)? = nil) {
        do {
            send(frame: try codec.encode(envelope), completion: completion)
        } catch {
            completion?(error)
        }
    }

    public func close() {
        connection.cancel()
    }

    private func receiveNext() {
        connection.receive(
            minimumIncompleteLength: 1,
            maximumLength: LengthPrefixedFraming.maxFrameBytes + 4
        ) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data, !data.isEmpty {
                do {
                    let frames = try self.decoder.append(data)
                    for frame in frames {
                        do {
                            let envelope = try self.codec.decode(frame)
                            self.onFrame?(frame)
                            self.onEnvelope?(envelope)
                        } catch {
                            self.onClosed?(error)
                            self.connection.cancel()
                            return
                        }
                    }
                } catch {
                    self.onClosed?(error)
                    self.connection.cancel()
                    return
                }
            }
            if let error {
                self.onClosed?(error)
            } else if isComplete {
                self.onClosed?(nil)
            } else {
                self.receiveNext()
            }
        }
    }
}
#endif
