import Foundation

public enum SignalingSessionPhase: String, Sendable {
    case idle
    case awaitingRequesterHello
    case requesterHelloSent
    case responderHelloSent
    case readyToSendConnectRequest
    case awaitingConnectRequest
    case awaitingConfirmation
    case glarePending
    case accepted
    case awaitingAnswer
    case readyToSendAnswer
    case mediaNegotiating
    case connected
    case closed
}

@MainActor
public final class SignalingSessionController {
    public private(set) var phase: SignalingSessionPhase = .idle
    public private(set) var remoteNickname = ""
    public private(set) var remoteDeviceName = ""
    public private(set) var remoteCapabilities = Set<String>()
    public private(set) var remoteSessionID: String?

    public var onOffer: (@MainActor (String) -> Void)?
    public var onAnswer: (@MainActor (String) -> Void)?
    public var onCandidate: (@MainActor (String) -> Void)?
    public var onIncomingRequest: (@MainActor () -> Void)?
    public var onAccepted: (@MainActor () -> Void)?
    public var onReadyToRequest: (@MainActor () -> Void)?
    public var onHello: (@MainActor (String, String, String, Set<String>) -> Void)?
    public var onError: (@MainActor (Error) -> Void)?
    public var onTerminated: (@MainActor (Error?) -> Void)?
    public var onClaimHello: (@MainActor (SignalingSessionController) -> Bool)?
    public private(set) var isTerminated = false

    private let channel: NWControlChannel
    private let localIdentity: StableIdentity
    public private(set) var remoteDeviceID: String?
    public private(set) var attemptID: String?
    private var machine = SignalingPhaseMachine(initialRequestRole: nil)
    private var localCapabilities = Set<String>()
    private let scheduler: SessionDeadlineScheduling
    private let helloDeadline: TimeInterval
    private var helloTimeout: SessionDeadlineToken?
    private var confirmationTimeout: SessionDeadlineToken?
    private var confirmationDeadline: TimeInterval?
    private var helloVerified = false
    private var nextEvent: UInt64 = 1
    private var queuedEvents = [UInt64: ControlChannelEvent]()
    private var pendingLocalCandidates = [Data]()
    private let expectedRemoteSessionID: String?
    public var expectedRuntimeID: String? { expectedRemoteSessionID }
    public var absoluteHelloDeadline: TimeInterval { helloDeadline }

    public init(
        channel: NWControlChannel,
        localIdentity: StableIdentity,
        remoteDeviceID: String? = nil,
        attemptID: String? = nil,
        expectedRemoteSessionID: String? = nil,
        autoStart: Bool = true,
        scheduler: SessionDeadlineScheduling? = nil,
        helloDeadline: TimeInterval? = nil
    ) throws {
        if let remoteDeviceID {
            try UUIDValidator.requireCanonical(remoteDeviceID, field: "remoteDeviceId")
        }
        if let attemptID {
            try UUIDValidator.requireCanonical(attemptID, field: "attemptId")
        }
        if let expectedRemoteSessionID {
            try UUIDValidator.requireCanonical(expectedRemoteSessionID, field: "expectedRemoteSessionId")
        }
        self.channel = channel
        self.localIdentity = localIdentity
        self.remoteDeviceID = remoteDeviceID
        self.attemptID = attemptID
        self.expectedRemoteSessionID = expectedRemoteSessionID
        let resolvedScheduler = scheduler ?? SessionDeadlineScheduler()
        self.scheduler = resolvedScheduler
        self.helloDeadline = helloDeadline ?? resolvedScheduler.now + 10
        channel.onEvent = { [weak self] sequence, event in
            Task { @MainActor [weak self] in
                self?.receiveEvent(sequence, event)
            }
        }
        helloTimeout = resolvedScheduler.schedule(at: self.helloDeadline) { [weak self] in
            guard let self, !self.helloVerified else { return }
            self.fail(MotoComError.unavailable("TCP/HELLO deadline exceeded"))
        }
        if autoStart { channel.start() }
    }

    public func start() {
        guard !isTerminated else { return }
        channel.start()
    }

    public func startAsRequester(capabilities: Set<String>) {
        guard !isTerminated else { return }
        guard remoteDeviceID != nil, attemptID != nil else {
            fail(MotoComError.invalidField("requester requires remoteDeviceId and attemptId"))
            return
        }
        localCapabilities = capabilities
        machine = SignalingPhaseMachine(initialRequestRole: .requester)
        send(.hello(
            requestRole: .requester,
            nickname: localIdentity.nickname,
            deviceName: localIdentity.deviceName,
            capabilities: capabilities
        ))
    }

    public func startAsResponder(capabilities: Set<String>) {
        guard !isTerminated else { return }
        localCapabilities = capabilities
        machine = SignalingPhaseMachine(initialRequestRole: nil)
        syncPhase()
    }

    public func sendConnectRequest(trigger: RequestTrigger = .user) {
        if send(.connectRequest(trigger: trigger, preferredTransportHint: .lan)) { scheduleConfirmationTimeout() }
    }

    @discardableResult
    public func accept() -> Bool {
        guard !isTerminated, machine.phase == .awaitingLocalDecision,
              confirmationDeadline.map({ scheduler.now < $0 }) ?? true else { return false }
        confirmationTimeout?.cancel(); confirmationTimeout = nil
        return send(.connectAccept(nickname: localIdentity.nickname, deviceName: localIdentity.deviceName))
    }

    public func reject() {
        terminate(nil, finalMessage: .connectReject(reason: .userRejected, retryable: false))
    }

    public func sendOffer(_ sdpJSON: String) {
        send(.offer(sdpJSON: sdpJSON))
        flushPendingLocalCandidates()
    }

    public func sendAnswer(_ sdpJSON: String) {
        send(.answer(sdpJSON: sdpJSON))
        flushPendingLocalCandidates()
    }

    public func sendCandidate(_ candidateJSON: Data) {
        guard !isTerminated else { return }
        if machine.phase == .accepted {
            guard pendingLocalCandidates.count < 64 else {
                fail(MotoComError.invalidFrame("too many pending candidates")); return
            }
            pendingLocalCandidates.append(candidateJSON)
            return
        }
        send(.candidate(json: candidateJSON))
    }

    public func close(reason: String = "USER_CANCELED") {
        terminate(nil, finalMessage: .disconnect(reason: reason))
    }

    @discardableResult
    public func markMediaConnected() -> Bool {
        guard !isTerminated else { return false }
        if machine.phase == .connected { return true }
        do {
            try machine.markConnected()
            syncPhase()
            return true
        } catch {
            fail(error)
            return false
        }
    }

    private func receiveEvent(_ sequence: UInt64, _ event: ControlChannelEvent) {
        guard !isTerminated, sequence >= nextEvent else { return }
        queuedEvents[sequence] = event
        while !isTerminated, let pending = queuedEvents.removeValue(forKey: nextEvent) {
            nextEvent += 1
            switch pending {
            case .envelopes(let envelopes):
                for envelope in envelopes where !isTerminated { receive(envelope) }
            case .closed(let error): terminate(error)
            }
        }
    }

    private func receive(_ envelope: SignalingEnvelope) {
        guard !isTerminated else { return }
        if !helloVerified, scheduler.now >= helloDeadline {
            fail(MotoComError.unavailable("TCP/HELLO deadline exceeded")); return
        }
        if let deadline = confirmationDeadline,
           (machine.phase == .awaitingRemoteDecision || machine.phase == .awaitingLocalDecision),
           scheduler.now >= deadline {
            fail(MotoComError.unavailable("confirmation deadline exceeded")); return
        }
        guard envelope.targetDeviceID == localIdentity.deviceID else {
            fail(MotoComError.invalidField("signaling target/attempt mismatch"))
            return
        }
        if remoteDeviceID == nil || attemptID == nil {
            guard case .hello(let role, _, _, _) = envelope.message, role == .requester else {
                fail(MotoComError.invalidField("first incoming signaling frame must be requester HELLO"))
                return
            }
            do {
                try UUIDValidator.requireCanonical(envelope.sourceDeviceID, field: "remoteDeviceId")
                try UUIDValidator.requireCanonical(envelope.attemptID, field: "attemptId")
            } catch {
                fail(error)
                return
            }
            if let expectedDeviceID = remoteDeviceID,
               expectedDeviceID != envelope.sourceDeviceID {
                fail(MotoComError.invalidField("signaling source device mismatch"))
                return
            }
            if let expectedAttemptID = attemptID,
               expectedAttemptID != envelope.attemptID {
                fail(MotoComError.invalidField("signaling attempt mismatch"))
                return
            }
            remoteDeviceID = envelope.sourceDeviceID
            attemptID = envelope.attemptID
        }
        let isGlareRequesterHello: Bool = {
            guard machine.phase == .awaitingResponderHello,
                  case .hello(let role, _, _, _) = envelope.message else { return false }
            return role == .requester
        }()
        guard envelope.sourceDeviceID == remoteDeviceID,
              isGlareRequesterHello || envelope.attemptID == attemptID else {
            fail(MotoComError.invalidField("signaling source/attempt mismatch"))
            return
        }
        if let remoteSessionID {
            guard remoteSessionID == envelope.sourceSessionID else {
                fail(MotoComError.invalidField("signaling source session mismatch"))
                return
            }
        } else {
            guard case .hello = envelope.message else {
                fail(MotoComError.invalidField("first signaling frame must be HELLO"))
                return
            }
            remoteSessionID = envelope.sourceSessionID
        }
        if let expectedRemoteSessionID,
           expectedRemoteSessionID != envelope.sourceSessionID {
            fail(MotoComError.invalidField("signaling source session mismatch"))
            return
        }
        do {
            try machine.onFrame(direction: .inbound, message: envelope.message)
        } catch {
            fail(error)
            return
        }
        switch envelope.message {
        case .hello(let role, let nickname, let deviceName, let capabilities):
            guard onClaimHello?(self) ?? true else {
                terminate(MotoComError.unavailable("another session owns media"), finalMessage: .busy(reason: "ALREADY_CONNECTED", retryAfterMilliseconds: nil))
                return
            }
            helloVerified = true
            helloTimeout?.cancel(); helloTimeout = nil
            remoteNickname = nickname ?? ""
            remoteDeviceName = deviceName ?? ""
            remoteCapabilities = capabilities
            onHello?(
                remoteDeviceID ?? envelope.sourceDeviceID,
                nickname ?? "",
                deviceName ?? "",
                capabilities
            )
            if role == .requester, machine.phase == .glarePending {
                resolveGlare(remoteAttemptID: envelope.attemptID)
            } else if role == .requester, machine.phase == .readyToSendResponderHello {
                send(.hello(
                    requestRole: .responder,
                    nickname: localIdentity.nickname,
                    deviceName: localIdentity.deviceName,
                    capabilities: localCapabilities
                ))
            }
            if role == .responder, machine.phase == .readyToSendConnectRequest {
                onReadyToRequest?()
            }
        case .connectRequest:
            syncPhase()
            onIncomingRequest?()
            if machine.phase == .awaitingLocalDecision {
                scheduleConfirmationTimeout()
            }
        case .connectAccept:
            confirmationTimeout?.cancel(); confirmationTimeout = nil
            syncPhase()
            onAccepted?()
        case .connectReject, .busy, .disconnect:
            terminate(nil)
            return
        case .offer(let sdpJSON):
            syncPhase()
            onOffer?(sdpJSON)
        case .answer(let sdpJSON):
            syncPhase()
            onAnswer?(sdpJSON)
        case .candidate(let json):
            onCandidate?(String(decoding: json, as: UTF8.self))
        }
        syncPhase()
    }

    private func resolveGlare(remoteAttemptID: String) {
        guard let localAttemptID = attemptID,
              let remoteDeviceID,
              let remoteSessionID else {
            fail(MotoComError.invalidField("glare identity is incomplete"))
            return
        }
        let localKey = [
            localAttemptID,
            localIdentity.deviceID,
            localIdentity.sessionID,
            remoteDeviceID
        ]
        let remoteKey = [
            remoteAttemptID,
            remoteDeviceID,
            remoteSessionID,
            localIdentity.deviceID
        ]
        let localWins = wireKeyIsLess(localKey, remoteKey)
        do {
            try machine.resolveGlare(localRequestWins: localWins)
        } catch {
            fail(error)
            return
        }
        guard !localWins else { return }
        attemptID = remoteAttemptID
        send(.hello(
            requestRole: .responder,
            nickname: localIdentity.nickname,
            deviceName: localIdentity.deviceName,
            capabilities: localCapabilities
        ))
    }

    private func wireKeyIsLess(_ left: [String], _ right: [String]) -> Bool {
        for (lhs, rhs) in zip(left, right) where lhs != rhs {
            return lhs < rhs
        }
        return false
    }

    private func flushPendingLocalCandidates() {
        guard !pendingLocalCandidates.isEmpty else { return }
        let pending = pendingLocalCandidates
        pendingLocalCandidates.removeAll()
        pending.forEach { send(.candidate(json: $0)) }
    }

    @discardableResult
    private func send(_ message: SignalingMessage) -> Bool {
        guard !isTerminated else { return false }
        do {
            guard let remoteDeviceID, let attemptID else {
                throw MotoComError.invalidField("remoteDeviceId/attemptId not established")
            }
            try machine.onFrame(direction: .outbound, message: message)
            let envelope = try SignalingEnvelope(
                attemptID: attemptID,
                sourceDeviceID: localIdentity.deviceID,
                targetDeviceID: remoteDeviceID,
                sourceSessionID: localIdentity.sessionID,
                message: message
            )
            channel.send(envelope: envelope) { [weak self] error in
                if let error {
                    Task { @MainActor [weak self] in self?.fail(error) }
                }
            }
            syncPhase()
            return true
        } catch {
            fail(error)
            return false
        }
    }

    private func fail(_ error: Error) {
        terminate(error)
    }

    private func terminate(_ error: Error?, finalMessage: SignalingMessage? = nil) {
        guard !isTerminated else { return }
        isTerminated = true
        helloTimeout?.cancel(); helloTimeout = nil
        confirmationTimeout?.cancel(); confirmationTimeout = nil
        pendingLocalCandidates.removeAll()
        queuedEvents.removeAll()
        machine.close()
        syncPhase()
        if let message = finalMessage, let remoteDeviceID, let attemptID,
           let envelope = try? SignalingEnvelope(attemptID: attemptID,
               sourceDeviceID: localIdentity.deviceID, targetDeviceID: remoteDeviceID,
               sourceSessionID: localIdentity.sessionID, message: message) {
            channel.sendFinal(envelope: envelope)
        } else { channel.close() }
        if let error { onError?(error) }
        onTerminated?(error)
    }

    private func syncPhase() {
        phase = switch machine.phase {
        case .readyToSendRequesterHello: .idle
        case .awaitingRequesterHello: .awaitingRequesterHello
        case .awaitingResponderHello: .requesterHelloSent
        case .readyToSendResponderHello: .responderHelloSent
        case .readyToSendConnectRequest: .readyToSendConnectRequest
        case .awaitingConnectRequest: .awaitingConnectRequest
        case .awaitingRemoteDecision, .awaitingLocalDecision: .awaitingConfirmation
        case .glarePending: .glarePending
        case .accepted: .accepted
        case .awaitingAnswer: .awaitingAnswer
        case .readyToSendAnswer: .readyToSendAnswer
        case .mediaNegotiating: .mediaNegotiating
        case .connected: .connected
        case .closed: .closed
        }
    }

    private func scheduleConfirmationTimeout() {
        confirmationTimeout?.cancel()
        let deadline = scheduler.now + 15
        confirmationDeadline = deadline
        confirmationTimeout = scheduler.schedule(at: deadline) { [weak self] in
            guard let self, !self.isTerminated,
                  self.machine.phase == .awaitingLocalDecision || self.machine.phase == .awaitingRemoteDecision else { return }
            self.terminate(MotoComError.unavailable("confirmation deadline exceeded"),
                finalMessage: self.machine.phase == .awaitingLocalDecision
                    ? .connectReject(reason: .timeout, retryable: false) : .disconnect(reason: "CONFIRMATION_TIMEOUT"))
        }
    }
}
