## ADDED Requirements

### Requirement: Actual closed or revoked channels cannot re-enter the current group
Runtime SHALL check actual channel/adapter ownership and the exact channel/ingress pair before installation and final Main delivery. Rejected authentication SHALL release only its own resources. Current pending client loss SHALL reach the existing writer recovery event.

#### Scenario: Close occurs before authentication or before Main delivery
- **WHEN** an actual authenticated Socket channel closes before Runtime installation or queued Main authentication
- **THEN** no closed channel or ingress remains installed, no stale Authenticated/Control reaches the writer, and an unexpected current client loss enters recovery

#### Scenario: An old attempt completes after replacement or stop
- **WHEN** the actual adapter is revoked by replacement or stop before a late callback
- **THEN** the late callback only releases its own channel/queue and cannot change a healthy current member or writer

### Requirement: All locally owned connection resources are released
SocketClient SHALL release its locally constructed connection even when parent close precedes channel field publication. Concurrent control-registry cleanup SHALL finish without relying on a stale collection size.

#### Scenario: Close races with field publication
- **WHEN** actual parent close wins while the connection constructor returns
- **THEN** the late connection, key material and executors are released exactly through their own cleanup paths

#### Scenario: The final registry entry is removed during snapshot
- **WHEN** an actual onClosed worker removes the final CHM channel while closeControl snapshots it
- **THEN** all remaining cleanup and stop completion execute for both immediate and terminal-flush stop
