import Foundation

@MainActor
public protocol SessionDeadlineToken: AnyObject { func cancel() }

@MainActor
public protocol SessionDeadlineScheduling: AnyObject {
    var now: TimeInterval { get }
    func schedule(at deadline: TimeInterval, _ action: @escaping @MainActor () -> Void) -> SessionDeadlineToken
}

@MainActor
public final class SessionDeadlineScheduler: SessionDeadlineScheduling {
    public var now: TimeInterval { ProcessInfo.processInfo.systemUptime }
    public init() {}
    public func schedule(at deadline: TimeInterval, _ action: @escaping @MainActor () -> Void) -> SessionDeadlineToken {
        let delay = max(0, deadline - now)
        let task = Task { @MainActor in
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            guard !Task.isCancelled else { return }
            action()
        }
        return TaskDeadlineToken(task)
    }
}

@MainActor
private final class TaskDeadlineToken: SessionDeadlineToken {
    private let task: Task<Void, Never>
    init(_ task: Task<Void, Never>) { self.task = task }
    func cancel() { task.cancel() }
}
