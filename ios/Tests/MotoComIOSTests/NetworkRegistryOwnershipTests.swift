import XCTest
import Network
@testable import MotoComIOS

final class TestNetworkDriver: NetworkConnectionDriving {
    let connection = NWConnection(host: "localhost", port: 9, using: .tcp)
    var onState: ((NetworkConnectionState) -> Void)?
    private(set) var cancelled = false
    private(set) var started = false
    func start(queue: DispatchQueue) { started = true }
    func cancel() { cancelled = true }
}

final class NetworkRegistryOwnershipTests: XCTestCase {
    func testPendingRegistrationStopAndLateActualDriverCallbackCannotReviveOrClearB() {
        let a = TestNetworkDriver(); let b = TestNetworkDriver(); var factoryIndex = 0
        let core = NetworkTransportCore(label: "registry-test") { _, _ in
            defer { factoryIndex += 1 }; return factoryIndex == 0 ? a : b
        }
        var aCalls = 0; var bCalls = 0; var bSuccess = false
        let aLease = core.connect(to: .hostPort(host: "localhost", port: 9), includePeerToPeer: false) { _ in aCalls += 1 }
        let lateA = a.onState
        XCTAssertTrue(a.started)
        core.stop()
        XCTAssertTrue(a.cancelled); XCTAssertEqual(aCalls, 1)
        let bLease = core.connect(to: .hostPort(host: "localhost", port: 9), includePeerToPeer: false) { result in
            bCalls += 1; if case .success(let connection) = result { bSuccess = connection === b.connection }
        }
        lateA?(.ready); lateA?(.failed(MotoComError.unavailable("old A"))); aLease.cancel()
        b.onState?(.ready); core.owned {}
        XCTAssertEqual(aCalls, 1); XCTAssertEqual(bCalls, 1); XCTAssertTrue(bSuccess); XCTAssertFalse(b.cancelled)
        b.onState?(.failed(MotoComError.unavailable("EOF after handoff"))); core.owned {}
        XCTAssertEqual(bCalls, 1); XCTAssertTrue(b.cancelled)
        bLease.cancel(); core.stop()
    }
    func testExactPendingCancellationPreservesAdmissionAndNewDial() {
        var drivers = [TestNetworkDriver](); var callbacks = [Int: Int]()
        let core = NetworkTransportCore(label: "registry-admission") { _, _ in
            let driver = TestNetworkDriver(); drivers.append(driver); return driver
        }
        var leases = [IOSConnectionCancellation]()
        for index in 0..<12 {
            if let prior = leases.last { prior.cancel() }
            let lease = core.connect(to: .hostPort(host: "localhost", port: 9), includePeerToPeer: false) { _ in callbacks[index, default: 0] += 1 }
            leases.append(lease)
        }
        XCTAssertEqual(drivers.count, 12)
        XCTAssertTrue(drivers.dropLast().allSatisfy { $0.cancelled })
        XCTAssertFalse(drivers[11].cancelled); XCTAssertTrue(drivers[11].started)
        let late = drivers[0].onState
        leases[0].cancel(); late?(.ready); core.owned {}
        XCTAssertFalse(drivers[11].cancelled)
        drivers[11].onState?(.ready); core.owned {}
        XCTAssertEqual(callbacks[11], 1)
        core.stop()
    }
}
