import Foundation
import Combine

#if canImport(AVFAudio)
import AVFAudio
#endif

public enum AudioRoute: String, Equatable, Sendable {
    case bluetoothHFP = "BLUETOOTH_HFP"
    case phoneSpeaker = "PHONE_SPEAKER"
    case phoneReceiver = "PHONE_RECEIVER"
    case unavailable = "UNAVAILABLE"
}

@MainActor
public final class AudioSessionController: NSObject, ObservableObject {
    @Published public private(set) var route: AudioRoute = .unavailable
    @Published public private(set) var isActive = false
    @Published public private(set) var isInterrupted = false
    @Published public private(set) var readiness = AudioReadiness()

    public var onRouteChanged: (@MainActor (AudioRoute) -> Void)?
    public var onInterruptionChanged: (@MainActor (Bool) -> Void)?

    #if canImport(AVFAudio)
    private let audioSession = AVAudioSession.sharedInstance()
    #endif
    private var observers = [NSObjectProtocol]()

    public override init() {
        super.init()
        #if canImport(AVFAudio)
        let center = NotificationCenter.default
        observers.append(center.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            Task { @MainActor [weak self] in
                self?.handleInterruption(notification)
            }
        })
        observers.append(center.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.refreshRoute()
            }
        })
        observers.append(center.addObserver(
            forName: AVAudioSession.mediaServicesWereResetNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.isActive = false
                self?.readiness.localRouteReady = false
                self?.refreshRoute()
            }
        })
        #endif
    }

    deinit {
        observers.forEach(NotificationCenter.default.removeObserver)
    }

    public func activate() throws {
        #if canImport(AVFAudio)
        guard audioSession.recordPermission == .granted else {
            throw MotoComError.permissionDenied("Microphone permission is required for intercom audio")
        }
        try audioSession.setCategory(
            .playAndRecord,
            mode: .voiceChat,
            options: [.allowBluetoothHFP, .defaultToSpeaker]
        )
        try audioSession.setActive(true, options: [])
        restorePreferredRoute()
        #endif
        isActive = true
        refreshRoute()
    }

    public func requestMicrophonePermission() async -> Bool {
        #if canImport(AVFAudio)
        switch audioSession.recordPermission {
        case .granted:
            return true
        case .denied:
            return false
        case .undetermined:
            return await withCheckedContinuation { continuation in
                audioSession.requestRecordPermission { granted in
                    continuation.resume(returning: granted)
                }
            }
        @unknown default:
            return false
        }
        #else
        return false
        #endif
    }

    public func deactivate() {
        #if canImport(AVFAudio)
        try? audioSession.setActive(false, options: [.notifyOthersOnDeactivation])
        #endif
        isActive = false
        readiness.localRouteReady = false
        route = .unavailable
    }

    public func markRemoteTrackPresent() {
        readiness.remoteTrackPresent = true
    }

    public func markRemoteFirstFrameReceived() {
        readiness.remoteFirstFrameReceived = true
    }

    public func clearRemoteAudio() {
        readiness.remoteTrackPresent = false
        readiness.remoteFirstFrameReceived = false
    }

    public var isAudioReady: Bool { readiness.isReady }

    public func refreshRoute() {
        #if canImport(AVFAudio)
        if isActive { restorePreferredRoute() }
        let current = audioSession.currentRoute
        if current.outputs.contains(where: { $0.portType == .bluetoothHFP }) ||
            current.inputs.contains(where: { $0.portType == .bluetoothHFP }) {
            route = .bluetoothHFP
        } else if current.outputs.contains(where: { $0.portType == .builtInSpeaker }) {
            route = .phoneSpeaker
        } else if current.outputs.contains(where: { $0.portType == .builtInReceiver }) {
            route = .phoneReceiver
        } else {
            route = .unavailable
        }
        readiness.localRouteReady = isActive && route != .unavailable && !isInterrupted
        #else
        route = .unavailable
        readiness.localRouteReady = false
        #endif
        onRouteChanged?(route)
    }

    #if canImport(AVFAudio)
    private func restorePreferredRoute() {
        if let hfp = audioSession.availableInputs?.first(where: { $0.portType == .bluetoothHFP }) {
            try? audioSession.setPreferredInput(hfp)
        } else {
            try? audioSession.overrideOutputAudioPort(.speaker)
        }
    }
    #endif

    private func handleInterruption(_ notification: Notification) {
        #if canImport(AVFAudio)
        guard let raw = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        switch type {
        case .began:
            isInterrupted = true
            readiness.phoneInterrupting = true
            readiness.localRouteReady = false
            onInterruptionChanged?(true)
        case .ended:
            isInterrupted = false
            readiness.phoneInterrupting = false
            refreshRoute()
            onInterruptionChanged?(false)
        @unknown default:
            break
        }
        #endif
    }
}
