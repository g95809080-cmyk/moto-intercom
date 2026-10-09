import Foundation

#if MOTOCOM_REQUIRE_NATIVE_WEBRTC && !canImport(WebRTC)
#error("Native WebRTC validation requires the actual WebRTC module")
#endif

#if canImport(WebRTC)
import WebRTC

/// Every native producer captures a Run and an actual PC. Mutable SDK state
/// and callback registration belong to queue; callbacks never read a new Run.
public final class GoogleWebRTCEngine: NSObject, WebRTCEngine {
    private let queue = DispatchQueue(label: "com.motocom.webrtc")
    private let queueKey = DispatchSpecificKey<Bool>()
    private let factory: RTCPeerConnectionFactory
    private var callbacks = Callbacks()
    private var current: Run?

    private struct Callbacks {
        var state: ((WebRTCMediaState) -> Void)?
        var offer: ((String) -> Void)?
        var answer: ((String) -> Void)?
        var candidate: ((String) -> Void)?
        var track: (() -> Void)?
        var frame: (() -> Void)?
    }
    private final class Run {
        let id = UUID()
        let callbacks: Callbacks
        var connection: RTCPeerConnection?
        var delegate: PeerDelegate?
        var track: RTCAudioTrack?
        var remoteDescriptionSet = false
        var candidates = [RTCIceCandidate]()
        init(_ callbacks: Callbacks) { self.callbacks = callbacks }
    }
    public var onStateChanged: ((WebRTCMediaState) -> Void)? {
        get { owned { callbacks.state } } set { owned { callbacks.state = newValue } }
    }
    public var onLocalOffer: ((String) -> Void)? {
        get { owned { callbacks.offer } } set { owned { callbacks.offer = newValue } }
    }
    public var onLocalAnswer: ((String) -> Void)? {
        get { owned { callbacks.answer } } set { owned { callbacks.answer = newValue } }
    }
    public var onLocalCandidate: ((String) -> Void)? {
        get { owned { callbacks.candidate } } set { owned { callbacks.candidate = newValue } }
    }
    public var onRemoteAudioTrack: (() -> Void)? {
        get { owned { callbacks.track } } set { owned { callbacks.track = newValue } }
    }
    public var onRemoteAudioFrame: (() -> Void)? {
        get { owned { callbacks.frame } } set { owned { callbacks.frame = newValue } }
    }
    public override init() {
        factory = RTCPeerConnectionFactory()
        super.init()
        queue.setSpecific(key: queueKey, value: true)
    }
    private func owned<T>(_ action: () throws -> T) rethrows -> T {
        if DispatchQueue.getSpecific(key: queueKey) == true { return try action() }
        return try queue.sync(execute: action)
    }
    private func requireRun() throws -> Run {
        guard let run = current, run.connection != nil else { throw MotoComError.unavailable("WebRTC is not started") }
        return run
    }
    private func ingress(_ run: Run, _ pc: RTCPeerConnection, _ action: @escaping (Run) -> Void) {
        queue.async { [weak self] in
            guard let self, self.current === run, run.connection === pc else { return }
            action(run)
        }
    }
    public func start(configuration: WebRTCSessionConfiguration, offerer: Bool) throws {
        try owned {
            guard configuration.audioCodec.lowercased() == "opus",
                  configuration.hostOnlyICE, configuration.iceServers.isEmpty else {
                throw MotoComError.invalidField("iOS requires opus and host-only ICE")
            }
            closeCurrent()
            let run = Run(callbacks)
            let relay = PeerDelegate(owner: self, run: run)
            run.delegate = relay
            let config = RTCConfiguration()
            config.iceServers = []; config.iceTransportPolicy = .all; config.sdpSemantics = .unifiedPlan
            let constraints = RTCMediaConstraints(mandatoryConstraints: ["OfferToReceiveAudio": "true"], optionalConstraints: nil)
            guard let pc = factory.peerConnection(with: config, constraints: constraints, delegate: relay) else {
                throw MotoComError.unavailable("failed to create RTCPeerConnection")
            }
            run.connection = pc; current = run
            let source = factory.audioSource(with: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil))
            let track = factory.audioTrack(with: source, trackId: "motocom-audio")
            run.track = track
            pc.add(track, streamIds: ["motocom-stream"])
            run.callbacks.state?(.negotiating)
            if offerer {
                pc.offer(for: constraints) { [weak self] description, error in
                    self?.ingress(run, pc) { [weak self] run in
                        guard let self else { return }
                        self.setLocal(description, error: error, type: .offer, run: run, pc: pc)
                    }
                }
            }
        }
    }
    private func setLocal(_ description: RTCSessionDescription?, error: Error?, type: RTCSdpType, run: Run, pc: RTCPeerConnection) {
        guard error == nil, let description else { run.callbacks.state?(.failed); return }
        let local = RTCSessionDescription(type: type, sdp: WebRTCSignalingCodec.forceOpus32k(description.sdp))
        pc.setLocalDescription(local) { [weak self] error in
            self?.ingress(run, pc) { run in
                guard error == nil else { run.callbacks.state?(.failed); return }
                do {
                    let payload = try WebRTCSignalingCodec.encodeSessionDescription(type: type == .offer ? "offer" : "answer", sdp: local.sdp)
                    if type == .offer { run.callbacks.offer?(payload) } else { run.callbacks.answer?(payload) }
                } catch { run.callbacks.state?(.failed) }
            }
        }
    }
    public func setRemoteOffer(_ sdpJSON: String) throws {
        try owned {
            let run = try requireRun(); let pc = run.connection!
            let sdp = try WebRTCSignalingCodec.decodeSessionDescription(sdpJSON, expectedType: "offer")
            pc.setRemoteDescription(RTCSessionDescription(type: .offer, sdp: sdp)) { [weak self] error in
                self?.ingress(run, pc) { [weak self] run in
                    guard let self, error == nil else { run.callbacks.state?(.failed); return }
                    self.flush(run, pc)
                    pc.answer(for: RTCMediaConstraints(mandatoryConstraints: ["OfferToReceiveAudio": "true"], optionalConstraints: nil)) { [weak self] answer, error in
                        self?.ingress(run, pc) { [weak self] run in self?.setLocal(answer, error: error, type: .answer, run: run, pc: pc) }
                    }
                }
            }
        }
    }
    public func setRemoteAnswer(_ sdpJSON: String) throws {
        try owned {
            let run = try requireRun(); let pc = run.connection!
            let sdp = try WebRTCSignalingCodec.decodeSessionDescription(sdpJSON, expectedType: "answer")
            pc.setRemoteDescription(RTCSessionDescription(type: .answer, sdp: sdp)) { [weak self] error in
                self?.ingress(run, pc) { [weak self] run in
                    guard let self, error == nil else { run.callbacks.state?(.failed); return }
                    self.flush(run, pc)
                }
            }
        }
    }
    public func addRemoteCandidate(_ candidateJSON: String) throws {
        try owned {
            let run = try requireRun(); let pc = run.connection!
            let candidate = try WebRTCSignalingCodec.decodeCandidate(candidateJSON)
            let ice = RTCIceCandidate(sdp: candidate.candidate, sdpMLineIndex: candidate.sdpMLineIndex, sdpMid: candidate.sdpMid)
            if run.remoteDescriptionSet { pc.add(ice) }
            else {
                guard run.candidates.count < 64 else { throw MotoComError.invalidFrame("too many remote candidates") }
                run.candidates.append(ice)
            }
        }
    }
    private func flush(_ run: Run, _ pc: RTCPeerConnection) {
        run.remoteDescriptionSet = true
        let pending = run.candidates; run.candidates.removeAll()
        pending.forEach { pc.add($0) }
    }
    public func setAudioEnabled(_ enabled: Bool) { owned { current?.track?.isEnabled = enabled } }
    private func closeCurrent() {
        let old = current; current = nil
        old?.candidates.removeAll(); old?.connection?.close(); old?.track = nil
        old?.callbacks.state?(.closed)
    }
    public func close() { owned { closeCurrent() } }

    /// The real decoded-audio sink obtains this observer while attached to its
    /// actual PC. A saved A observer can never grant B's AUDIO_READY.
    public func remoteAudioFrameObserver(for pc: RTCPeerConnection) -> (() -> Void)? {
        owned {
            guard let run = current, run.connection === pc else { return nil }
            return { [weak self] in self?.ingress(run, pc) { $0.callbacks.frame?() } }
        }
    }

    #if MOTOCOM_REQUIRE_NATIVE_WEBRTC
    // CI-only ingress receipt retains the real old PC and its native delegate.
    // It exercises production revocation without enabling a microphone or
    // treating an injected callback as actual decoded audio evidence.
    func nativeValidationReceipt() -> (RTCPeerConnection, RTCPeerConnectionDelegate, () -> Void)? {
        owned {
            guard let run = current, let pc = run.connection, let delegate = run.delegate else { return nil }
            return (pc, delegate, { [weak self] in self?.ingress(run, pc) { $0.callbacks.frame?() } })
        }
    }
    func waitForNativeIngress() { owned {} }
    #endif

    private final class PeerDelegate: NSObject, RTCPeerConnectionDelegate {
        weak var owner: GoogleWebRTCEngine?
        weak var run: Run?
        init(owner: GoogleWebRTCEngine, run: Run) { self.owner = owner; self.run = run }
        private func deliver(_ pc: RTCPeerConnection, _ action: @escaping (Run) -> Void) {
            guard let run else { return }; owner?.ingress(run, pc, action)
        }
        func peerConnection(_ pc: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
        func peerConnection(_ pc: RTCPeerConnection, didAdd stream: RTCMediaStream) {
            if !stream.audioTracks.isEmpty { deliver(pc) { $0.callbacks.track?() } }
        }
        func peerConnection(_ pc: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
        func peerConnectionShouldNegotiate(_ pc: RTCPeerConnection) {}
        func peerConnection(_ pc: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
            deliver(pc) { run in
                switch newState {
                case .connected, .completed: run.callbacks.state?(.connected)
                case .failed, .disconnected: run.callbacks.state?(.failed)
                case .closed: run.callbacks.state?(.closed)
                default: run.callbacks.state?(.negotiating)
                }
            }
        }
        func peerConnection(_ pc: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}
        func peerConnection(_ pc: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
            deliver(pc) { run in
                do { run.callbacks.candidate?(try WebRTCSignalingCodec.encodeCandidate(sdpMid: candidate.sdpMid, sdpMLineIndex: candidate.sdpMLineIndex, candidate: candidate.sdp)) }
                catch { run.callbacks.state?(.failed) }
            }
        }
        func peerConnection(_ pc: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
        func peerConnection(_ pc: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
        func peerConnection(_ pc: RTCPeerConnection, didAdd rtpReceiver: RTCRtpReceiver, streams mediaStreams: [RTCMediaStream]) {
            if rtpReceiver.track is RTCAudioTrack { deliver(pc) { $0.callbacks.track?() } }
        }
    }
}
#endif
