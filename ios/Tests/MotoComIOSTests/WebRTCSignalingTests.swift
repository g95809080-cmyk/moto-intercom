import XCTest
@testable import MotoComIOS

final class WebRTCSignalingTests: XCTestCase {
    func testSessionDescriptionUsesAndroidJSONContract() throws {
        let sdp = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
        let encoded = try WebRTCSignalingCodec.encodeSessionDescription(type: "offer", sdp: sdp)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(encoded.utf8)) as? [String: Any])

        XCTAssertEqual(object["type"] as? String, "offer")
        XCTAssertEqual(object["sdp"] as? String, sdp)
        XCTAssertEqual(
            try WebRTCSignalingCodec.decodeSessionDescription(encoded, expectedType: "offer"),
            sdp
        )
    }

    func testOpusParametersMatchAndroidContract() {
        let sdp = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 0 111 126\r\na=rtpmap:111 opus/48000/2\r\n"
        let normalized = WebRTCSignalingCodec.forceOpus32k(sdp)

        XCTAssertTrue(normalized.contains("m=audio 9 UDP/TLS/RTP/SAVPF 111 0 126"))
        XCTAssertTrue(normalized.contains(
            "a=fmtp:111 minptime=10;useinbandfec=1;usedtx=1;maxaveragebitrate=32000;stereo=0;sprop-stereo=0"
        ))
    }

    func testCandidateUsesAndroidJSONContract() throws {
        let encoded = try WebRTCSignalingCodec.encodeCandidate(
            sdpMid: "0",
            sdpMLineIndex: 0,
            candidate: "candidate:1 1 UDP 1 192.168.1.2 5000 typ host"
        )
        let decoded = try WebRTCSignalingCodec.decodeCandidate(encoded)
        XCTAssertEqual(decoded.sdpMid, "0")
        XCTAssertEqual(decoded.sdpMLineIndex, 0)
        XCTAssertTrue(decoded.candidate.hasPrefix("candidate:"))
    }

    func testRawSdpAndNullSdpMidAreRejected() {
        XCTAssertThrowsError(try WebRTCSignalingCodec.decodeSessionDescription("v=0", expectedType: "offer"))
        XCTAssertThrowsError(try WebRTCSignalingCodec.encodeCandidate(
            sdpMid: nil,
            sdpMLineIndex: 0,
            candidate: "candidate:1"
        ))
    }
}
