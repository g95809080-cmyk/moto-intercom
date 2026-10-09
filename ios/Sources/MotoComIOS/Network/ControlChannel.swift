import Foundation
#if canImport(Network)
import Network
#endif

/// TCP is started before handoff. Tests drive the same framing and IO path.
public protocol RawControlIO: AnyObject {
    func receive(_ completion: @escaping (Data?, Bool, Error?) -> Void)
    func send(_ data: Data, completion: @escaping (Error?) -> Void)
    func cancel()
}

public enum ControlChannelEvent {
    case envelopes([SignalingEnvelope])
    case closed(Error?)
}

/// @unchecked is limited to queue ownership. Every native callback returns
/// here; decoder, terminal and callback registration never cross that boundary.
public final class NWControlChannel: @unchecked Sendable {
    private let io: RawControlIO
    private let queue = DispatchQueue(label: "com.motocom.control-channel")
    private let queueKey = DispatchSpecificKey<Bool>()
    private var decoder = LengthPrefixedFrameDecoder()
    private let codec = SignalingV2Codec()
    private var started = false
    private var terminal = false
    private var closing = false
    private var sequence: UInt64 = 0
    private var eventHandler: ((UInt64, ControlChannelEvent) -> Void)?
    private var frameHandler: ((Data) -> Void)?
    private var envelopeHandler: ((SignalingEnvelope) -> Void)?
    private var closeHandler: ((Error?) -> Void)?
    private var partialDeadline: DispatchWorkItem?
    private var awaitingDelivery: UInt64?

    public var onEvent: ((UInt64, ControlChannelEvent) -> Void)? {
        get { owned { eventHandler } } set { owned { eventHandler = newValue } }
    }
    public var onFrame: ((Data) -> Void)? {
        get { owned { frameHandler } } set { owned { frameHandler = newValue } }
    }
    public var onEnvelope: ((SignalingEnvelope) -> Void)? {
        get { owned { envelopeHandler } } set { owned { envelopeHandler = newValue } }
    }
    public var onClosed: ((Error?) -> Void)? {
        get { owned { closeHandler } } set { owned { closeHandler = newValue } }
    }
    public init(io: RawControlIO) {
        self.io = io
        queue.setSpecific(key: queueKey, value: true)
    }
    #if canImport(Network)
    public convenience init(connection: NWConnection) { self.init(io: NWRawControlIO(connection)) }
    #endif
    private func owned<T>(_ action: () throws -> T) rethrows -> T {
        if DispatchQueue.getSpecific(key: queueKey) == true { return try action() }
        return try queue.sync(execute: action)
    }
    public func start() {
        owned {
            guard !started, !terminal, !closing else { return }
            started = true
            receiveNext()
        }
    }
    public func send(frame: Data, completion: ((Error?) -> Void)? = nil) {
        queue.async { [self] in
            guard !terminal, !closing else {
                completion?(MotoComError.unavailable("control channel is closed")); return
            }
            do {
                let data = try LengthPrefixedFraming.encode(frame)
                io.send(data) { [weak self] error in
                    self?.queue.async { [weak self] in
                        guard let self, !self.terminal, !self.closing else { return }
                        completion?(error)
                        if let error { self.finish(error) }
                    }
                }
            } catch { completion?(error); finish(error) }
        }
    }
    public func send(envelope: SignalingEnvelope, completion: ((Error?) -> Void)? = nil) {
        do { send(frame: try codec.encode(envelope), completion: completion) }
        catch { owned { completion?(error); finish(error) } }
    }
    /// Revoke reception immediately and retain only this bounded final write.
    public func sendFinal(envelope: SignalingEnvelope) {
        owned {
            guard !terminal, !closing else { return }
            closing = true
            partialDeadline?.cancel(); partialDeadline = nil
            do {
                let data = try LengthPrefixedFraming.encode(codec.encode(envelope))
                io.send(data) { [weak self] error in
                    self?.queue.async { [weak self] in self?.finish(error) }
                }
                queue.asyncAfter(deadline: .now() + .milliseconds(250)) { [weak self] in self?.finish(nil) }
            } catch { finish(error) }
        }
    }
    public func close() { owned { finish(nil) } }
    public func acknowledge(_ deliveredSequence: UInt64) {
        owned {
            guard !terminal, !closing, awaitingDelivery == deliveredSequence else { return }
            awaitingDelivery = nil; receiveNext()
        }
    }
    private func emit(_ event: ControlChannelEvent) {
        sequence += 1
        eventHandler?(sequence, event)
    }
    private func finish(_ error: Error?) {
        guard !terminal else { return }
        terminal = true
        partialDeadline?.cancel(); partialDeadline = nil
        decoder = LengthPrefixedFrameDecoder()
        io.cancel()
        emit(.closed(error)); closeHandler?(error)
    }
    private func receiveNext() {
        guard !terminal, !closing, awaitingDelivery == nil else { return }
        io.receive { [weak self] data, complete, error in
            self?.queue.async { [weak self] in
                guard let self, !self.terminal, !self.closing else { return }
                do {
                    if let data, !data.isEmpty {
                        guard data.count <= LengthPrefixedFraming.maxFrameBytes + 4 else {
                            throw MotoComError.invalidFrame("raw control read exceeds bound")
                        }
                        let frames = try self.decoder.append(data)
                        let envelopes = try frames.map { try self.codec.decode($0) }
                        if !envelopes.isEmpty {
                            self.awaitingDelivery = self.sequence + 1
                            self.emit(.envelopes(envelopes))
                        }
                        for (frame, envelope) in zip(frames, envelopes) {
                            self.frameHandler?(frame); self.envelopeHandler?(envelope)
                        }
                        if self.decoder.bufferedByteCount == 0 {
                            self.partialDeadline?.cancel(); self.partialDeadline = nil
                        } else if self.partialDeadline == nil {
                            let work = DispatchWorkItem { [weak self] in
                                self?.finish(MotoComError.invalidFrame("partial frame deadline exceeded"))
                            }
                            self.partialDeadline = work
                            self.queue.asyncAfter(deadline: .now() + .seconds(5), execute: work)
                        }
                    }
                    if let error { self.finish(error) }
                    else if complete { self.finish(nil) }
                    else { self.receiveNext() }
                } catch { self.finish(error) }
            }
        }
    }
}

#if canImport(Network)
private final class NWRawControlIO: RawControlIO {
    private let connection: NWConnection
    init(_ connection: NWConnection) { self.connection = connection }
    func receive(_ completion: @escaping (Data?, Bool, Error?) -> Void) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: LengthPrefixedFraming.maxFrameBytes + 4) {
            data, _, complete, error in completion(data, complete, error)
        }
    }
    func send(_ data: Data, completion: @escaping (Error?) -> Void) {
        connection.send(content: data, completion: .contentProcessed { completion($0) })
    }
    func cancel() { connection.cancel() }
}
#endif
