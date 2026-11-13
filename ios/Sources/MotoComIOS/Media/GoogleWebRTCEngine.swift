import Foundation

// Supply the approved WebRTC module from the Xcode application target.
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
    private let mediaQueue = DispatchQueue(label: "com.motocom.webrtc-media")
    private var peerConnection: RTCPeerConnection?
    private var localAudioTrack: RTCAudioTrack?
    private var remoteDescriptionSet = false
    private var pendingRemoteCandidates = [RTCIceCandidate]()

    public override init() {
        factory = RTCPeerConnectionFactory()
        super.init()
    }

    public func start(configuration: WebRTCSessionConfiguration, offerer: Bool) throws {
        try mediaQueue.sync {
            guard peerConnection == nil else { throw MotoComError.invalidField("media session already active") }
            try configuration.validate()
            let rtcConfiguration = RTCConfiguration()
            rtcConfiguration.iceServers = configuration.iceServers.map {
                RTCIceServer(urlStrings: $0.urls, username: $0.username, credential: $0.credential)
            }
            rtcConfiguration.iceTransportPolicy = configuration.networkMode == .relay ? .relay : .all
            rtcConfiguration.sdpSemantics = .unifiedPlan
            guard let connection = factory.peerConnection(with: rtcConfiguration, constraints: constraints(), delegate: self) else {
                throw MotoComError.unavailable("failed to create RTCPeerConnection")
            }
            peerConnection = connection
            remoteDescriptionSet = false
            pendingRemoteCandidates.removeAll()
            let source = factory.audioSource(with: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil))
            let track = factory.audioTrack(withTrackId: "motocom-audio", source: source)
            localAudioTrack = track
            connection.add(track, streamIds: ["motocom-stream"])
            onStateChanged?(.negotiating)
            if offerer {
                connection.offer(for: constraints()) { [weak self, weak connection] description, error in
                    guard let connection else { return }
                    self?.withCurrent(connection) { engine in
                        if let error { engine.fail(error); return }
                        guard let description else { engine.fail(MotoComError.unavailable("empty offer")); return }
                        engine.setLocal(description, type: .offer, on: connection)
                    }
                }
            }
        }
    }

    public func setRemoteOffer(_ sdpJSON: String) throws {
        let sdp = try WebRTCSignalingCodec.decodeSessionDescription(sdpJSON, expectedType: "offer")
        try mediaQueue.sync {
            let connection = try currentConnection()
            connection.setRemoteDescription(RTCSessionDescription(type: .offer, sdp: sdp)) { [weak self, weak connection] error in
                guard let connection else { return }
                self?.withCurrent(connection) { engine in
                    if let error { engine.fail(error); return }
                    engine.flushRemoteCandidates(on: connection)
                    connection.answer(for: engine.constraints()) { [weak self, weak connection] answer, error in
                        guard let connection else { return }
                        self?.withCurrent(connection) { engine in
                            if let error { engine.fail(error); return }
                            guard let answer else { engine.fail(MotoComError.unavailable("empty answer")); return }
                            engine.setLocal(answer, type: .answer, on: connection)
                        }
                    }
                }
            }
        }
    }

    public func setRemoteAnswer(_ sdpJSON: String) throws {
        let sdp = try WebRTCSignalingCodec.decodeSessionDescription(sdpJSON, expectedType: "answer")
        try mediaQueue.sync {
            let connection = try currentConnection()
            connection.setRemoteDescription(RTCSessionDescription(type: .answer, sdp: sdp)) { [weak self, weak connection] error in
                guard let connection else { return }
                self?.withCurrent(connection) { engine in
                    if let error { engine.fail(error); return }
                    engine.flushRemoteCandidates(on: connection)
                }
            }
        }
    }

    public func addRemoteCandidate(_ candidateJSON: String) throws {
        let candidate = try WebRTCSignalingCodec.decodeCandidate(candidateJSON)
        try mediaQueue.sync {
            let connection = try currentConnection()
            let ice = RTCIceCandidate(sdp: candidate.candidate, sdpMLineIndex: candidate.sdpMLineIndex, sdpMid: candidate.sdpMid)
            if remoteDescriptionSet { connection.add(ice) }
            else {
                guard pendingRemoteCandidates.count < 128 else { throw MotoComError.invalidField("too many pending ICE candidates") }
                pendingRemoteCandidates.append(ice)
            }
        }
    }

    public func setAudioEnabled(_ enabled: Bool) {
        mediaQueue.sync { localAudioTrack?.isEnabled = enabled }
    }

    public func close() {
        mediaQueue.sync {
            let old = peerConnection
            // Retire identity before close can generate delegate callbacks.
            peerConnection = nil
            localAudioTrack = nil
            remoteDescriptionSet = false
            pendingRemoteCandidates.removeAll()
            old?.close()
            onStateChanged?(.closed)
        }
    }

    /// Install this observer in an actual decoded-PCM sink after starting the session.
    /// A callback retained by an old sink cannot mark a replacement session ready.
    public func makeDecodedAudioObserver() -> () -> Void {
        let connection = mediaQueue.sync { peerConnection }
        return { [weak self, weak connection] in
            guard let connection else { return }
            self?.withCurrent(connection) { $0.onRemoteAudioFrame?() }
        }
    }

    private func currentConnection() throws -> RTCPeerConnection {
        guard let peerConnection else { throw MotoComError.unavailable("WebRTC session is not started") }
        return peerConnection
    }

    private func constraints() -> RTCMediaConstraints {
        RTCMediaConstraints(mandatoryConstraints: ["OfferToReceiveAudio": "true"], optionalConstraints: nil)
    }

    private func withCurrent(_ connection: RTCPeerConnection, _ work: @escaping (GoogleWebRTCEngine) -> Void) {
        mediaQueue.async { [weak self, weak connection] in
            guard let self, let connection, self.peerConnection === connection else { return }
            work(self)
        }
    }

    private func setLocal(_ description: RTCSessionDescription, type: RTCSdpType, on connection: RTCPeerConnection) {
        let local = RTCSessionDescription(type: type, sdp: WebRTCSignalingCodec.forceOpus32k(description.sdp))
        connection.setLocalDescription(local) { [weak self, weak connection] error in
            guard let connection else { return }
            self?.withCurrent(connection) { engine in
                if let error { engine.fail(error); return }
                do {
                    let payload = try WebRTCSignalingCodec.encodeSessionDescription(type: type == .offer ? "offer" : "answer", sdp: local.sdp)
                    if type == .offer { engine.onLocalOffer?(payload) } else { engine.onLocalAnswer?(payload) }
                } catch { engine.fail(error) }
            }
        }
    }

    private func fail(_ error: Error) { onStateChanged?(.failed); _ = error }

    private func flushRemoteCandidates(on connection: RTCPeerConnection) {
        remoteDescriptionSet = true
        let pending = pendingRemoteCandidates
        pendingRemoteCandidates.removeAll()
        pending.forEach { connection.add($0) }
    }
}

extension GoogleWebRTCEngine: RTCPeerConnectionDelegate {
    public func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    public func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {
        withCurrent(peerConnection) { if !stream.audioTracks.isEmpty { $0.onRemoteAudioTrack?() } }
    }
    public func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    public func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    public func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        withCurrent(peerConnection) { engine in
            switch newState {
            case .connected, .completed: engine.onStateChanged?(.connected)
            case .failed: engine.onStateChanged?(.failed)
            case .closed: engine.onStateChanged?(.closed)
            default: engine.onStateChanged?(.negotiating)
            }
        }
    }
    public func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}
    public func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        withCurrent(peerConnection) { engine in
            do {
                engine.onLocalCandidate?(try WebRTCSignalingCodec.encodeCandidate(sdpMid: candidate.sdpMid,
                    sdpMLineIndex: candidate.sdpMLineIndex, candidate: candidate.sdp))
            } catch { engine.fail(error) }
        }
    }
    public func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    public func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
    public func peerConnection(_ peerConnection: RTCPeerConnection, didAdd rtpReceiver: RTCRtpReceiver, streams mediaStreams: [RTCMediaStream]) {
        withCurrent(peerConnection) { if rtpReceiver.track is RTCAudioTrack { $0.onRemoteAudioTrack?() } }
    }
}
#endif
