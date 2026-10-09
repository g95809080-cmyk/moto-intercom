import Foundation
#if canImport(Network)
import Network

@MainActor
public protocol NetworkPathDriving: AnyObject {
    func start(_ callback: @escaping @Sendable (Bool, TimeInterval) -> Void)
    func stop()
}
@MainActor
public final class NetworkPathDriver: NetworkPathDriving {
    /// Main owns the Run; its native callback reads only immutable monitor
    /// identity and this lock-protected revocation bit.
    private final class Run: @unchecked Sendable {
        let monitor = NWPathMonitor()
        private let lock = NSLock()
        private var revoked = false
        func revoke() { lock.lock(); revoked = true; lock.unlock() }
        func isCurrent(_ monitor: NWPathMonitor) -> Bool {
            lock.lock(); defer { lock.unlock() }; return !revoked && self.monitor === monitor
        }
    }
    private var current: Run?
    public init() {}
    public func start(_ callback: @escaping @Sendable (Bool, TimeInterval) -> Void) {
        stop()
        let run = Run(); current = run; let monitor = run.monitor
        monitor.pathUpdateHandler = { [weak run, weak monitor] path in
            guard let run, let monitor, run.isCurrent(monitor) else { return }
            callback(path.status == .satisfied, ProcessInfo.processInfo.systemUptime)
        }
        monitor.start(queue: DispatchQueue(label: "com.motocom.path-monitor"))
    }
    public func stop() {
        let old = current; current = nil
        old?.revoke(); old?.monitor.cancel()
    }
}
#endif
