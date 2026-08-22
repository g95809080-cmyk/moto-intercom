import XCTest
@testable import MotoComIOS

final class NetworkPolicyTests: XCTestCase {
    private func capabilities(
        platform: MotoComPlatform,
        values: Set<NetworkCapability>
    ) throws -> RuntimeCapabilities {
        try RuntimeCapabilities(
            platform: platform,
            platformVersion: "16.7",
            apiLevel: platform == .android ? 28 : nil,
            deviceName: platform == .ios ? "iPhone X" : "Android",
            capabilities: values
        )
    }

    func testAndroidHostIsSelectedForIOSPeer() throws {
        let local = try capabilities(platform: .ios, values: [.bleBootstrap, .lan, .wifiJoin, .iosPersonalHotspotManual])
        let remote = try capabilities(platform: .android, values: [.bleBootstrap, .lan, .androidLocalOnlyHotspotHost])
        let decision = try BootstrapPolicy.choose(local: local, remote: remote, context: BootstrapContext())
        XCTAssertEqual(decision.path, .androidLocalOnlyHotspot)
        XCTAssertEqual(decision.localRole, .joiner)
        XCTAssertFalse(decision.requiresUserAction)
    }

    func testIOSHostRequiresManualAction() throws {
        let local = try capabilities(platform: .ios, values: [.bleBootstrap, .lan, .iosPersonalHotspotManual])
        let remote = try capabilities(platform: .android, values: [.bleBootstrap, .lan, .wifiJoin])
        let decision = try BootstrapPolicy.choose(
            local: local,
            remote: remote,
            context: BootstrapContext(userSelectedIOSHost: true)
        )
        XCTAssertEqual(decision.path, .iosPersonalHotspotManual)
        XCTAssertTrue(decision.requiresUserAction)
    }

    func testAndroidToAndroidKeepsWifiDirect() throws {
        let local = try capabilities(platform: .android, values: [.bleBootstrap, .androidWiFiDirect])
        let remote = try capabilities(platform: .android, values: [.bleBootstrap, .androidWiFiDirect])
        let decision = try BootstrapPolicy.choose(local: local, remote: remote, context: BootstrapContext())
        XCTAssertEqual(decision.path, .androidWiFiDirect)
    }

    func testIOSPeerToPeerRequiresBothCapabilities() throws {
        let local = try capabilities(platform: .ios, values: [.bleBootstrap, .lan, .iosPeerToPeer])
        let remote = try capabilities(platform: .ios, values: [.bleBootstrap, .lan, .iosPeerToPeer])
        let decision = try BootstrapPolicy.choose(local: local, remote: remote, context: BootstrapContext())
        XCTAssertEqual(decision.path, .applePeerToPeer)
    }

    func testCommonLANWinsBeforeIOSPeerToPeer() throws {
        let local = try capabilities(platform: .ios, values: [.bleBootstrap, .lan, .iosPeerToPeer])
        let remote = try capabilities(platform: .ios, values: [.bleBootstrap, .lan, .iosPeerToPeer])
        let decision = try BootstrapPolicy.choose(
            local: local,
            remote: remote,
            context: BootstrapContext(commonLANAvailable: true)
        )
        XCTAssertEqual(decision.path, .commonLAN)
    }

    func testBluetoothPermissionBlocksAutomaticBootstrap() throws {
        let local = try capabilities(platform: .ios, values: [.bleBootstrap])
        let remote = try capabilities(platform: .android, values: [.bleBootstrap])
        XCTAssertThrowsError(try BootstrapPolicy.choose(
            local: local,
            remote: remote,
            context: BootstrapContext(bluetoothPermissionGranted: false)
        )) { error in
            XCTAssertEqual(error as? MotoComError, .permissionDenied("Bluetooth is required for automatic bootstrap"))
        }
    }
}
