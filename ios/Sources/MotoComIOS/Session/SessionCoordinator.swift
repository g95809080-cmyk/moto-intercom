import Foundation
import Combine
#if canImport(UIKit)
import UIKit
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
    public let pairingStore: PairingStore
    public let webRTC: WebRTCSessionCoordinator

    private let nickname: String
    private let deviceName: String
    private var activeTransport: NetworkPath?
    private var activeRemoteDeviceID: String?
    private var bootstrapRemoteDeviceID: String?
    private var pairingSaveInFlight = false
    private var audioReadyTimeoutTask: Task<Void, Never>?
    #if canImport(CoreBluetooth)
    private let bleBootstrap = BLEBootstrapCoordinator()
    #endif
    #if canImport(Network)
    private let bonjourTransport = BonjourTransport()
    private let peerToPeerTransport = ApplePeerToPeerTransport()
    private let networkPathMonitor = NWPathMonitor()
    private var signalingController: SignalingSessionController?
    private var discoveredEndpoints = [String: NWEndpoint]()
    private var discoveredPeerToPeerEndpoints = [String: NWEndpoint]()
    #endif

    public init(
        nickname: String = "骑士",
        deviceName: String = "iPhone",
        identityStore: StableIdentityStore = StableIdentityStore(),
        pairingStore: PairingStore = PairingStore(),
        audio: AudioSessionController = AudioSessionController(),
        webRTCEngine: WebRTCEngine = WebRTCEngineFactory.makeDefault()
    ) {
        self.nickname = nickname
        self.deviceName = deviceName
        self.identityStore = identityStore
        self.pairingStore = pairingStore
        self.audio = audio
        self.networkBootstrap = NetworkBootstrapCoordinator()
        self.webRTC = WebRTCSessionCoordinator(engine: webRTCEngine, audio: audio)
        self.webRTC.onAudioReadinessChanged = { [weak self] in
            self?.updateAudioReady()
        }
        #if canImport(CoreBluetooth)
        bleBootstrap.onStateChanged = { [weak self] state in
            guard case .permissionBlocked(let permission) = state else { return }
            Task { @MainActor [weak self] in
                self?.phase = .permissionBlocked
                self?.statusMessage = "蓝牙不可用（\(permission.rawValue)），无法自动准备网络"
            }
        }
        bleBootstrap.onAnnouncement = { [weak self] announcement in
            Task { @MainActor [weak self] in self?.record(announcement: announcement) }
        }
        bleBootstrap.onMessage = { [weak self] message in
            Task { @MainActor [weak self] in self?.handleBootstrap(message) }
        }
        bleBootstrap.onError = { [weak self] error in
            Task { @MainActor [weak self] in self?.handleDiscoveryError(error) }
        }
        #endif
        #if canImport(Network)
        networkPathMonitor.pathUpdateHandler = { [weak self] path in
            let isUsable = path.status == .satisfied
            Task { @MainActor [weak self] in
                guard let self, self.phase != .idle, self.phase != .offline else { return }
                if !isUsable {
                    self.audioReadyTimeoutTask?.cancel()
                    self.audioReadyTimeoutTask = nil
                    self.signalingController?.close(reason: "NETWORK_PATH_LOST")
                    self.signalingController = nil
                    self.webRTC.close()
                    self.phase = .recovering
                    self.statusMessage = "网络路径已变化，对讲连接已暂停，等待恢复"
                } else if self.phase == .recovering {
                    self.recover()
                }
            }
        }
        networkPathMonitor.start(queue: DispatchQueue(label: "com.motocom.path-monitor"))
        bonjourTransport.onPeerFound = { [weak self] endpoint, advertisement in
            guard let advertisement else { return }
            Task { @MainActor [weak self] in
                self?.record(advertisement: advertisement, endpoint: endpoint, isPeerToPeer: false)
            }
        }
        bonjourTransport.onConnection = { [weak self] connection in
            Task { @MainActor [weak self] in
                self?.handleIncomingConnection(connection)
            }
        }
        bonjourTransport.onError = { [weak self] error in
            Task { @MainActor [weak self] in self?.handleDiscoveryError(error) }
        }
        peerToPeerTransport.onPeerFound = { [weak self] endpoint, advertisement in
            guard let advertisement else { return }
            Task { @MainActor [weak self] in
                self?.record(advertisement: advertisement, endpoint: endpoint, isPeerToPeer: true)
            }
        }
        peerToPeerTransport.onError = { [weak self] error in
            Task { @MainActor [weak self] in self?.handleDiscoveryError(error) }
        }
        #endif
        Task { @MainActor [weak self] in
            guard let self else { return }
            let deviceID = await identityStore.deviceID()
            let sessionID = await identityStore.newRuntimeSessionID()
            guard let identity = try? StableIdentity(
                deviceID: deviceID,
                sessionID: sessionID,
                nickname: nickname,
                deviceName: deviceName
            ) else { return }
            self.identity = identity
            self.runtimeCapabilities = try? IOSRuntimeCapabilityProvider.make(deviceName: deviceName)
            self.pairings = await pairingStore.all()
            if !self.pairings.isEmpty { self.startDiscovery() }
        }
        audio.onInterruptionChanged = { [weak self] interrupted in
            guard let self else { return }
            self.webRTC.handleAudioInterruption(interrupted)
            if interrupted {
                self.statusMessage = "电话/系统音频中断，对讲已暂停"
                self.phase = .recovering
            } else {
                self.statusMessage = "音频中断结束，正在恢复对讲"
                self.updateAudioReady()
            }
        }
        audio.onRouteChanged = { [weak self] route in
            guard let self else { return }
            if route == .unavailable {
                self.phase = .recovering
                self.statusMessage = "音频路由不可用，正在等待恢复"
            } else {
                if !self.audio.isActive { try? self.audio.activate() }
                self.updateAudioReady()
            }
        }
    }

    public func startDiscovery() {
        guard identity != nil else {
            phase = .failed
            statusMessage = "设备身份仍在初始化"
            return
        }
        phase = .discovering
        statusMessage = "正在通过蓝牙和 Bonjour 查找附近设备"
        nearbyPeers.removeAll()
        bootstrapRemoteDeviceID = nil
        guard let identity, let runtimeCapabilities else { return }
        do {
            let announcement = try BootstrapAnnouncement(
                platform: runtimeCapabilities.platform,
                deviceID: identity.deviceID,
                sessionID: identity.sessionID,
                capabilities: runtimeCapabilities.capabilities,
                networkRole: runtimeCapabilities.networkRole,
                tcpPort: runtimeCapabilities.tcpPort
            )
            #if canImport(CoreBluetooth)
            bleBootstrap.start(announcement: announcement)
            if case .permissionBlocked(let permission) = bleBootstrap.state {
                phase = .permissionBlocked
                statusMessage = "蓝牙不可用（\(permission.rawValue)），无法自动准备网络"
                return
            }
            #endif
            #if canImport(Network)
            discoveredEndpoints.removeAll()
            discoveredPeerToPeerEndpoints.removeAll()
            let advertisement = try BonjourServiceAdvertisement(
                deviceID: identity.deviceID,
                sessionID: identity.sessionID,
                nickname: identity.nickname,
                deviceName: identity.deviceName,
                platform: runtimeCapabilities.platform,
                capabilities: runtimeCapabilities.capabilities,
                networkRole: runtimeCapabilities.networkRole,
                tcpPort: runtimeCapabilities.tcpPort
            )
            try bonjourTransport.start(advertisement: advertisement)
            bonjourTransport.startBrowsing()
            peerToPeerTransport.startBrowsing()
            #endif
        } catch {
            phase = .failed
            statusMessage = error.localizedDescription
        }
    }

    /// Starts the user-led iPhone-host path without asking the app to read or
    /// mutate the system Personal Hotspot password.
    public func prepareIOSHost() {
        guard identity != nil else {
            phase = .failed
            statusMessage = "设备身份仍在初始化"
            return
        }
        activeTransport = .iosPersonalHotspotManual
        phase = .networkPreparing
        statusMessage = "正在准备 iPhone 主机网络"
        Task { @MainActor [weak self] in
            guard let self, await self.requestMicrophonePermission() else { return }
            self.startDiscovery()
            guard self.phase != .permissionBlocked else { return }
            self.phase = .manualActionRequired
            self.statusMessage = "请打开个人热点并允许其他人加入，然后返回 MotoCom"
            self.openSystemNetworkSettings()
        }
    }

    public func finishManualNetworkSetup() {
        guard phase == .manualActionRequired else { return }
        Task { @MainActor [weak self] in
            guard let self else { return }
            await self.networkBootstrap.markManualActionCompleted()
            self.startDiscovery()
            self.phase = .networkReady
            self.statusMessage = "正在等待另一台设备加入 iPhone 网络"
        }
    }

    public func requestMicrophonePermission() async -> Bool {
        let granted = await audio.requestMicrophonePermission()
        if !granted {
            phase = .permissionBlocked
            statusMessage = "麦克风权限被拒绝，无法进行双向对讲"
        }
        return granted
    }

    public func openSystemNetworkSettings() {
        #if canImport(UIKit)
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
        #else
        statusMessage = "请在 iPhone 系统设置中打开个人热点或加入 Wi-Fi"
        #endif
    }

    public func showIncomingConfirmation(remote: RuntimeCapabilities) {
        remoteCapabilities = remote
        phase = .awaitingConfirmation
        statusMessage = "发现来自 \(remote.deviceName) 的连接请求，请确认"
    }

    #if canImport(Network)
    public func attachSignaling(
        _ controller: SignalingSessionController,
        remote: RuntimeCapabilities? = nil
    ) {
        signalingController = controller
        if let remote { remoteCapabilities = remote }
        controller.onReadyToRequest = { [weak controller] in
            controller?.sendConnectRequest()
        }
        controller.onHello = { [weak self] deviceID, nickname, deviceName, capabilities in
            self?.record(signalingHello: (
                deviceID: deviceID,
                nickname: nickname,
                deviceName: deviceName,
                capabilities: capabilities
            ))
        }
        controller.onIncomingRequest = { [weak self, weak controller] in
            guard let self,
                  let controller,
                  let remote = self.remoteCapabilities,
                  let remoteDeviceID = controller.remoteDeviceID else { return }
            if self.pairings.contains(where: { $0.remoteDeviceID == remoteDeviceID }) {
                controller.accept()
                self.startMedia(offerer: false)
            } else {
                self.showIncomingConfirmation(remote: remote)
            }
        }
        controller.onAccepted = { [weak self] in self?.startMedia(offerer: true) }
        controller.onOffer = { [weak self] sdp in
            do { try self?.webRTC.setRemoteOffer(sdp) }
            catch { self?.statusMessage = error.localizedDescription }
        }
        controller.onAnswer = { [weak self] sdp in
            do { try self?.webRTC.setRemoteAnswer(sdp) }
            catch { self?.statusMessage = error.localizedDescription }
        }
        controller.onCandidate = { [weak self] candidate in
            do { try self?.webRTC.addRemoteCandidate(candidate) }
            catch { self?.statusMessage = error.localizedDescription }
        }
        controller.onError = { [weak self] error in
            self?.phase = .failed
            self?.statusMessage = error.localizedDescription
        }
        webRTC.onLocalOffer = { [weak controller] sdp in controller?.sendOffer(sdp) }
        webRTC.onLocalAnswer = { [weak controller] sdp in controller?.sendAnswer(sdp) }
        webRTC.onLocalCandidate = { [weak controller] candidate in
            controller?.sendCandidate(Data(candidate.utf8))
        }
    }

    public func connect(to peer: NearbyPeer) {
        let lanEndpoint = discoveredEndpoints[peer.deviceID]
        let peerToPeerEndpoint = discoveredPeerToPeerEndpoints[peer.deviceID]
        guard let endpoint = lanEndpoint ?? peerToPeerEndpoint else {
            phase = .failed
            statusMessage = "尚未获得 " + peer.deviceName + " 的 TCP 端点，请重新发现"
            return
        }
        guard let identity, let runtimeCapabilities else {
            phase = .failed
            statusMessage = "设备身份或网络能力仍在初始化"
            return
        }
        let transport: NetworkPath = lanEndpoint == nil &&
            peerToPeerEndpoint != nil &&
            peer.capabilities.platform == .ios &&
            runtimeCapabilities.capabilities.contains(.iosPeerToPeer) &&
            peer.capabilities.capabilities.contains(.iosPeerToPeer)
            ? .applePeerToPeer
            : .commonLAN
        activeTransport = transport
        activeRemoteDeviceID = peer.deviceID
        phase = .signaling
        statusMessage = "正在连接 " + peer.deviceName
        let attemptID = UUID().uuidString.lowercased()
        connect(
            to: peer,
            endpoint: endpoint,
            identity: identity,
            attemptID: attemptID,
            transport: transport,
            allowP2PFallback: true
        )
    }

    private func connect(
        to peer: NearbyPeer,
        endpoint: NWEndpoint,
        identity: StableIdentity,
        attemptID: String,
        transport: NetworkPath,
        allowP2PFallback: Bool
    ) {
        let completion: (Result<NWConnection, Error>) -> Void = { [weak self] result in
            Task { @MainActor [weak self] in
                guard let self else { return }
                switch result {
                case .failure(let error):
                    if allowP2PFallback,
                       transport == .applePeerToPeer,
                       peer.capabilities.capabilities.contains(.lan) {
                        self.activeTransport = .commonLAN
                        self.statusMessage = "Apple P2P 不可用，正在回退共同局域网"
                        self.connect(
                            to: peer,
                            endpoint: endpoint,
                            identity: identity,
                            attemptID: attemptID,
                            transport: .commonLAN,
                            allowP2PFallback: false
                        )
                        return
                    }
                    self.phase = .failed
                    self.statusMessage = "TCP 连接失败：" + error.localizedDescription
                case .success(let connection):
                    do {
                        let channel = NWControlChannel(connection: connection)
                        let controller = try SignalingSessionController(
                            channel: channel,
                            localIdentity: identity,
                            remoteDeviceID: peer.deviceID,
                            attemptID: attemptID,
                            expectedRemoteSessionID: peer.sessionID,
                            autoStart: false
                        )
                        self.attachSignaling(controller, remote: peer.capabilities)
                        controller.startAsRequester(
                            capabilities: self.signalingCapabilities()
                        )
                        controller.start()
                    } catch {
                        self.phase = .failed
                        self.statusMessage = error.localizedDescription
                    }
                }
            }
        }
        if transport == .applePeerToPeer {
            peerToPeerTransport.connect(to: endpoint, completion: completion)
        } else {
            bonjourTransport.connect(
                to: endpoint,
                includePeerToPeer: false,
                completion: completion
            )
        }
    }

    private func handleIncomingConnection(_ connection: NWConnection) {
        guard let identity else {
            connection.cancel()
            return
        }
        do {
            let channel = NWControlChannel(connection: connection)
            let controller = try SignalingSessionController(
                channel: channel,
                localIdentity: identity,
                autoStart: false
            )
            activeTransport = .commonLAN
            attachSignaling(controller)
            controller.startAsResponder(capabilities: signalingCapabilities())
            controller.start()
            phase = .signaling
            statusMessage = "已建立控制连接，等待对方请求"
        } catch {
            connection.cancel()
            phase = .failed
            statusMessage = error.localizedDescription
        }
    }

    private func signalingCapabilities() -> Set<String> {
        Set((runtimeCapabilities?.capabilities ?? []).map(\.rawValue))
    }

    public func startMedia(offerer: Bool) {
        do {
            try webRTC.start(offerer: offerer)
            phase = .mediaNegotiating
            statusMessage = "正在建立双向音频"
            scheduleAudioReadyTimeout()
        } catch {
            audioReadyTimeoutTask?.cancel()
            audioReadyTimeoutTask = nil
            phase = phaseFor(error)
            statusMessage = error.localizedDescription
        }
    }
    #endif

    public func prepareNetwork(
        for remote: RuntimeCapabilities,
        context: BootstrapContext = BootstrapContext()
    ) async {
        guard let local = runtimeCapabilities else {
            phase = .failed
            statusMessage = "本机网络能力仍在初始化"
            return
        }
        phase = .bootstrapNegotiating
        do {
            let decision = try await networkBootstrap.selectPath(
                local: local,
                remote: remote,
                context: context
            )
            activeTransport = decision.path
            phase = decision.requiresUserAction ? .manualActionRequired : .networkPreparing
            statusMessage = decision.reason
        } catch {
            phase = phaseFor(error)
            statusMessage = error.localizedDescription
        }
    }

    public func receiveAndroidHotspot(_ credentials: HotspotCredentials) async {
        do {
            try await networkBootstrap.acceptAndroidHotspot(credentials)
            phase = .networkReady
            statusMessage = "已加入 Android 临时网络，等待 Bonjour/TCP 端点"
            #if canImport(Network)
            let bootstrapDeviceID = bootstrapRemoteDeviceID
            startDiscovery()
            bootstrapRemoteDeviceID = bootstrapDeviceID
            #endif
        } catch {
            phase = phaseFor(error)
            statusMessage = error.localizedDescription
        }
    }

    public func acceptIncoming() {
        guard phase == .awaitingConfirmation else { return }
        #if canImport(Network)
        signalingController?.accept()
        if signalingController != nil { startMedia(offerer: false) }
        #endif
        if signalingController == nil {
            phase = .failed
            statusMessage = "控制连接已断开，无法接受请求"
        }
    }

    public func markNetworkReady() {
        phase = .networkReady
        statusMessage = "网络已准备，等待信令"
    }

    public func markRemoteAudioTrack() {
        audio.markRemoteTrackPresent()
        updateAudioReady()
    }

    public func markRemoteAudioFrame() {
        audio.markRemoteFirstFrameReceived()
        updateAudioReady()
    }

    public func refreshAudioRoute() {
        audio.refreshRoute()
        updateAudioReady()
    }

    public func persistConnectedPeer(
        remoteDeviceID: String,
        remoteNickname: String,
        deviceName: String,
        transport: NetworkPath
    ) async {
        guard phase != .connected else { return }
        guard webRTC.audioReady else {
            statusMessage = "媒体已连接但远端音频尚未就绪"
            return
        }
        do {
            let record = try PairingRecord(
                remoteDeviceID: remoteDeviceID,
                remoteNickname: remoteNickname,
                deviceName: deviceName,
                lastTransport: transport.rawValue
            )
            try await pairingStore.saveConnectedPeer(record, audioReady: true, transport: transport.rawValue)
            pairings = await pairingStore.all()
            phase = .connected
            statusMessage = "连接成功，已听到远端音频"
        } catch {
            phase = .failed
            statusMessage = error.localizedDescription
        }
    }

    public func recover() {
        phase = .recovering
        statusMessage = "正在重新准备网络和音频"
        audio.refreshRoute()
        updateAudioReady()
        #if canImport(Network)
        if signalingController == nil { startDiscovery() }
        #else
        if identity != nil { startDiscovery() }
        #endif
    }

    public func stop() {
        audioReadyTimeoutTask?.cancel()
        audioReadyTimeoutTask = nil
        #if canImport(Network)
        signalingController?.close()
        signalingController = nil
        #endif
        webRTC.close()
        #if canImport(CoreBluetooth)
        bleBootstrap.stop()
        #endif
        #if canImport(Network)
        bonjourTransport.stop()
        peerToPeerTransport.stop()
        discoveredEndpoints.removeAll()
        discoveredPeerToPeerEndpoints.removeAll()
        #endif
        bootstrapRemoteDeviceID = nil
        activeTransport = nil
        activeRemoteDeviceID = nil
        pairingSaveInFlight = false
        Task { await networkBootstrap.close() }
        phase = .offline
        statusMessage = "对讲已结束"
    }

    private func updateAudioReady() {
        guard webRTC.audioReady else { return }
        audioReadyTimeoutTask?.cancel()
        audioReadyTimeoutTask = nil
        if let signalingController, signalingController.phase != .connected {
            signalingController.markMediaConnected()
        }
        guard !pairingSaveInFlight,
              phase != .connected,
              let signalingController,
              let remoteDeviceID = signalingController.remoteDeviceID,
              let remote = remoteCapabilities,
              let transport = activeTransport else {
            phase = .audioReady
            statusMessage = "音频已就绪，正在完成连接"
            return
        }
        pairingSaveInFlight = true
        let remoteNickname = signalingController.remoteNickname.isEmpty
            ? remote.deviceName
            : signalingController.remoteNickname
        Task { @MainActor [weak self] in
            guard let self else { return }
            await self.persistConnectedPeer(
                remoteDeviceID: remoteDeviceID,
                remoteNickname: remoteNickname,
                deviceName: remote.deviceName,
                transport: transport
            )
            self.pairingSaveInFlight = false
        }
    }

    private func scheduleAudioReadyTimeout() {
        audioReadyTimeoutTask?.cancel()
        audioReadyTimeoutTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 10_000_000_000)
            guard !Task.isCancelled, let self, !self.webRTC.audioReady else { return }
            self.phase = .failed
            self.statusMessage = "AUDIO_READY 超时，未确认远端首帧或本地音频路由"
            #if canImport(Network)
            self.signalingController?.close(reason: "AUDIO_READY_TIMEOUT")
            self.signalingController = nil
            #endif
            self.webRTC.close()
        }
    }

    private func handleBootstrap(_ message: BootstrapMessage) {
        #if canImport(Network)
        if let address = message.endpointAddress,
           let portValue = message.endpointPort,
           (1...65535).contains(portValue),
           let port = NWEndpoint.Port(rawValue: UInt16(portValue)),
           let deviceID = bootstrapRemoteDeviceID ?? activeRemoteDeviceID {
            discoveredEndpoints[deviceID] = .hostPort(
                host: NWEndpoint.Host(address),
                port: port
            )
        }
        #endif
        if let hotspot = message.hotspot {
            Task { await receiveAndroidHotspot(hotspot) }
        }
    }

    private func record(announcement: BootstrapAnnouncement) {
        bootstrapRemoteDeviceID = announcement.deviceID
        if activeRemoteDeviceID == nil { activeRemoteDeviceID = announcement.deviceID }
        let deviceName = announcement.platform == .ios ? "iPhone" : "Android"
        guard let capabilities = try? RuntimeCapabilities(
            platform: announcement.platform,
            platformVersion: "unknown",
            deviceName: deviceName,
            capabilities: announcement.capabilities,
            networkRole: announcement.networkRole,
            tcpPort: announcement.tcpPort
        ),
        let peer = try? NearbyPeer(
            deviceID: announcement.deviceID,
            sessionID: announcement.sessionID,
            nickname: deviceName,
            deviceName: deviceName,
            capabilities: capabilities
        ) else { return }
        upsert(peer)
    }

    #if canImport(Network)
    private func record(
        advertisement: BonjourServiceAdvertisement,
        endpoint: NWEndpoint,
        isPeerToPeer: Bool
    ) {
        if activeRemoteDeviceID == nil { activeRemoteDeviceID = advertisement.deviceID }
        if isPeerToPeer {
            discoveredPeerToPeerEndpoints[advertisement.deviceID] = endpoint
        } else {
            discoveredEndpoints[advertisement.deviceID] = endpoint
        }
        guard let capabilities = try? RuntimeCapabilities(
            platform: advertisement.platform,
            platformVersion: "unknown",
            deviceName: advertisement.deviceName,
            capabilities: advertisement.capabilities,
            networkRole: advertisement.networkRole,
            tcpPort: advertisement.tcpPort
        ),
        let peer = try? NearbyPeer(
            deviceID: advertisement.deviceID,
            sessionID: advertisement.sessionID,
            nickname: advertisement.nickname,
            deviceName: advertisement.deviceName,
            capabilities: capabilities
        ) else { return }
        upsert(peer)
    }

    private func record(signalingHello: (
        deviceID: String,
        nickname: String,
        deviceName: String,
        capabilities: Set<String>
    )) {
        let networkCapabilities = Set(
            signalingHello.capabilities.compactMap(NetworkCapability.init(rawValue:))
        )
        let knownPlatform = nearbyPeers.first {
            $0.deviceID == signalingHello.deviceID
        }?.capabilities.platform
        let platform = knownPlatform ?? (
            networkCapabilities.contains(.iosPeerToPeer) ||
            networkCapabilities.contains(.iosPersonalHotspotManual)
                ? .ios
                : .android
        )
        guard let capabilities = try? RuntimeCapabilities(
            platform: platform,
            platformVersion: "unknown",
            deviceName: signalingHello.deviceName.isEmpty ? "远端设备" : signalingHello.deviceName,
            capabilities: networkCapabilities,
            networkRole: .either,
            tcpPort: 8890
        ) else { return }
        remoteCapabilities = capabilities
    }
    #endif

    private func upsert(_ peer: NearbyPeer) {
        guard peer.deviceID != identity?.deviceID else { return }
        nearbyPeers.removeAll { $0.deviceID == peer.deviceID }
        nearbyPeers.append(peer)
        nearbyPeers.sort { $0.nickname.localizedCaseInsensitiveCompare($1.nickname) == .orderedAscending }
        #if canImport(Network)
        if phase == .discovering,
           signalingController == nil,
           discoveredEndpoints[peer.deviceID] != nil ||
                discoveredPeerToPeerEndpoints[peer.deviceID] != nil,
           pairings.contains(where: { $0.remoteDeviceID == peer.deviceID }) {
            connect(to: peer)
        }
        #endif
    }

    private func handleDiscoveryError(_ error: Error) {
        guard phase != .offline else { return }
        statusMessage = "附近设备发现失败：\(error.localizedDescription)"
    }

    private func phaseFor(_ error: Error) -> SessionPhase {
        guard let error = error as? MotoComError else { return .failed }
        switch error {
        case .permissionDenied: return .permissionBlocked
        case .manualActionRequired: return .manualActionRequired
        default: return .failed
        }
    }
}
