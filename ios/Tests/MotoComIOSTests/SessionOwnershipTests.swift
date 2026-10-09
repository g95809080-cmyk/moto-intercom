import XCTest
import Network
@testable import MotoComIOS

@MainActor
final class TestAudioDriver: AudioSessionDriving {
    var currentRoute: AudioRoute = .phoneSpeaker
    var active = false
    var permission: CheckedContinuation<Bool, Never>?
    var permissionStarted: XCTestExpectation?
    func requestMicrophonePermission() async -> Bool {
        guard let started = permissionStarted else { return true }
        return await withCheckedContinuation { permission = $0; started.fulfill() }
    }
    func activate() throws { active = true }
    func deactivate() { active = false }
}

final class TestWebRTCEngine: WebRTCEngine {
    var onStateChanged: ((WebRTCMediaState) -> Void)?
    var onLocalOffer: ((String) -> Void)?
    var onLocalAnswer: ((String) -> Void)?
    var onLocalCandidate: ((String) -> Void)?
    var onRemoteAudioTrack: (() -> Void)?
    var onRemoteAudioFrame: (() -> Void)?
    struct Run {
        let state: ((WebRTCMediaState) -> Void)?
        let offer: ((String) -> Void)?
        let answer: ((String) -> Void)?
        let candidate: ((String) -> Void)?
        let track: (() -> Void)?
        let frame: (() -> Void)?
    }
    var runs = [Run]()
    var closeCount = 0
    var onStart: (() -> Void)?
    var onRemoteAnswer: (() -> Void)?
    func start(configuration: WebRTCSessionConfiguration, offerer: Bool) throws {
        runs.append(Run(state: onStateChanged, offer: onLocalOffer, answer: onLocalAnswer,
            candidate: onLocalCandidate, track: onRemoteAudioTrack, frame: onRemoteAudioFrame))
        onStart?()
    }
    func setRemoteOffer(_ sdpJSON: String) throws {}
    func setRemoteAnswer(_ sdpJSON: String) throws { onRemoteAnswer?() }
    func addRemoteCandidate(_ candidateJSON: String) throws {}
    func setAudioEnabled(_ enabled: Bool) {}
    func close() { closeCount += 1; runs.last?.state?(.closed) }
}

final class TestControlTransport: IOSControlTransport {
    var onPeerFound: ((NWEndpoint, BonjourServiceAdvertisement?) -> Void)?
    var onConnection: ((NWConnection) -> Void)?
    var onError: ((Error) -> Void)?
    var completions = [(Result<NWConnection, Error>) -> Void]()
    var starts = 0; var stops = 0
    var onStart: (() -> Void)?
    var cancelled = 0
    func start(advertisement: BonjourServiceAdvertisement) throws { starts += 1; onStart?() }
    func startBrowsing() {}
    func connect(to endpoint: NWEndpoint, includePeerToPeer: Bool, completion: @escaping (Result<NWConnection, Error>) -> Void) -> IOSConnectionCancellation {
        completions.append(completion)
        return NetworkDialCancellation { [weak self] in self?.cancelled += 1 }
    }
    func stop() { stops += 1 }
}

actor TestPairingStore: PairingStoring {
    var records = [PairingRecord]()
    var saves = [(PairingRecord, CheckedContinuation<Void, Error>)]()
    let saved: XCTestExpectation?
    init(saved: XCTestExpectation? = nil) { self.saved = saved }
    func all() -> [PairingRecord] { records }
    func saveConnectedPeer(_ record: PairingRecord, audioReady: Bool, transport: String) async throws {
        try await withCheckedThrowingContinuation { continuation in
            saves.append((record, continuation)); saved?.fulfill()
        }
        records.append(record)
    }
    func finish(_ index: Int, error: Error? = nil) {
        if let error { saves[index].1.resume(throwing: error) } else { saves[index].1.resume() }
    }
    var saveCount: Int { saves.count }
}

final class TestBLESource: BLEBootstrapSource {
    var runs = [(UUID, @Sendable (BLEBootstrapEvent) -> Void)]()
    var sources = [BLEBootstrapSourceLease]()
    func start(runID: UUID, announcement: BootstrapAnnouncement, events: @escaping @Sendable (BLEBootstrapEvent) -> Void) { runs.append((runID, events)) }
    func stop(runID: UUID) { for source in sources where source.key.runID == runID { source.revoke() } }
    func send(_ message: BootstrapMessage, to source: BLEBootstrapSourceKey, completion: @escaping @Sendable (Result<Void, BLEBootstrapSendFailure>) -> Void) { completion(.success(())) }
    func source(in index: Int, identifier: UUID = UUID()) -> BLEBootstrapSourceLease {
        let source = BLEBootstrapSourceLease(key: BLEBootstrapSourceKey(runID: runs[index].0, role: .centralClient,
            managerInstanceID: UUID(), peerIdentifier: identifier, peerInstanceID: UUID(), peerLeaseID: UUID()))
        sources.append(source); return source
    }
}

@MainActor
final class TestPathDriver: NetworkPathDriving {
    var callbacks = [@Sendable (Bool, TimeInterval) -> Void]()
    func start(_ callback: @escaping @Sendable (Bool, TimeInterval) -> Void) { callbacks.append(callback) }
    func stop() {}
}

@MainActor
final class SessionOwnershipTests: XCTestCase {
    let localID = "00000000-0000-4000-8000-000000000001"
    let localRuntime = "00000000-0000-4000-8000-000000000011"
    let remoteID = "00000000-0000-4000-8000-000000000002"
    let remoteRuntime = "00000000-0000-4000-8000-000000000012"
    let attemptID = "00000000-0000-4000-8000-000000000021"
    let sdp = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=rtpmap:111 opus/48000/2\r\n"
    func identity() throws -> StableIdentity { try StableIdentity(deviceID: localID, sessionID: localRuntime, nickname: "local", deviceName: "iPhone") }
    func capabilities() throws -> RuntimeCapabilities { try RuntimeCapabilities(platform: .ios, platformVersion: "16", deviceName: "iPhone", capabilities: [.lan, .bleBootstrap, .wifiJoin, .iosPeerToPeer]) }
    func remote(_ device: String? = nil, runtime: String? = nil) throws -> NearbyPeer {
        try NearbyPeer(deviceID: device ?? remoteID, sessionID: runtime ?? remoteRuntime, nickname: "remote", deviceName: "remote", capabilities: capabilities())
    }
    func makeSession(engine: TestWebRTCEngine = TestWebRTCEngine(), audio: TestAudioDriver? = nil,
        store: PairingStoring = TestPairingStore(), clock: ManualSessionClock? = nil,
        transport: TestControlTransport = TestControlTransport(), ble: BLEBootstrapSource? = nil,
        bootstrap: NetworkBootstrapCoordinator? = nil,
        pathDriver: TestPathDriver? = nil,
        identityOverride: StableIdentity? = nil,
        factory: @escaping (NWConnection) -> NWControlChannel = { NWControlChannel(connection: $0) }) throws -> SessionCoordinator {
        try SessionCoordinator(pairingStore: store, audio: AudioSessionController(driver: audio ?? TestAudioDriver()), webRTCEngine: engine,
            networkBootstrap: bootstrap, scheduler: clock ?? ManualSessionClock(), bleSource: ble, initialIdentity: identityOverride ?? identity(), initialCapabilities: capabilities(),
            bonjourTransport: transport, peerToPeerTransport: TestControlTransport(), channelFactory: factory, pathDriver: pathDriver ?? TestPathDriver(), settingsOpener: {})
    }
    func frame(_ message: SignalingMessage, attempt: String? = nil, device: String? = nil, runtime: String? = nil, target: String? = nil) throws -> Data {
        try LengthPrefixedFraming.encode(SignalingV2Codec().encode(SignalingEnvelope(attemptID: attempt ?? attemptID,
            sourceDeviceID: device ?? remoteID, targetDeviceID: target ?? localID, sourceSessionID: runtime ?? remoteRuntime, message: message)))
    }
    func attach(_ session: SessionCoordinator, raw: TestRawControlIO, clock: ManualSessionClock, attempt: String? = nil) throws -> SignalingSessionController {
        let controller = try SignalingSessionController(channel: NWControlChannel(io: raw), localIdentity: identity(),
            remoteDeviceID: remoteID, attemptID: attempt ?? attemptID, expectedRemoteSessionID: remoteRuntime,
            autoStart: false, scheduler: clock)
        session.attachSignaling(controller, remote: try capabilities())
        controller.startAsRequester(capabilities: []); controller.start()
        return controller
    }
    func establish(_ session: SessionCoordinator, raw: TestRawControlIO, engine: TestWebRTCEngine, controller: SignalingSessionController) async throws {
        let requestSent = expectation(description: "real CONNECT_REQUEST")
        raw.onWrite = { data in
            var decoder = LengthPrefixedFrameDecoder()
            if let payload = try? decoder.append(data).first,
               let envelope = try? SignalingV2Codec().decode(payload), case .connectRequest = envelope.message { requestSent.fulfill() }
        }
        raw.emit(try frame(.hello(requestRole: .responder, nickname: "remote", deviceName: "remote", capabilities: []), attempt: controller.attemptID))
        await fulfillment(of: [requestSent], timeout: 2)
        raw.onWrite = nil
        let started = expectation(description: "actual engine start")
        engine.onStart = { started.fulfill() }
        raw.emit(try frame(.connectAccept(nickname: "remote", deviceName: "remote"), attempt: controller.attemptID))
        await fulfillment(of: [started], timeout: 2)
        engine.onStart = nil
        XCTAssertFalse(engine.runs.isEmpty)
        let offerSent = expectation(description: "real OFFER")
        raw.onWrite = { data in
            var decoder = LengthPrefixedFrameDecoder()
            if let payload = try? decoder.append(data).first,
               let envelope = try? SignalingV2Codec().decode(payload), case .offer = envelope.message { offerSent.fulfill() }
        }
        engine.runs.last?.offer?(try WebRTCSignalingCodec.encodeSessionDescription(type: "offer", sdp: sdp))
        await fulfillment(of: [offerSent], timeout: 2)
        raw.onWrite = nil
        let answered = expectation(description: "actual engine remote answer")
        engine.onRemoteAnswer = { answered.fulfill() }
        raw.emit(try frame(.answer(sdpJSON: WebRTCSignalingCodec.encodeSessionDescription(type: "answer", sdp: sdp)), attempt: controller.attemptID))
        await fulfillment(of: [answered], timeout: 2)
        engine.onRemoteAnswer = nil
        XCTAssertEqual(controller.phase, .mediaNegotiating)
    }
    func testConnectedIsSynchronousAndDuplicateRouteCannotDowngradeWhileSaveIsSuspended() async throws {
        let save = expectation(description: "actual pairing save"); let store = TestPairingStore(saved: save)
        let clock = ManualSessionClock(); let raw = TestRawControlIO(); let engine = TestWebRTCEngine(); let driver = TestAudioDriver()
        let session = try makeSession(engine: engine, audio: driver, store: store, clock: clock)
        let controller = try attach(session, raw: raw, clock: clock)
        try await establish(session, raw: raw, engine: engine, controller: controller)
        let run = try XCTUnwrap(engine.runs.last)
        run.state?(.connected); run.track?(); run.frame?()
        await fulfillment(of: [save], timeout: 2)
        XCTAssertEqual(session.phase, .connected)
        for _ in 0..<3 { session.refreshAudioRoute(); run.frame?() }
        for _ in 0..<20 { await Task.yield() }
        XCTAssertEqual(session.phase, .connected)
        let count = await store.saveCount; XCTAssertEqual(count, 1)
        session.stop(); await store.finish(0)
        for _ in 0..<20 { await Task.yield() }
        XCTAssertEqual(session.phase, .offline); XCTAssertFalse(driver.active)
    }
    func testOldPairingErrorAndEngineEventsCannotEndB() async throws {
        let save = expectation(description: "A pairing save"); let store = TestPairingStore(saved: save)
        let clock = ManualSessionClock(); let aRaw = TestRawControlIO(); let engine = TestWebRTCEngine()
        let session = try makeSession(engine: engine, store: store, clock: clock)
        let a = try attach(session, raw: aRaw, clock: clock)
        try await establish(session, raw: aRaw, engine: engine, controller: a)
        let old = try XCTUnwrap(engine.runs.last)
        old.state?(.connected); old.track?(); old.frame?()
        await fulfillment(of: [save], timeout: 2)
        session.stop()
        let bRaw = TestRawControlIO()
        let b = try attach(session, raw: bRaw, clock: clock, attempt: "00000000-0000-4000-8000-000000000022")
        try await establish(session, raw: bRaw, engine: engine, controller: b)
        old.state?(.failed); old.track?(); old.frame?(); old.offer?("old SDP"); old.answer?("old answer"); old.candidate?("old candidate")
        await store.finish(0, error: MotoComError.storageFailure("old A"))
        for _ in 0..<20 { await Task.yield() }
        XCTAssertFalse(b.isTerminated); XCTAssertEqual(b.phase, .mediaNegotiating)
        XCTAssertFalse(session.audio.readiness.remoteTrackPresent)
        XCTAssertFalse(session.statusMessage.contains("old A")); session.stop()
    }
    func testRemoteDisconnectAndNormalEOFCloseActualMedia() async throws {
        for eof in [false, true] {
            let clock = ManualSessionClock(); let raw = TestRawControlIO(); let engine = TestWebRTCEngine(); let driver = TestAudioDriver()
            let session = try makeSession(engine: engine, audio: driver, clock: clock)
            let controller = try attach(session, raw: raw, clock: clock)
            try await establish(session, raw: raw, engine: engine, controller: controller)
            let end = expectation(description: "real controller terminal")
            let original = controller.onTerminated
            controller.onTerminated = { error in original?(error); end.fulfill() }
            let closes = engine.closeCount
            if eof { raw.emit(complete: true) } else { raw.emit(try frame(.disconnect(reason: "USER_CANCELED"))) }
            await fulfillment(of: [end], timeout: 2)
            XCTAssertEqual(session.phase, .offline); XCTAssertFalse(driver.active); XCTAssertGreaterThan(engine.closeCount, closes)
            session.stop()
        }
    }
    func testPausedPermissionAfterStopCannotStartDiscovery() async throws {
        let driver = TestAudioDriver(); let started = expectation(description: "permission suspended"); driver.permissionStarted = started
        let transport = TestControlTransport(); let session = try makeSession(audio: driver, transport: transport)
        let task = Task { await session.startDiscoveryWithPermission() }
        await fulfillment(of: [started], timeout: 2)
        session.stop(); driver.permission?.resume(returning: true)
        await task.value
        XCTAssertEqual(session.phase, .offline); XCTAssertEqual(transport.starts, 0)
    }
    func testActualTransportACompletionQueuedBeforeStopCannotAttachToB() async throws {
        let transport = TestControlTransport(); let clock = ManualSessionClock()
        let aConnection = NWConnection(host: "localhost", port: 9, using: .tcp)
        let bConnection = NWConnection(host: "localhost", port: 9, using: .tcp)
        let bAttached = expectation(description: "B channel factory")
        var attached = [ObjectIdentifier]()
        let session = try makeSession(clock: clock, transport: transport) { connection in
            attached.append(ObjectIdentifier(connection)); if connection === bConnection { bAttached.fulfill() }
            return NWControlChannel(io: TestRawControlIO())
        }
        session.startDiscovery()
        let advertisement = try BonjourServiceAdvertisement(deviceID: remoteID, sessionID: remoteRuntime, nickname: "remote", deviceName: "remote", capabilities: [.lan])
        transport.onPeerFound?(.hostPort(host: "localhost", port: 8890), advertisement)
        for _ in 0..<30 where session.nearbyPeers.isEmpty { await Task.yield() }
        session.connect(to: try remote())
        let aCompletion = try XCTUnwrap(transport.completions.first)
        aCompletion(.success(aConnection))
        session.stop(); session.startDiscovery()
        transport.onPeerFound?(.hostPort(host: "localhost", port: 8890), advertisement)
        for _ in 0..<30 where session.nearbyPeers.isEmpty { await Task.yield() }
        session.connect(to: try remote())
        let bCompletion = try XCTUnwrap(transport.completions.last)
        bCompletion(.success(bConnection))
        await fulfillment(of: [bAttached], timeout: 2)
        XCTAssertEqual(attached, [ObjectIdentifier(bConnection)])
        XCTAssertEqual(transport.cancelled, 1)
        session.stop()
        XCTAssertEqual(transport.cancelled, 2)
    }
    func testQueuedPathLossBeforeBAndPriorCommandCannotTerminateB() async throws {
        let transport = TestControlTransport(); let path = TestPathDriver(); let clock = ManualSessionClock()
        let session = try makeSession(clock: clock, transport: transport, pathDriver: path)
        session.startDiscovery()
        let ad = try BonjourServiceAdvertisement(deviceID: remoteID, sessionID: remoteRuntime, nickname: "remote", deviceName: "remote", capabilities: [.lan])
        transport.onPeerFound?(.hostPort(host: "localhost", port: 8890), ad)
        for _ in 0..<30 where session.nearbyPeers.isEmpty { await Task.yield() }
        let old = try XCTUnwrap(path.callbacks.last)
        old(false, ProcessInfo.processInfo.systemUptime)
        session.connect(to: try remote())
        let current = try XCTUnwrap(path.callbacks.last)
        current(false, 0) // observation predates actual B creation
        for _ in 0..<20 { await Task.yield() }
        XCTAssertEqual(session.phase, .signaling)
        current(false, ProcessInfo.processInfo.systemUptime)
        for _ in 0..<20 where session.phase == .signaling { await Task.yield() }
        XCTAssertEqual(session.phase, .recovering)
        XCTAssertEqual(transport.cancelled, 1); session.stop()
    }
    func testHotspotSourceRevokedBeforeMainDoesNotApplyAndExactDeliveryIsAcknowledged() async throws {
        let source = TestBLESource(); var joins = 0
        let bootstrap = NetworkBootstrapCoordinator { _ in joins += 1 }
        let session = try makeSession(ble: source, bootstrap: bootstrap)
        session.startDiscovery()
        let lease = source.source(in: 0); let acknowledged = expectation(description: "exact delivery ack")
        let receipt = BLEBootstrapReceipt(source: lease, delivery: BLEBootstrapDeliveryLease { acknowledged.fulfill() })
        let remote = try BootstrapAnnouncement(platform: .android, deviceID: remoteID, sessionID: remoteRuntime, capabilities: [.lan], networkRole: .host)
        source.runs[0].1(.message(receipt, BootstrapMessage(type: .capabilities, announcement: remote)))
        lease.revoke() // before queued Main claim
        await fulfillment(of: [acknowledged], timeout: 2)
        XCTAssertTrue(session.nearbyPeers.isEmpty); XCTAssertEqual(joins, 0)
        session.stop()
    }
    func testIncomingThirdDeviceCannotReplaceActualOwnerAndLegitimateSiblingInheritsDeadline() async throws {
        let transport = TestControlTransport(); let clock = ManualSessionClock(); let engine = TestWebRTCEngine()
        let candidateConnection = NWConnection(host: "localhost", port: 9, using: .tcp)
        let candidateRaw = TestRawControlIO(); let installed = expectation(description: "candidate channel")
        let session = try makeSession(engine: engine, clock: clock, transport: transport) { _ in installed.fulfill(); return NWControlChannel(io: candidateRaw) }
        session.startDiscovery()
        let parentRaw = TestRawControlIO(); let parent = try attach(session, raw: parentRaw, clock: clock)
        transport.onConnection?(candidateConnection)
        await fulfillment(of: [installed], timeout: 2)
        let busy = expectation(description: "third device BUSY")
        candidateRaw.onWrite = { data in
            var decoder = LengthPrefixedFrameDecoder()
            if let bytes = try? decoder.append(data).first, let envelope = try? SignalingV2Codec().decode(bytes), case .busy = envelope.message { busy.fulfill() }
        }
        candidateRaw.emit(try frame(.hello(requestRole: .requester, nickname: "third", deviceName: "third", capabilities: []), device: "00000000-0000-4000-8000-000000000003"))
        await fulfillment(of: [busy], timeout: 2)
        XCTAssertFalse(parent.isTerminated); XCTAssertTrue(engine.runs.isEmpty)
        session.stop()

        let siblingTransport = TestControlTransport(); let siblingRaw = TestRawControlIO()
        let childReady = expectation(description: "sibling channel")
        let next = try makeSession(engine: engine, clock: clock, transport: siblingTransport) { _ in childReady.fulfill(); return NWControlChannel(io: siblingRaw) }
        next.startDiscovery(); let old = try attach(next, raw: TestRawControlIO(), clock: clock)
        clock.advance(to: 9)
        let reply = expectation(description: "actual smaller child wins")
        siblingRaw.onWrite = { data in
            var decoder = LengthPrefixedFrameDecoder()
            if let bytes = try? decoder.append(data).first, let envelope = try? SignalingV2Codec().decode(bytes),
               case .hello(let role, _, _, _) = envelope.message, role == .responder,
               envelope.attemptID == "00000000-0000-4000-8000-000000000019" { reply.fulfill() }
        }
        siblingTransport.onConnection?(NWConnection(host: "localhost", port: 9, using: .tcp))
        await fulfillment(of: [childReady], timeout: 2)
        siblingRaw.emit(try frame(.hello(requestRole: .requester, nickname: "remote", deviceName: "remote", capabilities: []), attempt: "00000000-0000-4000-8000-000000000019"))
        await fulfillment(of: [reply], timeout: 2)
        XCTAssertTrue(old.isTerminated); XCTAssertTrue(engine.runs.isEmpty)
        clock.advance(to: 10)
        XCTAssertTrue(next.phase == .failed || next.phase == .offline)
        XCTAssertTrue(engine.runs.isEmpty); next.stop()
    }
    func testSameSocketChildAttemptIsUsedForLaterSiblingComparison() async throws {
        let transport = TestControlTransport(); let clock = ManualSessionClock()
        let siblingRaw = TestRawControlIO(); let installed = expectation(description: "next sibling channel")
        let session = try makeSession(clock: clock, transport: transport) { _ in installed.fulfill(); return NWControlChannel(io: siblingRaw) }
        session.startDiscovery(); let raw = TestRawControlIO(); let controller = try attach(session, raw: raw, clock: clock)
        let child = "00000000-0000-4000-8000-000000000019"
        let response = expectation(description: "same socket becomes child")
        raw.onWrite = { data in
            var decoder = LengthPrefixedFrameDecoder()
            if let bytes = try? decoder.append(data).first, let envelope = try? SignalingV2Codec().decode(bytes),
               envelope.attemptID == child, case .hello(let role, _, _, _) = envelope.message, role == .responder { response.fulfill() }
        }
        raw.emit(try frame(.hello(requestRole: .requester, nickname: "remote", deviceName: "remote", capabilities: []), attempt: child))
        await fulfillment(of: [response], timeout: 2)
        transport.onConnection?(NWConnection(host: "localhost", port: 9, using: .tcp))
        await fulfillment(of: [installed], timeout: 2)
        let busy = expectation(description: "intermediate sibling loses to actual child")
        siblingRaw.onWrite = { data in
            var decoder = LengthPrefixedFrameDecoder()
            if let bytes = try? decoder.append(data).first, let envelope = try? SignalingV2Codec().decode(bytes), case .busy = envelope.message { busy.fulfill() }
        }
        siblingRaw.emit(try frame(.hello(requestRole: .requester, nickname: "remote", deviceName: "remote", capabilities: []), attempt: "00000000-0000-4000-8000-000000000020"))
        await fulfillment(of: [busy], timeout: 2)
        XCTAssertEqual(controller.attemptID, child); XCTAssertFalse(controller.isTerminated)
        session.stop()
    }
    func testReplayedSameChildWireCannotReplaceRemoteRequesterOwner() async throws {
        let biggerLocal = "00000000-0000-4000-8000-000000000004"
        let local = try StableIdentity(deviceID: biggerLocal, sessionID: localRuntime, nickname: "local", deviceName: "iPhone")
        let transport = TestControlTransport(); let clock = ManualSessionClock(); let siblingRaw = TestRawControlIO()
        let installed = expectation(description: "replay channel")
        let session = try makeSession(clock: clock, transport: transport, identityOverride: local) { _ in installed.fulfill(); return NWControlChannel(io: siblingRaw) }
        session.startDiscovery(); let raw = TestRawControlIO()
        let controller = try SignalingSessionController(channel: NWControlChannel(io: raw), localIdentity: local,
            remoteDeviceID: remoteID, attemptID: attemptID, expectedRemoteSessionID: remoteRuntime, autoStart: false, scheduler: clock)
        session.attachSignaling(controller, remote: try capabilities()); controller.startAsRequester(capabilities: []); controller.start()
        let child = "00000000-0000-4000-8000-000000000019"
        let response = expectation(description: "remote owns child")
        raw.onWrite = { data in
            var decoder = LengthPrefixedFrameDecoder()
            if let bytes = try? decoder.append(data).first, let envelope = try? SignalingV2Codec().decode(bytes),
               envelope.attemptID == child, case .hello(let role, _, _, _) = envelope.message, role == .responder { response.fulfill() }
        }
        raw.emit(try frame(.hello(requestRole: .requester, nickname: "remote", deviceName: "remote", capabilities: []), attempt: child, target: biggerLocal))
        await fulfillment(of: [response], timeout: 2)
        XCTAssertEqual(controller.currentWireKey, [child, remoteID, remoteRuntime, biggerLocal])
        transport.onConnection?(NWConnection(host: "localhost", port: 9, using: .tcp))
        await fulfillment(of: [installed], timeout: 2)
        let busy = expectation(description: "same wire replay rejected")
        siblingRaw.onWrite = { data in
            var decoder = LengthPrefixedFrameDecoder()
            if let bytes = try? decoder.append(data).first, let envelope = try? SignalingV2Codec().decode(bytes), case .busy = envelope.message { busy.fulfill() }
        }
        siblingRaw.emit(try frame(.hello(requestRole: .requester, nickname: "remote", deviceName: "remote", capabilities: []), attempt: child, target: biggerLocal))
        await fulfillment(of: [busy], timeout: 2)
        XCTAssertFalse(controller.isTerminated); XCTAssertEqual(controller.attemptID, child); session.stop()
    }
    func testSameCommandManualDiscoveryRestartRetiresExactPathProducer() async throws {
        let transport = TestControlTransport(); let path = TestPathDriver(); let raw = TestRawControlIO(); let ble = TestBLESource()
        let installed = expectation(description: "inbound channel after manual restart")
        let session = try makeSession(transport: transport, ble: ble, pathDriver: path) { _ in installed.fulfill(); return NWControlChannel(io: raw) }
        let started = expectation(description: "host discovery")
        transport.onStart = { started.fulfill() }
        session.prepareIOSHost()
        await fulfillment(of: [started], timeout: 2)
        transport.onStart = nil
        XCTAssertEqual(session.phase, .manualActionRequired)
        ble.runs[0].1(.state(ble.runs[0].0, .permissionBlocked(.unsupported)))
        for _ in 0..<20 { await Task.yield() }
        XCTAssertEqual(session.phase, .manualActionRequired)
        let old = try XCTUnwrap(path.callbacks.last)
        session.finishManualNetworkSetup()
        ble.runs[1].1(.state(ble.runs[1].0, .permissionBlocked(.unsupported)))
        for _ in 0..<20 { await Task.yield() }
        XCTAssertEqual(session.phase, .networkReady)
        let current = try XCTUnwrap(path.callbacks.last)
        let response = expectation(description: "inbound verified HELLO")
        raw.onWrite = { data in
            var decoder = LengthPrefixedFrameDecoder()
            if let bytes = try? decoder.append(data).first, let envelope = try? SignalingV2Codec().decode(bytes), case .hello = envelope.message { response.fulfill() }
        }
        transport.onConnection?(NWConnection(host: "localhost", port: 9, using: .tcp))
        await fulfillment(of: [installed], timeout: 2)
        raw.emit(try frame(.hello(requestRole: .requester, nickname: "remote", deviceName: "remote", capabilities: [])) + frame(.connectRequest(trigger: .user, preferredTransportHint: .lan)))
        await fulfillment(of: [response], timeout: 2)
        for _ in 0..<20 where session.phase != .awaitingConfirmation { await Task.yield() }
        old(false, ProcessInfo.processInfo.systemUptime)
        for _ in 0..<20 { await Task.yield() }
        XCTAssertEqual(session.phase, .awaitingConfirmation)
        current(false, ProcessInfo.processInfo.systemUptime)
        for _ in 0..<20 where session.phase != .recovering { await Task.yield() }
        XCTAssertEqual(session.phase, .recovering); session.stop()
    }
    func testJoinedOperationSurvivesExpectedBLERunRetirementButStopRejectsOldCompletion() async throws {
        let source = TestBLESource(); let joined = expectation(description: "actual join suspended")
        var continuation: CheckedContinuation<Void, Error>?
        let bootstrap = NetworkBootstrapCoordinator { _ in
            try await withCheckedThrowingContinuation { continuation = $0; joined.fulfill() }
        }
        let session = try makeSession(ble: source, bootstrap: bootstrap)
        session.startDiscovery(); let lease = source.source(in: 0)
        let announcement = try BootstrapAnnouncement(platform: .android, deviceID: remoteID, sessionID: remoteRuntime, capabilities: [.lan], networkRole: .host)
        let announced = expectation(description: "capabilities ack")
        source.runs[0].1(.message(BLEBootstrapReceipt(source: lease, delivery: BLEBootstrapDeliveryLease { announced.fulfill() }), BootstrapMessage(type: .capabilities, announcement: announcement)))
        await fulfillment(of: [announced], timeout: 2)
        let credentials = try HotspotCredentials(ssid: "test-network", security: "WPA2", password: "test-only-password")
        source.runs[0].1(.message(BLEBootstrapReceipt(source: lease, delivery: BLEBootstrapDeliveryLease {}), BootstrapMessage(type: .hotspotReady, hotspot: credentials)))
        await fulfillment(of: [joined], timeout: 2)
        lease.revoke() // after synchronous transfer, not a product command
        continuation?.resume()
        for _ in 0..<50 where session.phase != .networkReady { await Task.yield() }
        XCTAssertEqual(session.phase, .networkReady); XCTAssertEqual(source.runs.count, 2)
        session.stop()

        let waiting = expectation(description: "next join")
        var oldContinuation: CheckedContinuation<Void, Error>?
        let lateBootstrap = NetworkBootstrapCoordinator { _ in
            try await withCheckedThrowingContinuation { oldContinuation = $0; waiting.fulfill() }
        }
        let otherSource = TestBLESource(); let other = try makeSession(ble: otherSource, bootstrap: lateBootstrap)
        other.startDiscovery(); let otherLease = otherSource.source(in: 0)
        let ack = expectation(description: "other capabilities ack")
        otherSource.runs[0].1(.message(BLEBootstrapReceipt(source: otherLease, delivery: BLEBootstrapDeliveryLease { ack.fulfill() }), BootstrapMessage(type: .capabilities, announcement: announcement)))
        await fulfillment(of: [ack], timeout: 2)
        otherSource.runs[0].1(.message(BLEBootstrapReceipt(source: otherLease, delivery: BLEBootstrapDeliveryLease {}), BootstrapMessage(type: .hotspotReady, hotspot: credentials)))
        await fulfillment(of: [waiting], timeout: 2)
        other.stop(); oldContinuation?.resume(throwing: MotoComError.unavailable("old hotspot"))
        for _ in 0..<30 { await Task.yield() }
        XCTAssertEqual(other.phase, .offline); XCTAssertFalse(other.statusMessage.contains("old hotspot"))
    }
}
