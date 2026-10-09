import Foundation
import Combine
#if canImport(UIKit)
import UIKit
#endif
#if canImport(Network)
import Network
#endif

@MainActor
public final class SessionCoordinator: ObservableObject {
    @Published public private(set) var phase: SessionPhase = .idle
    @Published public private(set) var statusMessage = "等待开始"
    @Published public private(set) var identity: StableIdentity?
    @Published public private(set) var runtimeCapabilities: RuntimeCapabilities?
    @Published public private(set) var pairings = [PairingRecord]()
    @Published public private(set) var remoteCapabilities: RuntimeCapabilities?
    @Published public private(set) var nearbyPeers = [NearbyPeer]()
    public let audio: AudioSessionController
    public let networkBootstrap: NetworkBootstrapCoordinator
    public let identityStore: StableIdentityStore
    public let pairingStore: PairingStoring
    public let webRTC: WebRTCSessionCoordinator
    private let scheduler: SessionDeadlineScheduling
    private var command: UInt64 = 0
    private var discoveryRun: UUID?
    private var selectedTarget: (device: String, runtime: String)?
    private var bootstrapOperation: UUID?
    private let bleSource: BLEBootstrapSource?
    private var bleRun: UUID?
    private var announcements = [BLEBootstrapSourceKey: BootstrapAnnouncement]()
    #if canImport(CoreBluetooth)
    private let legacyBLE = BLEBootstrapCoordinator()
    #endif
    #if canImport(Network)
    private final class Attempt {
        let id = UUID()
        let command: UInt64
        let device: String
        let runtime: String?
        let wireAttempt: String
        let deadline: TimeInterval
        let createdAt = ProcessInfo.processInfo.systemUptime
        var controller: SignalingSessionController?
        var dial: IOSConnectionCancellation?
        var timeout: SessionDeadlineToken?
        var transport: NetworkPath
        var helloVerified = false
        var mediaStarted = false
        var connected = false
        init(command: UInt64, device: String, runtime: String?, wireAttempt: String, deadline: TimeInterval, transport: NetworkPath) {
            self.command = command; self.device = device; self.runtime = runtime
            self.wireAttempt = wireAttempt; self.deadline = deadline; self.transport = transport
        }
    }
    private struct EndpointReceipt {
        let endpoint: NWEndpoint
        let runtime: String
        let bleSource: BLEBootstrapSourceKey?
    }
    private let bonjourTransport: IOSControlTransport
    private let peerToPeerTransport: IOSControlTransport
    private let channelFactory: (NWConnection) -> NWControlChannel
    private let pathDriver: NetworkPathDriving
    private var owner: Attempt?
    private var pendingInbound = [ObjectIdentifier: SignalingSessionController]()
    private var discoveredEndpoints = [String: EndpointReceipt]()
    private var discoveredPeerToPeerEndpoints = [String: EndpointReceipt]()
    #endif

    public init(nickname: String = "骑士", deviceName: String = "iPhone",
        identityStore: StableIdentityStore = StableIdentityStore(), pairingStore: PairingStoring = PairingStore(),
        audio: AudioSessionController? = nil, webRTCEngine: WebRTCEngine = WebRTCEngineFactory.makeDefault(),
        networkBootstrap: NetworkBootstrapCoordinator? = nil, scheduler: SessionDeadlineScheduling? = nil,
        bleSource: BLEBootstrapSource? = nil, initialIdentity: StableIdentity? = nil,
        initialCapabilities: RuntimeCapabilities? = nil,
        bonjourTransport: IOSControlTransport = BonjourTransport(), peerToPeerTransport: IOSControlTransport = ApplePeerToPeerTransport(),
        channelFactory: @escaping (NWConnection) -> NWControlChannel = { NWControlChannel(connection: $0) },
        pathDriver: NetworkPathDriving? = nil
    ) {
        self.identityStore = identityStore; self.pairingStore = pairingStore
        let resolvedAudio = audio ?? AudioSessionController()
        self.audio = resolvedAudio
        self.webRTC = WebRTCSessionCoordinator(engine: webRTCEngine, audio: resolvedAudio)
        self.networkBootstrap = networkBootstrap ?? NetworkBootstrapCoordinator()
        self.scheduler = scheduler ?? SessionDeadlineScheduler(); self.bleSource = bleSource
        self.bonjourTransport = bonjourTransport; self.peerToPeerTransport = peerToPeerTransport; self.channelFactory = channelFactory
        self.pathDriver = pathDriver ?? NetworkPathDriver()
        identity = initialIdentity; runtimeCapabilities = initialCapabilities
        webRTC.onAudioReadinessChanged = { [weak self] in self?.updateAudioReady() }
        webRTC.onTerminated = { [weak self] in
            guard let self, let attempt = self.owner, attempt.mediaStarted else { return }
            self.end(attempt, phase: .failed, message: "媒体连接已结束")
        }
        self.audio.onRouteChanged = { [weak self] route in
            guard let self, let attempt = self.owner, attempt.mediaStarted else { return }
            if route == .unavailable {
                self.statusMessage = "音频路由不可用，正在等待恢复"
                if !attempt.connected { self.phase = .recovering }
            } else { self.updateAudioReady() }
        }
        self.audio.onInterruptionChanged = { [weak self] interrupted in
            guard let self, let attempt = self.owner, attempt.mediaStarted else { return }
            self.webRTC.handleAudioInterruption(interrupted)
            self.statusMessage = interrupted ? "电话/系统音频中断，对讲已暂停" : "音频中断结束，正在恢复对讲"
            if !interrupted { self.updateAudioReady() }
        }
        if initialIdentity == nil {
            Task { @MainActor [weak self] in
                let deviceID = await identityStore.deviceID()
                let sessionID = await identityStore.newRuntimeSessionID()
                let records = await pairingStore.all()
                guard let self else { return }
                self.identity = try? StableIdentity(deviceID: deviceID, sessionID: sessionID, nickname: nickname, deviceName: deviceName)
                self.runtimeCapabilities = try? IOSRuntimeCapabilityProvider.make(deviceName: deviceName)
                guard self.command == 0 else { return }
                self.pairings = records
                if !records.isEmpty { self.startDiscovery() }
            }
        }
    }

    @discardableResult private func beginCommand(preserveDiscovery: Bool = false) -> UInt64 {
        command += 1
        bootstrapOperation = nil; selectedTarget = nil
        clearSession(reason: "NEW_COMMAND")
        networkBootstrap.close()
        if !preserveDiscovery { stopDiscovery() }
        return command
    }
    private func isCurrent(_ attempt: Attempt) -> Bool { owner === attempt && attempt.command == command }
    private func clearSession(reason: String) {
        let old = owner; owner = nil
        old?.timeout?.cancel(); old?.timeout = nil
        let inbound = Array(pendingInbound.values); pendingInbound.removeAll()
        old?.dial?.cancel(); old?.dial = nil
        old?.controller?.close(reason: reason)
        for controller in inbound { controller.close(reason: reason) }
        webRTC.close(); remoteCapabilities = nil
    }
    private func end(_ attempt: Attempt, phase: SessionPhase, message: String) {
        guard isCurrent(attempt) else { return }
        owner = nil
        attempt.timeout?.cancel(); attempt.timeout = nil
        attempt.dial?.cancel(); attempt.dial = nil
        attempt.controller?.close(reason: "SESSION_TERMINATED")
        webRTC.close(); remoteCapabilities = nil
        self.phase = phase; statusMessage = message
    }

    public func startDiscovery() { beginCommand(); startDiscovery(command: command) }
    public func startDiscoveryWithPermission() async {
        let ticket = beginCommand()
        guard await requestPermission(command: ticket), command == ticket else { return }
        startDiscovery(command: ticket)
    }
    private func startDiscovery(command ticket: UInt64) {
        guard command == ticket, let identity, let local = runtimeCapabilities else {
            if command == ticket { phase = .failed; statusMessage = "设备身份或网络能力仍在初始化" }
            return
        }
        stopDiscovery()
        let run = UUID(); discoveryRun = run
        nearbyPeers.removeAll(); announcements.removeAll()
        discoveredEndpoints.removeAll(); discoveredPeerToPeerEndpoints.removeAll()
        phase = .discovering; statusMessage = "正在通过蓝牙和 Bonjour 查找附近设备"
        do {
            let announcement = try BootstrapAnnouncement(platform: local.platform, deviceID: identity.deviceID, sessionID: identity.sessionID,
                capabilities: local.capabilities, networkRole: local.networkRole, tcpPort: local.tcpPort)
            if let bleSource {
                let bleRun = UUID(); self.bleRun = bleRun
                bleSource.start(runID: bleRun, announcement: announcement) { [weak self] event in
                    Task { @MainActor [weak self] in self?.consumeBootstrap(event, command: ticket, run: bleRun) }
                }
            } else {
                #if canImport(CoreBluetooth)
                // KUM-74 supplies the actual source producer. Legacy callbacks
                // have no source receipt and cannot authorize endpoints/joins.
                legacyBLE.onStateChanged = { [weak self] state in
                    Task { @MainActor [weak self] in
                        guard let self, self.command == ticket, self.discoveryRun == run else { return }
                        self.recordBLEState(state)
                    }
                }
                legacyBLE.start(announcement: announcement)
                #endif
            }
            bindNetwork(command: ticket, run: run)
            let advertisement = try BonjourServiceAdvertisement(deviceID: identity.deviceID, sessionID: identity.sessionID,
                nickname: identity.nickname, deviceName: identity.deviceName, platform: local.platform,
                capabilities: local.capabilities, networkRole: local.networkRole, tcpPort: local.tcpPort)
            try bonjourTransport.start(advertisement: advertisement)
            bonjourTransport.startBrowsing(); peerToPeerTransport.startBrowsing()
        } catch {
            guard command == ticket, discoveryRun == run else { return }
            stopDiscovery(); phase = phaseFor(error); statusMessage = error.localizedDescription
        }
    }
    private func stopDiscovery() {
        discoveryRun = nil
        if let run = bleRun { bleRun = nil; bleSource?.stop(runID: run) }
        #if canImport(CoreBluetooth)
        legacyBLE.stop()
        #endif
        pathDriver.stop()
        bonjourTransport.stop(); peerToPeerTransport.stop()
        announcements.removeAll()
    }
    private func bindNetwork(command ticket: UInt64, run: UUID) {
        for (transport, p2p) in [(bonjourTransport, false), (peerToPeerTransport, true)] {
            transport.onPeerFound = { [weak self] endpoint, advertisement in
                guard let advertisement else { return }
                Task { @MainActor [weak self] in
                    guard let self, self.discoveryRun == run else { return }
                    self.record(advertisement: advertisement, endpoint: endpoint, isPeerToPeer: p2p)
                }
            }
            transport.onConnection = { [weak self] connection in
                Task { @MainActor [weak self] in
                    guard let self, self.discoveryRun == run else { connection.cancel(); return }
                    self.handleIncomingConnection(connection)
                }
            }
            transport.onError = { [weak self] error in
                Task { @MainActor [weak self] in
                    guard let self, self.discoveryRun == run else { return }
                    self.statusMessage = "附近设备发现失败：\(error.localizedDescription)"
                }
            }
        }
        bindPathMonitor(command: ticket)
    }
    private func bindPathMonitor(command ticket: UInt64) {
        pathDriver.start { [weak self] usable, observedAt in
            Task { @MainActor [weak self] in
                guard let self, self.command == ticket, !usable, let attempt = self.owner, observedAt >= attempt.createdAt else { return }
                self.end(attempt, phase: .recovering, message: "网络路径已变化，对讲连接已暂停")
            }
        }
    }

    public func prepareIOSHost() {
        let ticket = beginCommand()
        phase = .networkPreparing; statusMessage = "正在准备 iPhone 主机网络"
        Task { @MainActor [weak self] in
            guard let self, await self.requestPermission(command: ticket), self.command == ticket else { return }
            self.startDiscovery(command: ticket)
            guard self.command == ticket, self.phase == .discovering else { return }
            self.phase = .manualActionRequired; self.statusMessage = "请打开个人热点并允许其他人加入，然后返回 MotoCom"
            self.openSystemNetworkSettings()
        }
    }
    public func finishManualNetworkSetup() {
        guard phase == .manualActionRequired else { return }
        let ticket = command
        networkBootstrap.markManualActionCompleted()
        startDiscovery(command: ticket)
        guard command == ticket, phase == .discovering else { return }
        phase = .networkReady; statusMessage = "正在等待另一台设备加入 iPhone 网络"
    }
    public func requestMicrophonePermission() async -> Bool { await requestPermission(command: command) }
    private func requestPermission(command ticket: UInt64) async -> Bool {
        let granted = await audio.requestMicrophonePermission()
        guard command == ticket else { return false }
        if !granted { phase = .permissionBlocked; statusMessage = "麦克风权限被拒绝，无法进行双向对讲" }
        return granted
    }
    public func openSystemNetworkSettings() {
        #if canImport(UIKit)
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }; UIApplication.shared.open(url)
        #else
        statusMessage = "请在 iPhone 系统设置中打开个人热点或加入 Wi-Fi"
        #endif
    }

    public func connect(to peer: NearbyPeer) {
        let lan = discoveredEndpoints[peer.deviceID].flatMap { $0.runtime == peer.sessionID ? $0.endpoint : nil }
        let p2p = discoveredPeerToPeerEndpoints[peer.deviceID].flatMap { $0.runtime == peer.sessionID ? $0.endpoint : nil }
        let ticket = beginCommand(preserveDiscovery: true)
        bindPathMonitor(command: ticket)
        selectedTarget = (peer.deviceID, peer.sessionID)
        guard let endpoint = lan ?? p2p, let identity, let local = runtimeCapabilities else {
            phase = .failed; statusMessage = "尚未获得 " + peer.deviceName + " 的当前 TCP 端点，请重新发现"; return
        }
        let path: NetworkPath = lan == nil && p2p != nil && peer.capabilities.platform == .ios &&
            local.capabilities.contains(.iosPeerToPeer) && peer.capabilities.capabilities.contains(.iosPeerToPeer) ? .applePeerToPeer : .commonLAN
        let attempt = Attempt(command: ticket, device: peer.deviceID, runtime: peer.sessionID,
            wireAttempt: UUID().uuidString.lowercased(), deadline: scheduler.now + 10, transport: path)
        owner = attempt; remoteCapabilities = peer.capabilities
        phase = .signaling; statusMessage = "正在连接 " + peer.deviceName
        armDeadline(attempt)
        connect(peer, endpoint: endpoint, identity: identity, attempt: attempt, fallback: true)
    }
    private func armDeadline(_ attempt: Attempt) {
        attempt.timeout?.cancel()
        attempt.timeout = scheduler.schedule(at: attempt.deadline) { [weak self, weak attempt] in
            guard let self, let attempt, self.isCurrent(attempt), attempt.controller?.handshakeComplete != true else { return }
            self.end(attempt, phase: .failed, message: "TCP/HELLO 超时")
        }
    }
    private func connect(_ peer: NearbyPeer, endpoint: NWEndpoint, identity: StableIdentity, attempt: Attempt, fallback: Bool) {
        let completion: (Result<NWConnection, Error>) -> Void = { [weak self, weak attempt] result in
            Task { @MainActor [weak self, weak attempt] in
                guard let self, let attempt, self.isCurrent(attempt), self.scheduler.now < attempt.deadline, attempt.controller == nil else {
                    if case .success(let connection) = result { connection.cancel() }; return
                }
                switch result {
                case .failure(let error):
                    if fallback, attempt.transport == .applePeerToPeer, peer.capabilities.capabilities.contains(.lan) {
                        attempt.transport = .commonLAN
                        self.connect(peer, endpoint: endpoint, identity: identity, attempt: attempt, fallback: false)
                    } else { self.end(attempt, phase: .failed, message: "TCP 连接失败：" + error.localizedDescription) }
                case .success(let connection):
                    do {
                        let controller = try SignalingSessionController(channel: self.channelFactory(connection), localIdentity: identity,
                            remoteDeviceID: peer.deviceID, attemptID: attempt.wireAttempt, expectedRemoteSessionID: peer.sessionID,
                            autoStart: false, scheduler: self.scheduler, helloDeadline: attempt.deadline)
                        attempt.controller = controller
                        self.bind(controller, candidateCommand: attempt.command, deadline: attempt.deadline)
                        controller.startAsRequester(capabilities: self.signalingCapabilities()); controller.start()
                    } catch { connection.cancel(); self.end(attempt, phase: .failed, message: error.localizedDescription) }
                }
            }
        }
        let transport = attempt.transport == .applePeerToPeer ? peerToPeerTransport : bonjourTransport
        attempt.dial?.cancel()
        attempt.dial = transport.connect(to: endpoint, includePeerToPeer: attempt.transport == .applePeerToPeer, completion: completion)
    }
    public func attachSignaling(_ controller: SignalingSessionController, remote: RuntimeCapabilities? = nil) {
        guard !controller.isTerminated, owner == nil else { controller.close(reason: "BUSY"); return }
        if let device = controller.remoteDeviceID, let attemptID = controller.attemptID {
            let attempt = Attempt(command: command, device: device, runtime: controller.expectedRuntimeID,
                wireAttempt: attemptID, deadline: controller.absoluteHelloDeadline, transport: .commonLAN)
            attempt.controller = controller; owner = attempt; remoteCapabilities = remote
            phase = .signaling; statusMessage = "正在验证控制连接"
            armDeadline(attempt)
        } else { pendingInbound[ObjectIdentifier(controller)] = controller }
        bind(controller, candidateCommand: command, deadline: controller.absoluteHelloDeadline)
    }
    private func handleIncomingConnection(_ connection: NWConnection) {
        guard let identity, pendingInbound.count < 4 else { connection.cancel(); return }
        do {
            let controller = try SignalingSessionController(channel: channelFactory(connection), localIdentity: identity,
                autoStart: false, scheduler: scheduler, helloDeadline: owner?.deadline ?? scheduler.now + 10)
            pendingInbound[ObjectIdentifier(controller)] = controller
            bind(controller, candidateCommand: command, deadline: controller.absoluteHelloDeadline)
            controller.startAsResponder(capabilities: signalingCapabilities()); controller.start()
        } catch { connection.cancel() }
    }
    private func claim(_ controller: SignalingSessionController, command ticket: UInt64, deadline: TimeInterval) -> Bool {
        guard command == ticket, scheduler.now < deadline, let device = controller.remoteDeviceID,
              let runtime = controller.remoteSessionID, let attemptID = controller.attemptID else { return false }
        if let old = owner {
            if old.controller === controller {
                guard old.device == device, old.runtime == nil || old.runtime == runtime else { return false }
                old.helloVerified = true; return true
            }
            guard !old.mediaStarted, !old.connected, old.device == device, old.runtime == runtime,
                  scheduler.now < old.deadline, let local = identity else { return false }
            let localKey = [old.controller?.attemptID ?? old.wireAttempt, local.deviceID, local.sessionID, device]
            let remoteKey = [attemptID, device, runtime, local.deviceID]
            guard remoteKey.lexicographicallyPrecedes(localKey) else { return false }
            let replacement = Attempt(command: ticket, device: device, runtime: runtime, wireAttempt: attemptID,
                deadline: old.deadline, transport: old.transport)
            replacement.controller = controller; replacement.helloVerified = true
            owner = replacement; old.timeout?.cancel(); old.dial?.cancel(); old.dial = nil; old.controller?.close(reason: "GLARE_LOST")
        } else {
            if let selectedTarget, selectedTarget.device != device || selectedTarget.runtime != runtime { return false }
            let incoming = Attempt(command: ticket, device: device, runtime: runtime, wireAttempt: attemptID, deadline: deadline, transport: .commonLAN)
            incoming.controller = controller; incoming.helloVerified = true; owner = incoming
        }
        pendingInbound.removeValue(forKey: ObjectIdentifier(controller)); return true
    }
    private func bind(_ controller: SignalingSessionController, candidateCommand: UInt64, deadline: TimeInterval) {
        controller.onClaimHello = { [weak self] controller in self?.claim(controller, command: candidateCommand, deadline: deadline) ?? false }
        controller.onReadyToRequest = { [weak self, weak controller] in
            guard let self, let controller, self.owner?.controller === controller else { return }; controller.sendConnectRequest()
        }
        controller.onHello = { [weak self, weak controller] device, nickname, name, capabilities in
            guard let self, let controller, self.owner?.controller === controller else { return }
            self.recordHello(device: device, nickname: nickname, name: name, capabilities: capabilities)
        }
        controller.onIncomingRequest = { [weak self, weak controller] in
            guard let self, let controller, let attempt = self.owner, self.isCurrent(attempt), attempt.controller === controller else { return }
            if self.pairings.contains(where: { $0.remoteDeviceID == attempt.device }) {
                if controller.accept() { self.startMedia(offerer: false) }
            } else {
                self.phase = .awaitingConfirmation
                self.statusMessage = "发现来自 \(self.remoteCapabilities?.deviceName ?? "远端设备") 的连接请求，请确认"
            }
        }
        controller.onAccepted = { [weak self, weak controller] in
            guard let self, let controller, self.owner?.controller === controller else { return }; self.startMedia(offerer: true)
        }
        controller.onOffer = { [weak self, weak controller] value in self?.applyMedia(controller, { try $0.setRemoteOffer(value) }) }
        controller.onAnswer = { [weak self, weak controller] value in self?.applyMedia(controller, { try $0.setRemoteAnswer(value) }) }
        controller.onCandidate = { [weak self, weak controller] value in self?.applyMedia(controller, { try $0.addRemoteCandidate(value) }) }
        controller.onTerminated = { [weak self, weak controller] error in
            guard let self, let controller else { return }
            self.pendingInbound.removeValue(forKey: ObjectIdentifier(controller))
            guard let attempt = self.owner, self.isCurrent(attempt), attempt.controller === controller else { return }
            self.end(attempt, phase: error == nil ? .offline : self.phaseFor(error!), message: error?.localizedDescription ?? "对方已结束对讲")
        }
    }
    private func applyMedia(_ controller: SignalingSessionController?, _ action: (WebRTCSessionCoordinator) throws -> Void) {
        guard let controller, let attempt = owner, isCurrent(attempt), attempt.controller === controller, attempt.mediaStarted else { return }
        do { try action(webRTC) } catch { end(attempt, phase: phaseFor(error), message: error.localizedDescription) }
    }
    private func signalingCapabilities() -> Set<String> { Set((runtimeCapabilities?.capabilities ?? []).map(\.rawValue)) }
    public func startMedia(offerer: Bool) {
        guard let attempt = owner, isCurrent(attempt), attempt.helloVerified, !attempt.mediaStarted,
              attempt.controller?.phase == .accepted else { return }
        attempt.mediaStarted = true
        webRTC.onLocalOffer = { [weak self, weak attempt] value in
            guard let self, let attempt, self.isCurrent(attempt) else { return }; attempt.controller?.sendOffer(value)
        }
        webRTC.onLocalAnswer = { [weak self, weak attempt] value in
            guard let self, let attempt, self.isCurrent(attempt) else { return }; attempt.controller?.sendAnswer(value)
        }
        webRTC.onLocalCandidate = { [weak self, weak attempt] value in
            guard let self, let attempt, self.isCurrent(attempt) else { return }; attempt.controller?.sendCandidate(Data(value.utf8))
        }
        do {
            try webRTC.start(offerer: offerer)
            guard isCurrent(attempt) else { return }
            phase = .mediaNegotiating; statusMessage = "正在建立双向音频"
            attempt.timeout = scheduler.schedule(at: scheduler.now + 10) { [weak self, weak attempt] in
                guard let self, let attempt, self.isCurrent(attempt), !attempt.connected else { return }
                self.end(attempt, phase: .failed, message: "AUDIO_READY 超时，未确认远端首帧或本地音频路由")
            }
        } catch { end(attempt, phase: phaseFor(error), message: error.localizedDescription) }
    }
    public func acceptIncoming() {
        guard phase == .awaitingConfirmation, let attempt = owner, isCurrent(attempt), attempt.controller?.accept() == true else { return }
        startMedia(offerer: false)
    }
    private func updateAudioReady() {
        guard let attempt = owner, isCurrent(attempt), attempt.mediaStarted, !attempt.connected,
              webRTC.audioReady, let controller = attempt.controller, controller.markMediaConnected(), let remote = remoteCapabilities else { return }
        attempt.connected = true; attempt.timeout?.cancel(); attempt.timeout = nil
        phase = .connected; statusMessage = "连接成功，已听到远端音频"
        let nickname = controller.remoteNickname.isEmpty ? remote.deviceName : controller.remoteNickname
        guard let record = try? PairingRecord(remoteDeviceID: attempt.device, remoteNickname: nickname, deviceName: remote.deviceName, lastTransport: attempt.transport.rawValue) else { return }
        let store = pairingStore; let transport = attempt.transport.rawValue
        // A legitimate Connected fact may be persisted after stop, but its
        // completion cannot change B or reclaim a product-state owner.
        Task { @MainActor [weak self, weak attempt] in
            do {
                try await store.saveConnectedPeer(record, audioReady: true, transport: transport)
                let records = await store.all()
                guard let self, let attempt, self.isCurrent(attempt), attempt.connected else { return }
                self.pairings = records
            } catch {
                guard let self, let attempt, self.isCurrent(attempt), attempt.connected else { return }
                self.statusMessage = "对讲已连接；配对记录保存失败：\(error.localizedDescription)"
            }
        }
    }

    public func prepareNetwork(for remote: RuntimeCapabilities, context: BootstrapContext = BootstrapContext()) async {
        let ticket = beginCommand()
        guard let local = runtimeCapabilities else { phase = .failed; statusMessage = "本机网络能力仍在初始化"; return }
        do {
            let decision = try networkBootstrap.selectPath(local: local, remote: remote, context: context)
            guard command == ticket else { return }
            phase = decision.requiresUserAction ? .manualActionRequired : .networkPreparing; statusMessage = decision.reason
        } catch { guard command == ticket else { return }; phase = phaseFor(error); statusMessage = error.localizedDescription }
    }
    private func joinHotspot(_ credentials: HotspotCredentials, command ticket: UInt64, operation: UUID) async {
        guard command == ticket, bootstrapOperation == operation else { return }
        do {
            try await networkBootstrap.acceptAndroidHotspot(credentials)
            guard command == ticket, bootstrapOperation == operation else { return }
            bootstrapOperation = nil
            startDiscovery(command: ticket)
            guard command == ticket else { return }
            phase = .networkReady; statusMessage = "已加入 Android 临时网络，等待 Bonjour/TCP 端点"
        } catch {
            guard command == ticket, bootstrapOperation == operation else { return }
            bootstrapOperation = nil; phase = phaseFor(error); statusMessage = error.localizedDescription
        }
    }
    private func consumeBootstrap(_ event: BLEBootstrapEvent, command ticket: UInt64, run: UUID) {
        guard command == ticket, bleRun == run else {
            if case .message(let receipt, _) = event { receipt.delivery.acknowledge() }
            if case .announcement(let receipt, _) = event { receipt.delivery.acknowledge() }
            return
        }
        switch event {
        case .state(let eventRun, let state): if eventRun == run { recordBLEState(state) }
        case .sourceEnded(let key, _):
            guard key.runID == run else { return }; announcements.removeValue(forKey: key)
            discoveredEndpoints = discoveredEndpoints.filter { $0.value.bleSource != key }
        case .announcement(let receipt, let announcement): consume(receipt, announcement: announcement, message: nil, command: ticket, run: run)
        case .message(let receipt, let message): consume(receipt, announcement: message.announcement, message: message, command: ticket, run: run)
        }
    }
    private func consume(_ receipt: BLEBootstrapReceipt, announcement: BootstrapAnnouncement?, message: BootstrapMessage?, command ticket: UInt64, run: UUID) {
        defer { receipt.delivery.acknowledge() }
        let operation = UUID()
        let claimed: BootstrapAnnouncement? = receipt.withCurrent {
            guard command == ticket, bleRun == run, receipt.source.key.runID == run else { return nil }
            if let announcement { announcements[receipt.source.key] = announcement }
            guard let remote = announcements[receipt.source.key] else { return nil }
            if let selectedTarget, selectedTarget.device != remote.deviceID || selectedTarget.runtime != remote.sessionID { return nil }
            if let owner, owner.device != remote.deviceID || owner.runtime != remote.sessionID { return nil }
            if message?.hotspot != nil {
                guard owner == nil, bootstrapOperation == nil else { return nil }
                selectedTarget = (remote.deviceID, remote.sessionID); bootstrapOperation = operation
            }
            return remote
        }
        guard let remote = claimed else { return }
        if let message, let address = message.endpointAddress, let value = message.endpointPort,
           (1...65535).contains(value), let port = NWEndpoint.Port(rawValue: UInt16(value)) {
            discoveredEndpoints[remote.deviceID] = EndpointReceipt(endpoint: .hostPort(host: NWEndpoint.Host(address), port: port), runtime: remote.sessionID, bleSource: receipt.source.key)
        }
        record(announcement: remote)
        if let hotspot = message?.hotspot {
            Task { @MainActor [weak self] in await self?.joinHotspot(hotspot, command: ticket, operation: operation) }
        }
    }
    private func recordBLEState(_ state: BLEBootstrapState) {
        guard owner == nil else { return }
        if case .permissionBlocked(let permission) = state {
            phase = .permissionBlocked; statusMessage = "蓝牙不可用（\(permission.rawValue)），无法自动准备网络"
        } else if (state == .scanning || state == .ready), phase == .permissionBlocked {
            phase = .discovering; statusMessage = "正在查找附近设备"
        }
    }
    private func record(announcement: BootstrapAnnouncement) {
        let name = announcement.platform == .ios ? "iPhone" : "Android"
        guard let capabilities = try? RuntimeCapabilities(platform: announcement.platform, platformVersion: "unknown", deviceName: name,
            capabilities: announcement.capabilities, networkRole: announcement.networkRole, tcpPort: announcement.tcpPort),
            let peer = try? NearbyPeer(deviceID: announcement.deviceID, sessionID: announcement.sessionID, nickname: name, deviceName: name, capabilities: capabilities) else { return }
        upsert(peer)
    }
    private func record(advertisement: BonjourServiceAdvertisement, endpoint: NWEndpoint, isPeerToPeer: Bool) {
        let receipt = EndpointReceipt(endpoint: endpoint, runtime: advertisement.sessionID, bleSource: nil)
        if isPeerToPeer { discoveredPeerToPeerEndpoints[advertisement.deviceID] = receipt }
        else { discoveredEndpoints[advertisement.deviceID] = receipt }
        guard let capabilities = try? RuntimeCapabilities(platform: advertisement.platform, platformVersion: "unknown", deviceName: advertisement.deviceName,
            capabilities: advertisement.capabilities, networkRole: advertisement.networkRole, tcpPort: advertisement.tcpPort),
            let peer = try? NearbyPeer(deviceID: advertisement.deviceID, sessionID: advertisement.sessionID, nickname: advertisement.nickname, deviceName: advertisement.deviceName, capabilities: capabilities) else { return }
        upsert(peer)
    }
    private func recordHello(device: String, nickname: String, name: String, capabilities: Set<String>) {
        let values = Set(capabilities.compactMap(NetworkCapability.init(rawValue:)))
        let platform = nearbyPeers.first { $0.deviceID == device }?.capabilities.platform ??
            (values.contains(.iosPeerToPeer) || values.contains(.iosPersonalHotspotManual) ? .ios : .android)
        remoteCapabilities = try? RuntimeCapabilities(platform: platform, platformVersion: "unknown", deviceName: name.isEmpty ? "远端设备" : name,
            capabilities: values, networkRole: .either, tcpPort: 8890)
    }
    private func upsert(_ peer: NearbyPeer) {
        guard peer.deviceID != identity?.deviceID else { return }
        nearbyPeers.removeAll { $0.deviceID == peer.deviceID }; nearbyPeers.append(peer)
        nearbyPeers.sort { $0.nickname.localizedCaseInsensitiveCompare($1.nickname) == .orderedAscending }
        if phase == .discovering, owner == nil, bootstrapOperation == nil,
           discoveredEndpoints[peer.deviceID] != nil || discoveredPeerToPeerEndpoints[peer.deviceID] != nil,
           pairings.contains(where: { $0.remoteDeviceID == peer.deviceID }) { connect(to: peer) }
    }
    public func markNetworkReady() {
        guard owner == nil, discoveryRun != nil else { return }; phase = .networkReady; statusMessage = "网络已准备，等待信令"
    }
    public func refreshAudioRoute() { audio.refreshRoute(); updateAudioReady() }
    public func recover() {
        if let owner, owner.mediaStarted { audio.refreshRoute(); updateAudioReady() }
        else if identity != nil, phase != .offline { startDiscovery() }
    }
    public func stop() {
        beginCommand(); discoveredEndpoints.removeAll(); discoveredPeerToPeerEndpoints.removeAll(); nearbyPeers.removeAll()
        phase = .offline; statusMessage = "对讲已结束"
    }
    private func phaseFor(_ error: Error) -> SessionPhase {
        guard let error = error as? MotoComError else { return .failed }
        switch error { case .permissionDenied: return .permissionBlocked; case .manualActionRequired: return .manualActionRequired; default: return .failed }
    }
}
