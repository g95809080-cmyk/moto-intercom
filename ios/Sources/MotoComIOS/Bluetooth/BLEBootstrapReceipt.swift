import Foundation

public enum BLEBootstrapRole: String, Hashable, Sendable { case centralClient, peripheralServer }
public struct BLEBootstrapSourceKey: Hashable, Sendable {
    public let runID: UUID
    public let role: BLEBootstrapRole
    public let managerInstanceID: UUID
    public let peerIdentifier: UUID
    public let peerInstanceID: UUID
    public let peerLeaseID: UUID
    public init(runID: UUID, role: BLEBootstrapRole, managerInstanceID: UUID, peerIdentifier: UUID, peerInstanceID: UUID, peerLeaseID: UUID) {
        self.runID = runID; self.role = role; self.managerInstanceID = managerInstanceID
        self.peerIdentifier = peerIdentifier; self.peerInstanceID = peerInstanceID; self.peerLeaseID = peerLeaseID
    }
}

/// Lock-protected revocation is the only shared mutable state. Main's claim
/// is synchronous metadata only: no publish, IO, clock/factory, ack or await.
/// @unchecked does not authorize transferring CoreBluetooth objects across queues.
public final class BLEBootstrapSourceLease: @unchecked Sendable {
    public let key: BLEBootstrapSourceKey
    private let lock = NSLock()
    private var revoked = false
    public init(key: BLEBootstrapSourceKey) { self.key = key }
    public func revoke() { lock.lock(); revoked = true; lock.unlock() }
    @MainActor public func withCurrent<T>(_ claim: () -> T?) -> T? {
        lock.lock(); defer { lock.unlock() }
        guard !revoked else { return nil }; return claim()
    }
}

/// Exact delivery accounting remains reserved while a queued Task retains
/// the payload. Invalidation never releases that reservation before acknowledge.
public final class BLEBootstrapDeliveryLease: @unchecked Sendable {
    public let id: UUID
    private let lock = NSLock()
    private var release: (@Sendable () -> Void)?
    private var invalidated = false
    public init(id: UUID = UUID(), release: @escaping @Sendable () -> Void) { self.id = id; self.release = release }
    public func acknowledge() {
        lock.lock(); let action = release; release = nil; lock.unlock(); action?()
    }
    public func invalidate() { lock.lock(); invalidated = true; lock.unlock() }
    @MainActor public func withCurrent<T>(_ claim: () -> T?) -> T? {
        lock.lock(); defer { lock.unlock() }
        guard !invalidated, release != nil else { return nil }; return claim()
    }
    deinit { acknowledge() }
}
public struct BLEBootstrapReceipt: Sendable {
    public let source: BLEBootstrapSourceLease
    public let delivery: BLEBootstrapDeliveryLease
    public init(source: BLEBootstrapSourceLease, delivery: BLEBootstrapDeliveryLease) { self.source = source; self.delivery = delivery }
    @MainActor public func withCurrent<T>(_ claim: () -> T?) -> T? {
        source.withCurrent { delivery.withCurrent(claim) }
    }
}
public enum BLEBootstrapEvent: Sendable {
    case state(UUID, BLEBootstrapState)
    case announcement(BLEBootstrapReceipt, BootstrapAnnouncement)
    case message(BLEBootstrapReceipt, BootstrapMessage)
    case sourceEnded(BLEBootstrapSourceKey, String?)
}
public enum BLEBootstrapSendFailure: Error { case staleSource, notReady, encoding(String) }
public protocol BLEBootstrapSource: AnyObject {
    func start(runID: UUID, announcement: BootstrapAnnouncement, events: @escaping @Sendable (BLEBootstrapEvent) -> Void)
    func stop(runID: UUID)
    func send(_ message: BootstrapMessage, to source: BLEBootstrapSourceKey, completion: @escaping @Sendable (Result<Void, BLEBootstrapSendFailure>) -> Void)
}
