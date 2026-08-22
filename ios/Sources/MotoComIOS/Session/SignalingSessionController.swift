import Foundation

#if canImport(Network)
import Network

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

    private let channel: NWControlChannel
    private let localIdentity: StableIdentity
    public private(set) var remoteDeviceID: String?
    public private(set) var attemptID: String?
    private var machine = SignalingPhaseMachine(initialRequestRole: nil)
    private var localCapabilities = Set<String>()
    private var confirmationTimeoutTask: Task<Void, Never>?
    private var pendingLocalCandidates = [Data]()
    private let expectedRemoteSessionID: String?

    public init(
        channel: NWControlChannel,
        localIdentity: StableIdentity,
        remoteDeviceID: String? = nil,
        attemptID: String? = nil,
        expectedRemoteSessionID: String? = nil,
        autoStart: Bool = true
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
        channel.onEnvelope = { [weak self] envelope in
            Task { @MainActor [weak self] in
                self?.receive(envelope)
            }
        }
        channel.onClosed = { [weak self] error in
            Task { @MainActor [weak self] in
                self?.confirmationTimeoutTask?.cancel()
                self?.phase = .closed
                if let error { self?.onError?(error) }
            }
        }
        if autoStart { channel.start() }
    }

    public func start() {
        channel.start()
    }

    public func startAsRequester(capabilities: Set<String>) {
        guard remoteDeviceID != nil, attemptID != nil else {
            onError?(MotoComError.invalidField("requester requires remoteDeviceId and attemptId"))
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
        localCapabilities = capabilities
        machine = SignalingPhaseMachine(initialRequestRole: nil)
        syncPhase()
    }

    public func sendConnectRequest(trigger: RequestTrigger = .user) {
        send(.connectRequest(trigger: trigger, preferredTransportHint: .lan))
    }

    public func accept() {
        confirmationTimeoutTask?.cancel()
        send(.connectAccept(nickname: localIdentity.nickname, deviceName: localIdentity.deviceName))
    }

    public func reject() {
        confirmationTimeoutTask?.cancel()
        pendingLocalCandidates.removeAll()
        send(.connectReject(reason: .userRejected, retryable: false))
        channel.close()
        machine.close()
        syncPhase()
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
        if machine.phase == .accepted {
            pendingLocalCandidates.append(candidateJSON)
            return
        }
        send(.candidate(json: candidateJSON))
    }

    public func close(reason: String = "USER_CANCELED") {
        confirmationTimeoutTask?.cancel()
        pendingLocalCandidates.removeAll()
        if machine.phase != .closed { send(.disconnect(reason: reason)) }
        channel.close()
        machine.close()
        syncPhase()
    }

    public func markMediaConnected() {
        do {
            try machine.markConnected()
            syncPhase()
        } catch {
            onError?(error)
        }
    }

    private func receive(_ envelope: SignalingEnvelope) {
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
            confirmationTimeoutTask?.cancel()
            syncPhase()
            onAccepted?()
        case .connectReject, .busy, .disconnect:
            confirmationTimeoutTask?.cancel()
            syncPhase()
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

    private func send(_ message: SignalingMessage) {
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
                    Task { @MainActor [weak self] in self?.onError?(error) }
                }
            }
            syncPhase()
        } catch {
            onError?(error)
        }
    }

    private func fail(_ error: Error) {
        confirmationTimeoutTask?.cancel()
        pendingLocalCandidates.removeAll()
        onError?(error)
        machine.close()
        channel.close()
        syncPhase()
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
        confirmationTimeoutTask?.cancel()
        confirmationTimeoutTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 15_000_000_000)
            guard !Task.isCancelled else { return }
            guard let self, self.machine.phase == .awaitingLocalDecision else { return }
            self.send(.connectReject(reason: .timeout, retryable: false))
            self.channel.close()
            self.machine.close()
            self.syncPhase()
        }
    }
}
#endif
