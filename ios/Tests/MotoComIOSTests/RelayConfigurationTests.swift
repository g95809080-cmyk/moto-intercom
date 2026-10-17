import XCTest
@testable import MotoComIOS

final class RelayConfigurationTests: XCTestCase {
    func testNearbyGuardAndExplicitRelayCredentials() throws {
        let now = Date(timeIntervalSince1970: 1000)
        let turn = WebRTCTurnServer(urls: ["turn:127.0.0.1:3478?transport=udp"], username: "2000:a",
                                   credential: "temporary", expiresAt: now.addingTimeInterval(60))
        XCTAssertNoThrow(try WebRTCSessionConfiguration().validate(now: now))
        XCTAssertThrowsError(try WebRTCSessionConfiguration(iceServers: [turn]).validate(now: now))
        XCTAssertThrowsError(try WebRTCSessionConfiguration(hostOnlyICE: false, networkMode: .relay).validate(now: now))
        let relay = WebRTCSessionConfiguration(iceServers: [turn], hostOnlyICE: false, networkMode: .relay)
        XCTAssertNoThrow(try relay.validate(now: now))
        XCTAssertThrowsError(try relay.validate(now: now.addingTimeInterval(56)))
        let invalid = WebRTCTurnServer(urls: ["stun:example.org"], username: "u", credential: "p", expiresAt: .distantFuture)
        XCTAssertThrowsError(try invalid.validate(now: now))
    }

    @MainActor func testSecondStartAndQueuedOldCallbacksCannotAffectReplacement() async throws {
        let engine = RelayFakeEngine()
        let coordinator = WebRTCSessionCoordinator(engine: engine, audio: RelayFakeAudio())
        try coordinator.start(offerer: true)
        XCTAssertThrowsError(try coordinator.start(offerer: false))
        XCTAssertEqual(engine.starts, 1)
        let oldState = engine.onStateChanged
        let oldOffer = engine.onLocalOffer
        var offers = [String]()
        coordinator.onLocalOffer = { offers.append($0) }
        oldState?(.failed) // Already enqueued before close: must also be ignored.
        coordinator.close()
        try coordinator.start(offerer: true)
        oldState?(.connected)
        oldOffer?("stale")
        for _ in 0..<10 { await Task.yield() }
        XCTAssertEqual(coordinator.state, .negotiating)
        XCTAssertTrue(offers.isEmpty)
        engine.onLocalOffer?("current")
        for _ in 0..<10 { await Task.yield() }
        XCTAssertEqual(offers, ["current"])
        coordinator.close()
    }
}

@MainActor private final class RelayFakeAudio: WebRTCSessionAudio {
    var isAudioReady = false
    func activate() throws {}
    func deactivate() {}
    func clearRemoteAudio() {}
    func markRemoteTrackPresent() {}
    func markRemoteFirstFrameReceived() {}
}

private final class RelayFakeEngine: WebRTCEngine {
    var onStateChanged: ((WebRTCMediaState) -> Void)?
    var onLocalOffer: ((String) -> Void)?
    var onLocalAnswer: ((String) -> Void)?
    var onLocalCandidate: ((String) -> Void)?
    var onRemoteAudioTrack: (() -> Void)?
    var onRemoteAudioFrame: (() -> Void)?
    var starts = 0
    func start(configuration: WebRTCSessionConfiguration, offerer: Bool) throws { starts += 1 }
    func setRemoteOffer(_ sdpJSON: String) throws {}
    func setRemoteAnswer(_ sdpJSON: String) throws {}
    func addRemoteCandidate(_ candidateJSON: String) throws {}
    func setAudioEnabled(_ enabled: Bool) {}
    func close() { onStateChanged?(.closed) }
}
