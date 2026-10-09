import XCTest
@testable import MotoComIOS

@MainActor
final class BootstrapOwnershipTests: XCTestCase {
    func credentials(_ ssid: String) throws -> HotspotCredentials {
        try HotspotCredentials(ssid: ssid, security: "WPA2", password: "test-only-password")
    }
    func testStopBeforeChildApplyStartsDoesNotInvokeNativeJoin() async throws {
        var joins = 0
        let scheduled = expectation(description: "apply executor held before native start")
        var continuation: CheckedContinuation<Void, Never>?
        let bootstrap = NetworkBootstrapCoordinator(beforeApplyStart: {
            await withCheckedContinuation { continuation = $0; scheduled.fulfill() }
        }, join: { _ in joins += 1 })
        let task = Task { try await bootstrap.acceptAndroidHotspot(credentials("A")) }
        await fulfillment(of: [scheduled], timeout: 2)
        bootstrap.close()
        continuation?.resume()
        _ = try? await task.value
        XCTAssertEqual(bootstrap.state, .offline)
        XCTAssertEqual(joins, 0)
    }
    func testBWaitsForActualAApplyCompletionAndOldAErrorCannotClearB() async throws {
        var continuations = [CheckedContinuation<Void, Error>]()
        let first = expectation(description: "A native join"); let second = expectation(description: "B native join")
        var ssids = [String]()
        let bootstrap = NetworkBootstrapCoordinator { credentials in
            try await withCheckedThrowingContinuation { continuation in
                ssids.append(credentials.ssid); continuations.append(continuation)
                if ssids.count == 1 { first.fulfill() } else { second.fulfill() }
            }
        }
        let a = Task { try await bootstrap.acceptAndroidHotspot(credentials("A")) }
        await fulfillment(of: [first], timeout: 2)
        bootstrap.close()
        let b = Task { try await bootstrap.acceptAndroidHotspot(credentials("B")) }
        for _ in 0..<20 { await Task.yield() }
        XCTAssertEqual(ssids, ["A"])
        continuations[0].resume(throwing: MotoComError.unavailable("old A"))
        await fulfillment(of: [second], timeout: 2)
        _ = try? await a.value
        XCTAssertEqual(bootstrap.currentHotspot?.ssid, "B")
        XCTAssertEqual(bootstrap.state, .networkPreparing)
        continuations[1].resume(); try await b.value
        XCTAssertEqual(bootstrap.state, .networkReady); XCTAssertNil(bootstrap.currentHotspot)
    }
    func testSamePeerIdentifierDifferentNativeSourceAndInvalidatedDeliveryDoNotClaim() {
        let identifier = UUID(); let run = UUID()
        let a = BLEBootstrapSourceLease(key: BLEBootstrapSourceKey(runID: run, role: .centralClient,
            managerInstanceID: UUID(), peerIdentifier: identifier, peerInstanceID: UUID(), peerLeaseID: UUID()))
        let b = BLEBootstrapSourceLease(key: BLEBootstrapSourceKey(runID: run, role: .centralClient,
            managerInstanceID: UUID(), peerIdentifier: identifier, peerInstanceID: UUID(), peerLeaseID: UUID()))
        var aAcks = 0; var bAcks = 0
        let aReceipt = BLEBootstrapReceipt(source: a, delivery: BLEBootstrapDeliveryLease { aAcks += 1 })
        let bReceipt = BLEBootstrapReceipt(source: b, delivery: BLEBootstrapDeliveryLease { bAcks += 1 })
        a.revoke()
        XCTAssertNil(aReceipt.withCurrent { 1 }); XCTAssertEqual(bReceipt.withCurrent { 2 }, 2)
        aReceipt.delivery.acknowledge(); aReceipt.delivery.acknowledge()
        XCTAssertEqual(aAcks, 1); XCTAssertEqual(bAcks, 0)
        bReceipt.delivery.invalidate(); XCTAssertNil(bReceipt.withCurrent { 3 })
        XCTAssertEqual(bAcks, 0)
        bReceipt.delivery.acknowledge(); XCTAssertEqual(bAcks, 1)
    }
}
