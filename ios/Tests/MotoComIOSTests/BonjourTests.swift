import XCTest
@testable import MotoComIOS

#if canImport(Network)
import Network

final class BonjourTests: XCTestCase {
    func testAndroidCompatibleTXTRecordRoundTrips() throws {
        let advertisement = try BonjourServiceAdvertisement(
            deviceID: "00000000-0000-4000-8000-000000000011",
            sessionID: "00000000-0000-4000-8000-000000000033",
            nickname: "Android",
            deviceName: "Xiaomi 6",
            platform: .android,
            capabilities: [.lan, .androidWiFiDirect],
            networkRole: .either,
            tcpPort: 8890
        )
        let decoded = try BonjourServiceAdvertisement(txtRecord: advertisement.txtRecord())
        XCTAssertEqual(decoded, advertisement)
    }

    func testAndroidTXTWithoutPortUsesDNSServicePort() throws {
        let record = NWTXTRecord([
            "id": "00000000-0000-4000-8000-000000000011",
            "sessionId": "00000000-0000-4000-8000-000000000033",
            "name": "Android",
            "deviceName": "Xiaomi 6",
            "protocolVersion": "2",
            "platform": "ANDROID",
            "capabilities": "LAN,ANDROID_WIFI_DIRECT",
            "networkRole": "EITHER"
        ])
        let decoded = try BonjourServiceAdvertisement(txtRecord: record)
        XCTAssertEqual(decoded.tcpPort, 8890)
        XCTAssertEqual(decoded.platform, .android)
    }

    func testBonjourTXTRejectsUnknownRootField() throws {
        let record = NWTXTRecord([
            "id": "00000000-0000-4000-8000-000000000011",
            "sessionId": "00000000-0000-4000-8000-000000000033",
            "name": "Android",
            "deviceName": "Xiaomi 6",
            "protocolVersion": "2",
            "platform": "ANDROID",
            "capabilities": "LAN",
            "networkRole": "EITHER",
            "tcpPort": "8890",
            "unexpected": "1"
        ])
        XCTAssertThrowsError(try BonjourServiceAdvertisement(txtRecord: record))
    }
}
#endif
