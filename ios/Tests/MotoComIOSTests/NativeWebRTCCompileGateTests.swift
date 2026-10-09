#if MOTOCOM_REQUIRE_NATIVE_WEBRTC
import XCTest
import WebRTC
@testable import MotoComIOS

final class NativeWebRTCCompileGateTests: XCTestCase {
    func testNativeAdapterTypeIsAvailable() {
        XCTAssertEqual(String(describing: GoogleWebRTCEngine.self), "GoogleWebRTCEngine")
    }
    func testOldActualPCDelegateAndDecodedObserverCannotReadNewRunCallbacks() throws {
        let engine = GoogleWebRTCEngine()
        var aStates = [WebRTCMediaState](); var aFrames = 0
        engine.onStateChanged = { aStates.append($0) }; engine.onRemoteAudioFrame = { aFrames += 1 }
        try engine.start(configuration: WebRTCSessionConfiguration(), offerer: false)
        let a = try XCTUnwrap(engine.nativeValidationReceipt())
        engine.close()
        var bStates = [WebRTCMediaState](); var bFrames = 0
        engine.onStateChanged = { bStates.append($0) }; engine.onRemoteAudioFrame = { bFrames += 1 }
        try engine.start(configuration: WebRTCSessionConfiguration(), offerer: false)
        let b = try XCTUnwrap(engine.nativeValidationReceipt())
        XCTAssertNil(engine.remoteAudioFrameObserver(for: a.0))
        a.1.peerConnection(a.0, didChange: RTCIceConnectionState.failed); a.2()
        b.1.peerConnection(a.0, didChange: RTCIceConnectionState.failed)
        engine.waitForNativeIngress()
        XCTAssertFalse(bStates.contains(.failed)); XCTAssertEqual(bFrames, 0); XCTAssertEqual(aFrames, 0)
        b.2(); engine.waitForNativeIngress()
        XCTAssertEqual(bFrames, 1)
        engine.close(); engine.waitForNativeIngress()
        XCTAssertEqual(aStates.filter { $0 == .closed }.count, 1)
    }
    @MainActor
    func testCurrentActualNativeFailureEndsAcceptedSessionAndAudio() async throws {
        let identity = try StableIdentity(deviceID: "00000000-0000-4000-8000-000000000001",
            sessionID: "00000000-0000-4000-8000-000000000011", nickname: "local", deviceName: "iPhone")
        let capabilities = try RuntimeCapabilities(platform: .ios, platformVersion: "16", deviceName: "iPhone", capabilities: [.lan])
        let driver = TestAudioDriver(); let audio = AudioSessionController(driver: driver)
        let engine = GoogleWebRTCEngine(); let clock = ManualSessionClock()
        let session = SessionCoordinator(pairingStore: TestPairingStore(), audio: audio, webRTCEngine: engine,
            scheduler: clock, initialIdentity: identity, initialCapabilities: capabilities,
            bonjourTransport: TestControlTransport(), peerToPeerTransport: TestControlTransport(), pathDriver: TestPathDriver())
        let raw = TestRawControlIO()
        let controller = try SignalingSessionController(channel: NWControlChannel(io: raw), localIdentity: identity,
            autoStart: false, scheduler: clock)
        session.attachSignaling(controller)
        let request = expectation(description: "real incoming request")
        let incoming = controller.onIncomingRequest
        controller.onIncomingRequest = { incoming?(); request.fulfill() }
        controller.startAsResponder(capabilities: []); controller.start()
        func frame(_ message: SignalingMessage) throws -> Data {
            try LengthPrefixedFraming.encode(SignalingV2Codec().encode(SignalingEnvelope(
                attemptID: "00000000-0000-4000-8000-000000000021", sourceDeviceID: "00000000-0000-4000-8000-000000000002",
                targetDeviceID: identity.deviceID, sourceSessionID: "00000000-0000-4000-8000-000000000012", message: message)))
        }
        raw.emit(try frame(.hello(requestRole: .requester, nickname: "remote", deviceName: "remote", capabilities: [])) +
            frame(.connectRequest(trigger: .user, preferredTransportHint: .lan)))
        await fulfillment(of: [request], timeout: 2)
        session.acceptIncoming()
        XCTAssertEqual(session.phase, .mediaNegotiating); XCTAssertTrue(driver.active)
        let native = try XCTUnwrap(engine.nativeValidationReceipt())
        let ended = expectation(description: "native failure through Session.end")
        let terminal = controller.onTerminated
        controller.onTerminated = { error in terminal?(error); ended.fulfill() }
        native.1.peerConnection(native.0, didChange: RTCIceConnectionState.failed)
        engine.waitForNativeIngress()
        await fulfillment(of: [ended], timeout: 2)
        XCTAssertTrue(controller.isTerminated); XCTAssertFalse(driver.active)
        XCTAssertFalse(audio.readiness.remoteTrackPresent); XCTAssertFalse(audio.readiness.remoteFirstFrameReceived)
        XCTAssertEqual(session.phase, .failed); session.stop()
    }
}
#endif
