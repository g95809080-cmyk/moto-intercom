import XCTest
@testable import MotoComIOS

final class BootstrapTests: XCTestCase {
    func testBLEFragmentsReassembleOutOfOrder() throws {
        let payload = Data(repeating: 0xA5, count: 1_001)
        let chunks = try BLEFragmenter.fragment(payload, messageID: "00000000-0000-4000-8000-000000000099")
        var reassembler = BLEReassembler()
        var result: Data?
        for chunk in chunks.reversed() {
            result = reassembler.append(chunk)
        }
        XCTAssertEqual(result, payload)
        XCTAssertEqual(reassembler.pendingMessageCount, 0)
    }

    func testBLEChunkWireRoundTrips() throws {
        let chunk = try XCTUnwrap(BLEFragmenter.fragment(
            Data("wire".utf8),
            messageID: "00000000-0000-4000-8000-000000000099"
        ).first)
        XCTAssertEqual(try BLEChunkWireCodec.decode(try BLEChunkWireCodec.encode(chunk)), chunk)
    }

    func testBLEChunkUsesAndroidBigEndianHeader() throws {
        let chunk = try BLEChunk(
            messageID: 0x01020304,
            index: 2,
            count: 3,
            messageBytes: 4,
            payload: Data("wire".utf8)
        )
        var expected = Data([
            0x01, 0x02, 0x03, 0x04,
            0x00, 0x02,
            0x00, 0x03,
            0x00, 0x00, 0x00, 0x04
        ])
        expected.append(Data("wire".utf8))
        XCTAssertEqual(
            try BLEChunkWireCodec.encode(chunk),
            expected
        )
    }

    func testBLEFragmentExpires() throws {
        let chunk = try XCTUnwrap(BLEFragmenter.fragment(
            Data("payload".utf8),
            messageID: "00000000-0000-4000-8000-000000000099"
        ).first)
        var reassembler = BLEReassembler(timeout: 1)
        XCTAssertNil(reassembler.append(chunk, now: Date(timeIntervalSince1970: 10)))
        reassembler.expire(now: Date(timeIntervalSince1970: 12))
        XCTAssertEqual(reassembler.pendingMessageCount, 0)
    }

    func testBootstrapAnnouncementRoundTrips() throws {
        let announcement = try BootstrapAnnouncement(
            platform: .ios,
            deviceID: "00000000-0000-4000-8000-000000000011",
            sessionID: "00000000-0000-4000-8000-000000000033",
            capabilities: [.bleBootstrap, .lan, .iosPeerToPeer],
            networkRole: .either
        )
        let message = BootstrapMessage(type: .capabilities, announcement: announcement)
        XCTAssertEqual(try BootstrapCodec.decode(try BootstrapCodec.encode(message)), message)
    }

    func testAndroidCapabilityAdvertisementFixtureDecodes() throws {
        let url = try XCTUnwrap(
            Bundle.module.url(
                forResource: "ble-capabilities",
                withExtension: "json",
                subdirectory: "Fixtures"
            )
        )
        let announcement = try BootstrapCodec.decodeAdvertisement(Data(contentsOf: url))
        XCTAssertEqual(announcement.platform, .ios)
        XCTAssertEqual(announcement.deviceID, "00000000-0000-4000-8000-000000000011")
        XCTAssertEqual(announcement.tcpPort, 8890)
        XCTAssertTrue(announcement.capabilities.contains(.iosPeerToPeer))
    }

    func testBLEChunkLimitMatchesAndroidAssembler() throws {
        let data = Data(repeating: 0xA5, count: 4 * 1024)
        let chunks = try BLEFragmenter.fragment(
            data,
            messageID: "00000000-0000-4000-8000-000000000099",
            maxPacketBytes: 20
        )
        XCTAssertEqual(chunks.count, 512)
    }

    func testBootstrapRejectsUnknownRootField() throws {
        let data = Data(#"{"protocolVersion":2,"type":"BOOTSTRAP_REQUEST","requestId":"00000000-0000-4000-8000-000000000099","fields":{},"capabilities":[],"extra":1}"#.utf8)
        XCTAssertThrowsError(try BootstrapCodec.decode(data))
    }

    func testCapabilityAdvertisementRejectsDuplicateCapabilities() throws {
        let data = Data(#"{"app":"motocom","protocolVersion":2,"platform":"IOS","deviceId":"00000000-0000-4000-8000-000000000011","sessionId":"00000000-0000-4000-8000-000000000033","capabilities":["LAN","LAN"],"networkRole":"EITHER","tcpPort":8890}"#.utf8)
        XCTAssertThrowsError(try BootstrapCodec.decodeAdvertisement(data))
    }

    func testCapabilityAdvertisementRejectsDecimalPort() throws {
        let data = Data(#"{"app":"motocom","protocolVersion":2,"platform":"IOS","deviceId":"00000000-0000-4000-8000-000000000011","sessionId":"00000000-0000-4000-8000-000000000033","capabilities":["LAN"],"networkRole":"EITHER","tcpPort":8890.0}"#.utf8)
        XCTAssertThrowsError(try BootstrapCodec.decodeAdvertisement(data))
    }
}
