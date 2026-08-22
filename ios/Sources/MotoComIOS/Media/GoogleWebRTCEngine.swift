import Foundation

// The package intentionally does not pin an unreviewed WebRTC binary. Add the
// approved iOS WebRTC module to the Xcode App target; this adapter is compiled
// only when that module is present.
#if canImport(WebRTC)
import WebRTC

public final class GoogleWebRTCEngine: NSObject, WebRTCEngine {
    public var onStateChanged: ((WebRTCMediaState) -> Void)?
    public var onLocalOffer: ((String) -> Void)?
    public var onLocalAnswer: ((String) -> Void)?
    public var onLocalCandidate: ((String) -> Void)?
    public var onRemoteAudioTrack: (() -> Void)?
    public var onRemoteAudioFrame: (() -> Void)?

    private let factory: RTCPeerConnectionFactory
    private var peerConnection: RTCPeerConnection?
    private var localAudioTrack: RTCAudioTrack?
    private let remoteCandidateLock = NSLock()
    private var remoteDescriptionSet = false
    private var pendingRemoteCandidates = [RTCIceCandidate]()

    public override init() {
        factory = RTCPeerConnectionFactory()
        super.init()
    }

    public func start(configuration: WebRTCSessionConfiguration, offerer: Bool) throws {
        guard configuration.audioCodec.lowercased() == "opus" else {
            throw MotoComError.invalidField("audioCodec must be opus")
        }
        guard configuration.hostOnlyICE, configuration.iceServers.isEmpty else {
            throw MotoComError.invalidField("iOS v1 requires host-only ICE without STUN/TURN")
        }

        let rtcConfiguration = RTCConfiguration()
        rtcConfiguration.iceServers = []
        rtcConfiguration.iceTransportPolicy = .all
        rtcConfiguration.sdpSemantics = .unifiedPlan
        let constraints = RTCMediaConstraints(
            mandatoryConstraints: ["OfferToReceiveAudio": "true"],
            optionalConstraints: nil
        )
        guard let connection = factory.peerConnection(
            with: rtcConfiguration,
            constraints: constraints,
            delegate: self
        ) else {
            throw MotoComError.unavailable("failed to create RTCPeerConnection")
        }
        peerConnection = connection
        remoteCandidateLock.lock()
        remoteDescriptionSet = false
        pendingRemoteCandidates.removeAll()
        remoteCandidateLock.unlock()

        let source = factory.audioSource(with: RTCMediaConstraints(
            mandatoryConstraints: nil,
            optionalConstraints: nil
        ))
        let track = factory.audioTrack(withTrackId: "motocom-audio", source: source)
        localAudioTrack = track
        connection.add(track, streamIds: ["motocom-stream"])
        onStateChanged?(.negotiating)

        if offerer {
            connection.offer(for: constraints) { [weak self] description, error in
                if let error { self?.fail(error); return }
            guard let self, let description else {
                self?.fail(MotoComError.unavailable("WebRTC offer is empty"))
                return
            }
            let localDescription = RTCSessionDescription(
                type: .offer,
                sdp: WebRTCSignalingCodec.forceOpus32k(description.sdp)
            )
            connection.setLocalDescription(localDescription) { [weak self] error in
                if let error { self?.fail(error); return }
                guard let self else { return }
                do {
                    let payload = try WebRTCSignalingCodec.encodeSessionDescription(
                        type: "offer",
                        sdp: localDescription.sdp
                    )
                        self.onLocalOffer?(payload)
                    } catch {
                        self.fail(error)
                    }
                }
            }
        }
    }

    public func setRemoteOffer(_ sdpJSON: String) throws {
        guard let connection = peerConnection else { throw MotoComError.unavailable("WebRTC session is not started") }
        let sdp = try WebRTCSignalingCodec.decodeSessionDescription(sdpJSON, expectedType: "offer")
        let description = RTCSessionDescription(type: .offer, sdp: sdp)
        connection.setRemoteDescription(description) { [weak self] error in
            if let error { self?.fail(error); return }
            self?.flushRemoteCandidates(on: connection)
            let constraints = RTCMediaConstraints(
                mandatoryConstraints: ["OfferToReceiveAudio": "true"],
                optionalConstraints: nil
            )
            connection.answer(for: constraints) { [weak self] answer, error in
                if let error { self?.fail(error); return }
                guard let self, let answer else {
                    self?.fail(MotoComError.unavailable("WebRTC answer is empty"))
                    return
                }
                let localDescription = RTCSessionDescription(
                    type: .answer,
                    sdp: WebRTCSignalingCodec.forceOpus32k(answer.sdp)
                )
                connection.setLocalDescription(localDescription) { [weak self] error in
                    if let error { self?.fail(error); return }
                    guard let self else { return }
                    do {
                        let payload = try WebRTCSignalingCodec.encodeSessionDescription(
                            type: "answer",
                            sdp: localDescription.sdp
                        )
                        self.onLocalAnswer?(payload)
                    } catch {
                        self.fail(error)
                    }
                }
            }
        }
    }

    public func setRemoteAnswer(_ sdpJSON: String) throws {
        guard let connection = peerConnection else { throw MotoComError.unavailable("WebRTC session is not started") }
        let sdp = try WebRTCSignalingCodec.decodeSessionDescription(sdpJSON, expectedType: "answer")
        connection.setRemoteDescription(
            RTCSessionDescription(type: .answer, sdp: sdp)
        ) { [weak self] error in
            if let error {
                self?.fail(error)
            } else {
                self?.flushRemoteCandidates(on: connection)
            }
        }
    }

    public func addRemoteCandidate(_ candidateJSON: String) throws {
        guard let connection = peerConnection else { throw MotoComError.unavailable("WebRTC session is not started") }
        let candidate = try WebRTCSignalingCodec.decodeCandidate(candidateJSON)
        let iceCandidate = RTCIceCandidate(
            sdp: candidate.candidate,
            sdpMLineIndex: candidate.sdpMLineIndex,
            sdpMid: candidate.sdpMid
        )
        remoteCandidateLock.lock()
        guard remoteDescriptionSet else {
            pendingRemoteCandidates.append(iceCandidate)
            remoteCandidateLock.unlock()
            return
        }
        remoteCandidateLock.unlock()
        connection.add(iceCandidate)
    }

    public func setAudioEnabled(_ enabled: Bool) {
        localAudioTrack?.isEnabled = enabled
    }

    public func close() {
        peerConnection?.close()
        peerConnection = nil
        localAudioTrack = nil
        remoteCandidateLock.lock()
        remoteDescriptionSet = false
        pendingRemoteCandidates.removeAll()
        remoteCandidateLock.unlock()
        onStateChanged?(.closed)
    }

    // A production audio sink must call this only after the first decoded
    // remote audio buffer is observed. RTCAudioTrack arrival alone is not
    // sufficient for MotoCom's AUDIO_READY contract.
    public func markRemoteAudioFrameDecoded() {
        onRemoteAudioFrame?()
    }

    private func fail(_ error: Error) {
        onStateChanged?(.failed)
        _ = error
    }

    private func flushRemoteCandidates(on connection: RTCPeerConnection) {
        remoteCandidateLock.lock()
        remoteDescriptionSet = true
        let pending = pendingRemoteCandidates
        pendingRemoteCandidates.removeAll()
        remoteCandidateLock.unlock()
        pending.forEach { connection.add($0) }
    }
}

extension GoogleWebRTCEngine: RTCPeerConnectionDelegate {
    public func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}

    public func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {
        if !stream.audioTracks.isEmpty { onRemoteAudioTrack?() }
    }

    public func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}

    public func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}

    public func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        switch newState {
        case .connected, .completed:
            onStateChanged?(.connected)
        case .failed:
            onStateChanged?(.failed)
        case .closed:
            onStateChanged?(.closed)
        default:
            onStateChanged?(.negotiating)
        }
    }

    public func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}

    public func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        do {
            let string = try WebRTCSignalingCodec.encodeCandidate(
                sdpMid: candidate.sdpMid,
                sdpMLineIndex: candidate.sdpMLineIndex,
                candidate: candidate.sdp
            )
            onLocalCandidate?(string)
        } catch {
            fail(error)
        }
    }

    public func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}

    public func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}

    // Unified Plan reports a remote track through the receiver callback instead
    // of the legacy media-stream callback. Keep both paths so the adapter works
    // with SDKs that expose either delegate surface.
    public func peerConnection(
        _ peerConnection: RTCPeerConnection,
        didAdd rtpReceiver: RTCRtpReceiver,
        streams mediaStreams: [RTCMediaStream]
    ) {
        if rtpReceiver.track is RTCAudioTrack {
            onRemoteAudioTrack?()
        }
    }
}
#endif
