import XCTest
@testable import MotoComIOS

@MainActor
final class ManualSessionClock: SessionDeadlineScheduling {
    final class Token: SessionDeadlineToken {
        var cancelled = false
        func cancel() { cancelled = true }
    }
    var now: TimeInterval = 0
    private var entries = [(TimeInterval, Token, @MainActor () -> Void)]()
    func schedule(at deadline: TimeInterval, _ action: @escaping @MainActor () -> Void) -> SessionDeadlineToken {
        let token = Token(); entries.append((deadline, token, action)); return token
    }
    func advance(to time: TimeInterval) {
        now = time
        let ready = entries.filter { $0.0 <= time }; entries.removeAll { $0.0 <= time }
        for (_, token, action) in ready where !token.cancelled { action() }
    }
}

final class TestRawControlIO: RawControlIO {
    private let lock = NSLock()
    private var receiver: ((Data?, Bool, Error?) -> Void)?
    private var writes = [Data]()
    private var senders = [(Error?) -> Void]()
    private var cancels = 0
    private var writeHandler: ((Data) -> Void)?
    var onWrite: ((Data) -> Void)? {
        get { lock.lock(); defer { lock.unlock() }; return writeHandler }
        set { lock.lock(); writeHandler = newValue; lock.unlock() }
    }
    var sent: [Data] { lock.lock(); defer { lock.unlock() }; return writes }
    var cancelCount: Int { lock.lock(); defer { lock.unlock() }; return cancels }
    func receive(_ completion: @escaping (Data?, Bool, Error?) -> Void) {
        lock.lock(); receiver = completion; lock.unlock()
    }
    func send(_ data: Data, completion: @escaping (Error?) -> Void) {
        lock.lock(); writes.append(data); senders.append(completion); let callback = writeHandler; lock.unlock()
        callback?(data)
    }
    func cancel() { lock.lock(); cancels += 1; lock.unlock() }
    func emit(_ data: Data? = nil, complete: Bool = false, error: Error? = nil) {
        lock.lock(); let callback = receiver; receiver = nil; lock.unlock()
        callback?(data, complete, error)
    }
    func completeSend(_ index: Int, error: Error? = nil) {
        lock.lock(); let callback = senders[index]; lock.unlock(); callback(error)
    }
}

@MainActor
final class ControlLifecycleTests: XCTestCase {
    let localID = "00000000-0000-4000-8000-000000000001"
    let remoteID = "00000000-0000-4000-8000-000000000002"
    let localRuntime = "00000000-0000-4000-8000-000000000011"
    let remoteRuntime = "00000000-0000-4000-8000-000000000012"
    let attempt = "00000000-0000-4000-8000-000000000021"
    func identity() throws -> StableIdentity {
        try StableIdentity(deviceID: localID, sessionID: localRuntime, nickname: "local", deviceName: "iPhone")
    }
    func frame(_ message: SignalingMessage, runtime: String? = nil) throws -> Data {
        try LengthPrefixedFraming.encode(SignalingV2Codec().encode(SignalingEnvelope(
            attemptID: attempt, sourceDeviceID: remoteID, targetDeviceID: localID,
            sourceSessionID: runtime ?? remoteRuntime, message: message)))
    }
    func testNormalEOFThenLateFrameAndSendErrorTerminateOnlyOnce() async throws {
        let raw = TestRawControlIO(); let channel = NWControlChannel(io: raw)
        let controller = try SignalingSessionController(channel: channel, localIdentity: identity(), scheduler: ManualSessionClock())
        let ended = expectation(description: "EOF ends session")
        var terminalCount = 0; var helloCount = 0
        controller.onHello = { _, _, _, _ in helloCount += 1 }
        controller.onTerminated = { _ in terminalCount += 1; ended.fulfill() }
        raw.emit(complete: true)
        await fulfillment(of: [ended], timeout: 2)
        raw.emit(try frame(.hello(requestRole: .requester, nickname: "remote", deviceName: "Android", capabilities: [])))
        controller.sendCandidate(Data("{}".utf8)); controller.close()
        XCTAssertEqual(terminalCount, 1); XCTAssertEqual(helloCount, 0)
        XCTAssertEqual(raw.cancelCount, 1); XCTAssertEqual(controller.phase, .closed)
    }
    func testRequesterRejectBusyAndDisconnectUseSameTerminalPath() async throws {
        for message in [SignalingMessage.connectReject(reason: .userRejected, retryable: false), .busy(reason: "ALREADY_CONNECTED", retryAfterMilliseconds: nil)] {
            let raw = TestRawControlIO(); let controller = try SignalingSessionController(
                channel: NWControlChannel(io: raw), localIdentity: identity(), remoteDeviceID: remoteID,
                attemptID: attempt, expectedRemoteSessionID: remoteRuntime, scheduler: ManualSessionClock())
            controller.onReadyToRequest = { controller.sendConnectRequest() }
            controller.startAsRequester(capabilities: [])
            let hello = expectation(description: "HELLO")
            controller.onHello = { _, _, _, _ in hello.fulfill() }
            raw.emit(try frame(.hello(requestRole: .responder, nickname: "remote", deviceName: "Android", capabilities: [])))
            await fulfillment(of: [hello], timeout: 2)
            let end = expectation(description: "terminal")
            controller.onTerminated = { _ in end.fulfill() }
            raw.emit(try frame(message))
            await fulfillment(of: [end], timeout: 2)
            XCTAssertEqual(controller.phase, .closed); XCTAssertEqual(raw.cancelCount, 1)
        }
    }
    func testAbsoluteHelloDeadlineCannotBeExtendedByPartialBytes() throws {
        let raw = TestRawControlIO(); let clock = ManualSessionClock()
        let controller = try SignalingSessionController(channel: NWControlChannel(io: raw), localIdentity: identity(), scheduler: clock)
        raw.emit(Data([0]))
        clock.advance(to: 10)
        XCTAssertTrue(controller.isTerminated); XCTAssertEqual(raw.cancelCount, 1)
        XCTAssertFalse(controller.accept())
    }
    func testWrongRuntimeAndThirdPartyClaimNeverAccept() async throws {
        let raw = TestRawControlIO(); let clock = ManualSessionClock()
        let controller = try SignalingSessionController(channel: NWControlChannel(io: raw), localIdentity: identity(),
            remoteDeviceID: remoteID, attemptID: attempt, expectedRemoteSessionID: remoteRuntime, scheduler: clock)
        controller.startAsRequester(capabilities: [])
        let end = expectation(description: "wrong runtime closes")
        controller.onTerminated = { _ in end.fulfill() }
        raw.emit(try frame(.hello(requestRole: .responder, nickname: "remote", deviceName: "Android", capabilities: []), runtime: localRuntime))
        await fulfillment(of: [end], timeout: 2)
        XCTAssertFalse(controller.accept())

        let inbound = TestRawControlIO()
        let responder = try SignalingSessionController(channel: NWControlChannel(io: inbound), localIdentity: identity(), scheduler: clock)
        responder.onClaimHello = { _ in false }
        let rejected = expectation(description: "occupied owner rejects")
        responder.onTerminated = { _ in rejected.fulfill() }
        inbound.emit(try frame(.hello(requestRole: .requester, nickname: "remote", deviceName: "Android", capabilities: [])))
        await fulfillment(of: [rejected], timeout: 2)
        XCTAssertFalse(responder.accept()); XCTAssertEqual(responder.phase, .closed)
    }
    func testConfirmationDeadlineAndAcceptOnlyFromActualRequest() async throws {
        let raw = TestRawControlIO(); let clock = ManualSessionClock()
        let controller = try SignalingSessionController(channel: NWControlChannel(io: raw), localIdentity: identity(), scheduler: clock)
        XCTAssertFalse(controller.accept())
        let request = expectation(description: "request")
        controller.onIncomingRequest = { request.fulfill() }
        let hello = try frame(.hello(requestRole: .requester, nickname: "remote", deviceName: "Android", capabilities: []))
        raw.emit(try hello + frame(.connectRequest(trigger: .user, preferredTransportHint: .lan)))
        await fulfillment(of: [request], timeout: 2)
        clock.advance(to: 15)
        XCTAssertTrue(controller.isTerminated); XCTAssertFalse(controller.accept())
    }
    func testResponderWaitingForRequestKeepsOriginalDeadline() async throws {
        let raw = TestRawControlIO(); let clock = ManualSessionClock()
        let controller = try SignalingSessionController(channel: NWControlChannel(io: raw), localIdentity: identity(), scheduler: clock)
        let hello = expectation(description: "first HELLO")
        controller.onHello = { _, _, _, _ in hello.fulfill() }
        raw.emit(try frame(.hello(requestRole: .requester, nickname: "remote", deviceName: "Android", capabilities: [])))
        await fulfillment(of: [hello], timeout: 2)
        XCTAssertEqual(controller.phase, .awaitingConnectRequest)
        clock.advance(to: 10)
        XCTAssertTrue(controller.isTerminated)
    }
    func testLocalWinningGlareDoesNotDropOriginalHelloDeadline() async throws {
        let raw = TestRawControlIO(); let clock = ManualSessionClock()
        let controller = try SignalingSessionController(channel: NWControlChannel(io: raw), localIdentity: identity(), remoteDeviceID: remoteID,
            attemptID: "00000000-0000-4000-8000-000000000020", expectedRemoteSessionID: remoteRuntime, scheduler: clock)
        controller.startAsRequester(capabilities: [])
        let hello = expectation(description: "requester glare")
        controller.onHello = { _, _, _, _ in hello.fulfill() }
        raw.emit(try frame(.hello(requestRole: .requester, nickname: "remote", deviceName: "Android", capabilities: [])))
        await fulfillment(of: [hello], timeout: 2)
        XCTAssertEqual(controller.phase, .requesterHelloSent)
        clock.advance(to: 10)
        XCTAssertTrue(controller.isTerminated); XCTAssertFalse(controller.accept())
    }
}
