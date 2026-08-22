import Foundation

public enum WebRTCMediaState: String, Sendable {
    case idle
    case negotiating
    case connected
    case failed
    case closed
}

public struct WebRTCSessionConfiguration: Equatable, Sendable {
    public let audioCodec: String
    public let iceServers: [String]
    public let hostOnlyICE: Bool

    public init(
        audioCodec: String = "opus",
        iceServers: [String] = [],
        hostOnlyICE: Bool = true
    ) {
        self.audioCodec = audioCodec
        self.iceServers = iceServers
        self.hostOnlyICE = hostOnlyICE
    }
}

public struct WebRTCCandidatePayload: Equatable, Sendable {
    public let sdpMid: String
    public let sdpMLineIndex: Int32
    public let candidate: String

    public init(sdpMid: String, sdpMLineIndex: Int32, candidate: String) throws {
        guard !sdpMid.isEmpty else { throw MotoComError.invalidField("sdpMid") }
        guard !candidate.isEmpty else { throw MotoComError.invalidField("candidate") }
        self.sdpMid = sdpMid
        self.sdpMLineIndex = sdpMLineIndex
        self.candidate = candidate
    }
}

/// Encodes the WebRTC payload strings used by Android's RiderAudioEngine.
/// The V2 envelope carries these values as strings, while the string itself
/// is a small JSON object for SDP and ICE candidates.
public enum WebRTCSignalingCodec {
    private static let opusFMTP =
        "minptime=10;useinbandfec=1;usedtx=1;maxaveragebitrate=32000;stereo=0;sprop-stereo=0"

    public static func encodeSessionDescription(type: String, sdp: String) throws -> String {
        guard type == "offer" || type == "answer" else {
            throw MotoComError.invalidField("sdp type")
        }
        let normalized = try boundedSDP(forceOpus32k(sdp))
        return try encodeJSONObject(
            ["type": type, "sdp": normalized],
            name: "sdp",
            maximumBytes: SignalingV2Codec.maxFrameBytes
        )
    }

    public static func decodeSessionDescription(
        _ json: String,
        expectedType: String
    ) throws -> String {
        guard expectedType == "offer" || expectedType == "answer" else {
            throw MotoComError.invalidField("sdp type")
        }
        let object = try decodeJSONObject(json, name: "sdp", maximumBytes: SignalingV2Codec.maxFrameBytes)
        try requireExactKeys(object, expected: ["type", "sdp"])
        guard object["type"] as? String == expectedType else {
            throw MotoComError.invalidField("sdp type")
        }
        guard let sdp = object["sdp"] as? String else {
            throw MotoComError.invalidField("sdp")
        }
        return try boundedSDP(sdp)
    }

    public static func encodeCandidate(
        sdpMid: String?,
        sdpMLineIndex: Int32,
        candidate: String
    ) throws -> String {
        guard let sdpMid else { throw MotoComError.invalidField("sdpMid") }
        let payload = try WebRTCCandidatePayload(
            sdpMid: sdpMid,
            sdpMLineIndex: sdpMLineIndex,
            candidate: candidate
        )
        guard Data(candidate.utf8).count <= SignalingV2Codec.maxCandidateBytes else {
            throw MotoComError.invalidFrame("candidate is too large")
        }
        return try encodeJSONObject([
            "sdpMid": payload.sdpMid,
            "sdpMLineIndex": payload.sdpMLineIndex,
            "candidate": payload.candidate
        ], name: "candidate", maximumBytes: SignalingV2Codec.maxFrameBytes)
    }

    public static func decodeCandidate(_ json: String) throws -> WebRTCCandidatePayload {
        let object = try decodeJSONObject(
            json,
            name: "candidate",
            maximumBytes: SignalingV2Codec.maxFrameBytes
        )
        try requireExactKeys(object, expected: ["sdpMid", "sdpMLineIndex", "candidate"])
        guard let sdpMid = object["sdpMid"] as? String,
              let candidate = object["candidate"] as? String,
              let rawIndex = object["sdpMLineIndex"],
              let indexValue = StrictJSONNumber.int64(rawIndex),
              let index = Int32(exactly: indexValue) else {
            throw MotoComError.invalidField("candidate")
        }
        guard Data(candidate.utf8).count <= SignalingV2Codec.maxCandidateBytes else {
            throw MotoComError.invalidFrame("candidate is too large")
        }
        return try WebRTCCandidatePayload(
            sdpMid: sdpMid,
            sdpMLineIndex: index,
            candidate: candidate
        )
    }

    static func forceOpus32k(_ sdp: String) -> String {
        var lines = sdp
            .replacingOccurrences(of: "\r\n", with: "\n")
            .split(separator: "\n", omittingEmptySubsequences: true)
            .map(String.init)
        guard let rtpmapIndex = lines.firstIndex(where: {
            $0.hasPrefix("a=rtpmap:") &&
                $0.range(of: "opus/48000", options: [.caseInsensitive]) != nil
        }) else {
            return sdp
        }
        let rtpmap = lines[rtpmapIndex]
        let payload = String(rtpmap.dropFirst("a=rtpmap:".count).prefix { $0 != " " })
        guard !payload.isEmpty else { return sdp }

        if let audioIndex = lines.firstIndex(where: { $0.hasPrefix("m=audio ") }) {
            let parts = lines[audioIndex].split(separator: " ").map(String.init)
            if parts.count >= 3 {
                let header = Array(parts.prefix(3))
                let payloads = parts.dropFirst(3).filter { $0 != payload }
                lines[audioIndex] = (header + [payload] + payloads).joined(separator: " ")
            }
        }

        let fmtpPrefix = "a=fmtp:\(payload)"
        if let fmtpIndex = lines.firstIndex(where: { $0.hasPrefix(fmtpPrefix) }) {
            lines[fmtpIndex] = mergeOpusFmtp(lines[fmtpIndex], prefix: fmtpPrefix)
        } else {
            lines.insert("\(fmtpPrefix) \(opusFMTP)", at: rtpmapIndex + 1)
        }
        return lines.joined(separator: "\r\n") + "\r\n"
    }

    private static func boundedSDP(_ sdp: String) throws -> String {
        let data = Data(sdp.utf8)
        guard !data.isEmpty, data.count <= SignalingV2Codec.maxSDPBytes else {
            throw MotoComError.invalidFrame("sdp bytes=\(data.count) max=\(SignalingV2Codec.maxSDPBytes)")
        }
        return sdp
    }

    private static func encodeJSONObject(
        _ object: [String: Any],
        name: String,
        maximumBytes: Int
    ) throws -> String {
        guard JSONSerialization.isValidJSONObject(object) else {
            throw MotoComError.invalidFrame("invalid \(name) JSON")
        }
        let data = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        guard data.count <= maximumBytes else {
            throw MotoComError.invalidFrame("\(name) bytes=\(data.count) max=\(maximumBytes)")
        }
        guard let string = String(data: data, encoding: .utf8) else {
            throw MotoComError.invalidFrame("\(name) is not UTF-8")
        }
        return string
    }

    private static func decodeJSONObject(
        _ json: String,
        name: String,
        maximumBytes: Int
    ) throws -> [String: Any] {
        let data = Data(json.utf8)
        guard !data.isEmpty, data.count <= maximumBytes else {
            throw MotoComError.invalidFrame("\(name) JSON is too large")
        }
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw MotoComError.invalidFrame("\(name) must be a JSON object")
        }
        return object
    }

    private static func requireExactKeys(_ object: [String: Any], expected: Set<String>) throws {
        let actual = Set(object.keys)
        let missing = expected.subtracting(actual)
        guard missing.isEmpty else { throw MotoComError.missingField(missing.sorted().joined(separator: ",")) }
        let unknown = actual.subtracting(expected)
        guard unknown.isEmpty else { throw MotoComError.unexpectedFields(unknown) }
    }

    private static func mergeOpusFmtp(_ line: String, prefix: String) -> String {
        var params = [(String, String)]()
        let suffix = String(line.dropFirst(prefix.count)).trimmingCharacters(in: .whitespaces)
        for item in suffix.split(separator: ";") {
            let pair = item.split(separator: "=", maxSplits: 1).map(String.init)
            let key = pair[0].trimmingCharacters(in: .whitespaces)
            let value = pair.count == 2 ? pair[1].trimmingCharacters(in: .whitespaces) : ""
            guard !key.isEmpty else { continue }
            if let index = params.firstIndex(where: { $0.0 == key }) {
                params[index].1 = value
            } else {
                params.append((key, value))
            }
        }
        for item in opusFMTP.split(separator: ";") {
            let pair = item.split(separator: "=", maxSplits: 1).map(String.init)
            let key = pair[0]
            let value = pair.count == 2 ? pair[1] : ""
            if let index = params.firstIndex(where: { $0.0 == key }) {
                params[index].1 = value
            } else {
                params.append((key, value))
            }
        }
        let encoded = params.map { $0.1.isEmpty ? $0.0 : "\($0.0)=\($0.1)" }
        return "\(prefix) " + encoded.joined(separator: ";")
    }
}

public protocol WebRTCEngine: AnyObject {
    var onStateChanged: ((WebRTCMediaState) -> Void)? { get set }
    var onLocalOffer: ((String) -> Void)? { get set }
    var onLocalAnswer: ((String) -> Void)? { get set }
    var onLocalCandidate: ((String) -> Void)? { get set }
    var onRemoteAudioTrack: (() -> Void)? { get set }
    var onRemoteAudioFrame: (() -> Void)? { get set }

    func start(configuration: WebRTCSessionConfiguration, offerer: Bool) throws
    func setRemoteOffer(_ sdpJSON: String) throws
    func setRemoteAnswer(_ sdpJSON: String) throws
    func addRemoteCandidate(_ candidateJSON: String) throws
    func setAudioEnabled(_ enabled: Bool)
    func close()
}

public final class UnavailableWebRTCEngine: WebRTCEngine {
    public var onStateChanged: ((WebRTCMediaState) -> Void)?
    public var onLocalOffer: ((String) -> Void)?
    public var onLocalAnswer: ((String) -> Void)?
    public var onLocalCandidate: ((String) -> Void)?
    public var onRemoteAudioTrack: (() -> Void)?
    public var onRemoteAudioFrame: (() -> Void)?

    public init() {}

    public func start(configuration: WebRTCSessionConfiguration, offerer: Bool) throws {
        throw MotoComError.unavailable(
            "WebRTC iOS SDK is not bundled; inject a WebRTCEngine implementation backed by the approved SDK"
        )
    }

    public func setRemoteOffer(_ sdpJSON: String) throws {
        throw MotoComError.unavailable("WebRTC iOS SDK is not bundled")
    }

    public func setRemoteAnswer(_ sdpJSON: String) throws {
        throw MotoComError.unavailable("WebRTC iOS SDK is not bundled")
    }

    public func addRemoteCandidate(_ candidateJSON: String) throws {
        throw MotoComError.unavailable("WebRTC iOS SDK is not bundled")
    }

    public func setAudioEnabled(_ enabled: Bool) {}

    public func close() {
        onStateChanged?(.closed)
    }
}

public enum WebRTCEngineFactory {
    public static func makeDefault() -> WebRTCEngine {
        #if canImport(WebRTC)
        return GoogleWebRTCEngine()
        #else
        return UnavailableWebRTCEngine()
        #endif
    }
}

@MainActor
public final class WebRTCSessionCoordinator {
    public private(set) var state: WebRTCMediaState = .idle
    public let configuration: WebRTCSessionConfiguration
    public var onLocalOffer: (@MainActor (String) -> Void)?
    public var onLocalAnswer: (@MainActor (String) -> Void)?
    public var onLocalCandidate: (@MainActor (String) -> Void)?
    public var onAudioReadinessChanged: (@MainActor () -> Void)?

    private let engine: WebRTCEngine
    private let audio: AudioSessionController

    public init(
        engine: WebRTCEngine = UnavailableWebRTCEngine(),
        audio: AudioSessionController,
        configuration: WebRTCSessionConfiguration = WebRTCSessionConfiguration()
    ) {
        self.engine = engine
        self.audio = audio
        self.configuration = configuration
        engine.onStateChanged = { [weak self] state in
            Task { @MainActor [weak self] in
                self?.state = state
                self?.onAudioReadinessChanged?()
            }
        }
        engine.onRemoteAudioTrack = { [weak self] in
            Task { @MainActor [weak self] in
                self?.audio.markRemoteTrackPresent()
                self?.onAudioReadinessChanged?()
            }
        }
        engine.onRemoteAudioFrame = { [weak self] in
            Task { @MainActor [weak self] in
                self?.audio.markRemoteFirstFrameReceived()
                self?.onAudioReadinessChanged?()
            }
        }
        engine.onLocalOffer = { [weak self] offer in
            Task { @MainActor [weak self] in self?.onLocalOffer?(offer) }
        }
        engine.onLocalAnswer = { [weak self] answer in
            Task { @MainActor [weak self] in self?.onLocalAnswer?(answer) }
        }
        engine.onLocalCandidate = { [weak self] candidate in
            Task { @MainActor [weak self] in self?.onLocalCandidate?(candidate) }
        }
    }

    public func start(offerer: Bool) throws {
        audio.clearRemoteAudio()
        try audio.activate()
        state = .negotiating
        do {
            try engine.start(configuration: configuration, offerer: offerer)
        } catch {
            audio.deactivate()
            state = .failed
            throw error
        }
    }

    public func setRemoteOffer(_ sdpJSON: String) throws {
        try engine.setRemoteOffer(sdpJSON)
    }

    public func setRemoteAnswer(_ sdpJSON: String) throws {
        try engine.setRemoteAnswer(sdpJSON)
    }

    public func addRemoteCandidate(_ candidateJSON: String) throws {
        try engine.addRemoteCandidate(candidateJSON)
    }

    public func handleAudioInterruption(_ interrupted: Bool) {
        engine.setAudioEnabled(!interrupted)
        if !interrupted { try? audio.activate() }
    }

    public var audioReady: Bool {
        state == .connected && audio.isAudioReady
    }

    public func close() {
        engine.close()
        audio.clearRemoteAudio()
        audio.deactivate()
        state = .closed
    }
}
