import XCTest
@testable import MotoComIOS

final class PairingAndAudioTests: XCTestCase {
    func testPairingCannotBeSavedBeforeAudioReady() async throws {
        let suiteName = "com.motocom.tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        let store = PairingStore(storage: defaults)
        let record = try PairingRecord(
            remoteDeviceID: "00000000-0000-4000-8000-000000000022",
            remoteNickname: "Android",
            deviceName: "Xiaomi"
        )
        do {
            try await store.saveConnectedPeer(record, audioReady: false, transport: "LAN")
            XCTFail("pairing must require AUDIO_READY")
        } catch let error as MotoComError {
            XCTAssertEqual(error, .notAudioReady)
        }
        let stored = await store.all()
        XCTAssertTrue(stored.isEmpty)
        defaults.removePersistentDomain(forName: suiteName)
    }

    func testAudioReadyRequiresRemoteFrameAndRouteAndNoInterruption() {
        var readiness = AudioReadiness()
        XCTAssertFalse(readiness.isReady)
        readiness.remoteTrackPresent = true
        readiness.remoteFirstFrameReceived = true
        readiness.localRouteReady = true
        XCTAssertTrue(readiness.isReady)
        readiness.phoneInterrupting = true
        XCTAssertFalse(readiness.isReady)
    }

    func testAudioReadySavesAndUpdatesTransport() async throws {
        let suiteName = "com.motocom.tests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        let store = PairingStore(storage: defaults)
        let record = try PairingRecord(
            remoteDeviceID: "00000000-0000-4000-8000-000000000022",
            remoteNickname: "Android",
            deviceName: "Xiaomi"
        )

        try await store.saveConnectedPeer(record, audioReady: true, transport: "LAN")

        let savedRecord = await store.record(for: record.remoteDeviceID)
        let saved = try XCTUnwrap(savedRecord)
        XCTAssertEqual(saved.lastTransport, "LAN")
        XCTAssertEqual(saved.remoteDeviceID, record.remoteDeviceID)
        defaults.removePersistentDomain(forName: suiteName)
    }
}
