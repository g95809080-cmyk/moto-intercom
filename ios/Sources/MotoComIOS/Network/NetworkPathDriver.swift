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
    private var monitor: NWPathMonitor?
    public init() {}
    public func start(_ callback: @escaping @Sendable (Bool, TimeInterval) -> Void) {
        stop()
        let monitor = NWPathMonitor(); self.monitor = monitor
        monitor.pathUpdateHandler = { path in
            callback(path.status == .satisfied, ProcessInfo.processInfo.systemUptime)
        }
        monitor.start(queue: DispatchQueue(label: "com.motocom.path-monitor"))
    }
    public func stop() { monitor?.cancel(); monitor = nil }
}
#endif
