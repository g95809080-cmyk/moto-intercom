import Foundation

public enum SignalingFrameDirection: Sendable {
    case inbound
    case outbound
}

public enum SignalingWirePhase: String, Sendable {
    case readyToSendRequesterHello
    case awaitingRequesterHello
    case awaitingResponderHello
    case readyToSendResponderHello
    case readyToSendConnectRequest
    case awaitingConnectRequest
    case awaitingRemoteDecision
    case awaitingLocalDecision
    case glarePending
    case accepted
    case awaitingAnswer
    case readyToSendAnswer
    case mediaNegotiating
    case connected
    case closed
}

/// Mirrors Android's SignalingPhaseMachine so a valid JSON frame is not enough
/// to advance an iOS session from an impossible wire state.
public struct SignalingPhaseMachine: Sendable {
    public private(set) var requestRole: RequestRole?
    public private(set) var phase: SignalingWirePhase

    public init(initialRequestRole: RequestRole?) {
        self.requestRole = initialRequestRole
        self.phase = initialRequestRole == .requester
            ? .readyToSendRequesterHello
            : .awaitingRequesterHello
    }

    public mutating func onFrame(
        direction: SignalingFrameDirection,
        message: SignalingMessage
    ) throws {
        guard let next = nextPhase(direction: direction, message: message) else {
            throw MotoComError.invalidFrame(
                "unexpected \(message.type.rawValue) direction=\(direction) phase=\(phase.rawValue)"
            )
        }
        if phase == .awaitingRequesterHello,
           direction == .inbound,
           case .hello(let role, _, _, _) = message,
           role == .requester {
            requestRole = .responder
        }
        phase = next
    }

    public mutating func resolveGlare(localRequestWins: Bool) throws {
        guard phase == .glarePending, requestRole == .requester else {
            throw MotoComError.invalidFrame("glare resolution is not pending")
        }
        if localRequestWins {
            phase = .awaitingResponderHello
        } else {
            requestRole = .responder
            phase = .readyToSendResponderHello
        }
    }

    public mutating func markConnected() throws {
        guard phase == .mediaNegotiating else {
            throw MotoComError.invalidFrame("media cannot connect from phase=\(phase.rawValue)")
        }
        phase = .connected
    }

    public mutating func close() {
        phase = .closed
    }

    private func nextPhase(
        direction: SignalingFrameDirection,
        message: SignalingMessage
    ) -> SignalingWirePhase? {
        switch phase {
        case .readyToSendRequesterHello:
            return direction == .outbound && isHello(message, role: .requester)
                ? .awaitingResponderHello : nil
        case .awaitingRequesterHello:
            return direction == .inbound && isHello(message, role: .requester)
                ? .readyToSendResponderHello : nil
        case .awaitingResponderHello:
            if direction == .inbound && isHello(message, role: .responder) {
                return .readyToSendConnectRequest
            }
            if direction == .inbound && isHello(message, role: .requester) {
                return .glarePending
            }
            return nil
        case .readyToSendResponderHello:
            return direction == .outbound && isHello(message, role: .responder)
                ? .awaitingConnectRequest : nil
        case .readyToSendConnectRequest:
            return direction == .outbound && isConnectRequest(message)
                ? .awaitingRemoteDecision : nil
        case .awaitingConnectRequest:
            return direction == .inbound && isConnectRequest(message)
                ? .awaitingLocalDecision : nil
        case .awaitingRemoteDecision:
            if direction == .inbound, case .connectAccept = message { return .accepted }
            if direction == .inbound, case .connectReject = message { return .closed }
            if direction == .inbound, case .busy = message { return .closed }
            return nil
        case .awaitingLocalDecision:
            if direction == .outbound, case .connectAccept = message { return .accepted }
            if direction == .outbound, case .connectReject = message { return .closed }
            if direction == .outbound, case .busy = message { return .closed }
            return nil
        case .glarePending:
            return nil
        case .accepted:
            if case .disconnect = message { return .closed }
            if requestRole == .requester,
               direction == .outbound,
               case .offer = message { return .awaitingAnswer }
            if requestRole == .responder,
               direction == .inbound,
               case .offer = message { return .readyToSendAnswer }
            return nil
        case .awaitingAnswer:
            if case .disconnect = message { return .closed }
            if case .candidate = message { return phase }
            return direction == .inbound && isAnswer(message) ? .mediaNegotiating : nil
        case .readyToSendAnswer:
            if case .disconnect = message { return .closed }
            if case .candidate = message { return phase }
            return direction == .outbound && isAnswer(message) ? .mediaNegotiating : nil
        case .mediaNegotiating, .connected:
            if case .disconnect = message { return .closed }
            if case .candidate = message { return phase }
            return nil
        case .closed:
            return nil
        }
    }

    private func isHello(_ message: SignalingMessage, role: RequestRole) -> Bool {
        guard case .hello(let requestRole, _, _, _) = message else { return false }
        return requestRole == role
    }

    private func isConnectRequest(_ message: SignalingMessage) -> Bool {
        if case .connectRequest = message { return true }
        return false
    }

    private func isAnswer(_ message: SignalingMessage) -> Bool {
        if case .answer = message { return true }
        return false
    }
}
