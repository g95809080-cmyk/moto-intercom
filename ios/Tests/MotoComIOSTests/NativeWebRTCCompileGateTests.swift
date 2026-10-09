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
        a.1.peerConnection(a.0, didChange: RTCIceConnectionState.failed); a.2()
        b.1.peerConnection(a.0, didChange: RTCIceConnectionState.failed)
        engine.waitForNativeIngress()
        XCTAssertFalse(bStates.contains(.failed)); XCTAssertEqual(bFrames, 0); XCTAssertEqual(aFrames, 0)
        b.2(); engine.waitForNativeIngress()
        XCTAssertEqual(bFrames, 1)
        engine.close(); engine.waitForNativeIngress()
        XCTAssertEqual(aStates.filter { $0 == .closed }.count, 1)
    }
}
#endif
