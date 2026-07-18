## Why

KUM-27B needs one immutable connection-attempt vocabulary and a deterministic
monotonic time seam before ownership can move safely. B1 completed that pure
domain foundation. B2 now removes the live outbound/recovery creation and
attempt-termination split between Service, reducer, and the existing
`SignalingControlCoordinator` without changing deadline behavior.

## What Changes

- Add immutable value types for attempt identity, target identity, trigger,
  preferred transport, a single-transport `ChannelPlan`, monotonic deadline,
  and terminal outcome.
- Add a monotonic clock abstraction plus a deterministic fake clock for pure
  JVM boundary tests.
- Add matching/staleness helpers and tests for attempt, target, and deadline
  boundaries without routing any existing callback through the new model.
- Inject a deterministic attempt-ID factory into the existing Coordinator and
  make it create outbound and recovery attempts from intent data.
- Make the same Coordinator track the current logical attempt and preserve the
  first terminal outcome for timeout, cancellation, failure, disconnect, and
  success ordering.
- Route production attempt-ending events through the Coordinator before the
  generic reducer; `SessionOrchestrator` remains the only product-state writer.
- Keep B3-B6 deferred. B2 does not move the absolute deadline source, remove
  rebasing, replace the inbound confirmation sentinel, route adapter callbacks,
  change candidate/winner behavior, or start KUM-28.

## Capabilities

### New Capabilities

- `connection-attempt-domain-model`: Defines the B1 immutable attempt model,
  single-transport plan, monotonic deadline semantics, terminal outcomes, and
  deterministic clock test support.
- `connection-attempt-coordinator-ownership`: Defines the B2 production
  creation owner, current-attempt record, and first-terminal-wins contract.

### Modified Capabilities

None. The approved KUM-27A ownership contract remains authoritative.

## Impact

- Evolves the existing `SignalingControlCoordinator` in place; no second
  Coordinator is created.
- Changes attempt-related event ownership in `SessionOrchestrator`,
  `IntercomStateMachine`, and `IntercomService` while preserving Service as the
  physical effect executor.
- Leaves the current absolute deadline values, 10-second constants, deadline
  scheduler, rebasing paths, inbound confirmation representation, adapters,
  Signaling v2, TargetLock, WebRTC, UI, database, pairing, identity,
  notification, Gradle, permissions, and dependencies unchanged in B2.
