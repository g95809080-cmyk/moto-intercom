## Why

KUM-27B needs one immutable connection-attempt vocabulary and a deterministic
monotonic time seam before ownership can move safely. B1 completed that pure
domain foundation, and B2 moved production attempt creation plus first-terminal
ownership into the existing `SignalingControlCoordinator`. B3 removes the
remaining mutable total-deadline paths and the false attempt used while an
unpaired inbound request awaits confirmation.

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
- Inject the B1 `MonotonicClock` into the existing Coordinator and make it the
  only production source of every attempt's immutable 10-second deadline.
- Remove deadline inputs from outbound and recovery events, remove Service
  deadline creation, and replace deadline rebasing with one explicit
  `ScheduleAttemptDeadline` effect per newly created attempt.
- Represent an unpaired inbound request awaiting confirmation as a
  Coordinator-owned `PendingInboundRequest`, not a `ConnectionAttempt`; create
  the inbound attempt only after a current, valid local acceptance.
- Keep B4-B6 deferred. B3 does not migrate adapter/callback ownership or
  remaining-time contracts, change candidate/winner behavior, or start KUM-28.

## Capabilities

### New Capabilities

- `connection-attempt-domain-model`: Defines the B1 immutable attempt model,
  single-transport plan, monotonic deadline semantics, terminal outcomes, and
  deterministic clock test support.
- `connection-attempt-coordinator-ownership`: Defines the B2 production
  creation owner, current-attempt record, first-terminal-wins contract, and B3
  immutable total-deadline/pending-inbound cutover.

### Modified Capabilities

None. The approved KUM-27A ownership contract remains authoritative.

## Impact

- Evolves the existing `SignalingControlCoordinator` in place; no second
  Coordinator is created.
- Changes attempt-related event/effect ownership in `SessionOrchestrator`,
  `IntercomStateMachine`, `SignalingControlCoordinator`, and `IntercomService`
  while preserving Service as the physical effect executor.
- Preserves the 10-second attempt budget and 15-second human confirmation
  window, but makes attempt deadlines immutable and strictly monotonic.
- Leaves adapters, callback routing beyond the B3 deadline boundary,
  Signaling v2, TargetLock, WebRTC, UI, database, pairing, identity,
  notification, Gradle, permissions, and dependencies unchanged.
