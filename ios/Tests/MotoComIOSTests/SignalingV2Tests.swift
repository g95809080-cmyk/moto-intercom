import XCTest
@testable import MotoComIOS

final class SignalingV2Tests: XCTestCase {
    private let codec = SignalingV2Codec()

    func testAndroidHelloFixtureDecodes() throws {
        let envelope = try codec.decode(try fixture("hello-requester"))
        XCTAssertEqual(envelope.protocolVersion, 2)
        XCTAssertEqual(envelope.message.type, .hello)
        guard case .hello(let role, let nickname, let deviceName, let capabilities) = envelope.message else {
            return XCTFail("expected HELLO")
        }
        XCTAssertEqual(role, .requester)
        XCTAssertEqual(nickname, "骑士")
        XCTAssertEqual(deviceName, "iPhone X")
        XCTAssertEqual(capabilities, Set(["BLE_BOOTSTRAP", "LAN"]))
    }

    func testConnectRequestFixtureDecodes() throws {
        let envelope = try codec.decode(try fixture("connect-request"))
        guard case .connectRequest(let trigger, let hint) = envelope.message else {
            return XCTFail("expected CONNECT_REQUEST")
        }
        XCTAssertEqual(trigger, .user)
        XCTAssertEqual(hint, .lan)
    }

    func testEncodeRoundTripsOffer() throws {
        let envelope = try SignalingEnvelope(
            attemptID: "00000000-0000-4000-8000-000000000001",
            sourceDeviceID: "00000000-0000-4000-8000-000000000011",
            targetDeviceID: "00000000-0000-4000-8000-000000000022",
            sourceSessionID: "00000000-0000-4000-8000-000000000033",
            message: .offer(sdpJSON: "v=0\r\n")
        )
        let encoded = try codec.encode(envelope)
        XCTAssertEqual(try codec.decode(encoded), envelope)
    }

    func testUnknownEnvelopeFieldIsRejected() throws {
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: fixture("hello-requester")) as? [String: Any])
        object["platform"] = "IOS"
        let data = try JSONSerialization.data(withJSONObject: object)
        XCTAssertThrowsError(try codec.decode(data)) { error in
            guard let error = error as? MotoComError,
                  case .unexpectedFields(let fields) = error else {
                return XCTFail("unexpected error: \(error)")
            }
            XCTAssertTrue(fields.contains("platform"))
        }
    }

    func testUppercaseUUIDIsRejected() throws {
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: fixture("hello-requester")) as? [String: Any])
        object["attemptId"] = "00000000-0000-4000-8000-00000000000A"
        let data = try JSONSerialization.data(withJSONObject: object)
        XCTAssertThrowsError(try codec.decode(data))
    }

    func testFrameLimitIsRejected() throws {
        let oversized = Data(repeating: 0x78, count: SignalingV2Codec.maxFrameBytes + 1)
        XCTAssertThrowsError(try codec.decode(oversized))
    }

    func testBooleanCannotBeDecodedAsProtocolVersion() throws {
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: fixture("hello-requester")) as? [String: Any])
        object["protocolVersion"] = true
        let data = try JSONSerialization.data(withJSONObject: object)
        XCTAssertThrowsError(try codec.decode(data))
    }

    func testDecimalCannotBeDecodedAsProtocolVersion() throws {
        let raw = try String(decoding: fixture("hello-requester"), as: UTF8.self)
            .replacingOccurrences(of: "\"protocolVersion\":2", with: "\"protocolVersion\":2.0")
        XCTAssertThrowsError(try codec.decode(Data(raw.utf8)))
    }

    private func fixture(_ name: String) throws -> Data {
        let url = try XCTUnwrap(Bundle.module.url(forResource: name, withExtension: "json", subdirectory: "Fixtures"))
        return try Data(contentsOf: url)
    }
}
