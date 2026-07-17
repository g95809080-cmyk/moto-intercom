## Why

KUM-27B needs one immutable connection-attempt vocabulary and a deterministic
monotonic time seam before ownership can move safely. B1 introduces that pure
domain foundation without connecting it to the current Android runtime or
changing the existing 10-second behavior.

## What Changes

- Add immutable value types for attempt identity, target identity, trigger,
  preferred transport, a single-transport `ChannelPlan`, monotonic deadline,
  and terminal outcome.
- Add a monotonic clock abstraction plus a deterministic fake clock for pure
  JVM boundary tests.
- Add matching/staleness helpers and tests for attempt, target, and deadline
  boundaries without routing any existing callback through the new model.
- Keep B2-B6 deferred. This change does not migrate attempt creation,
  termination, deadline ownership, callbacks, candidates, adapters, or winner
  selection, and it does not start KUM-28.

## Capabilities

### New Capabilities

- `connection-attempt-domain-model`: Defines the B1 immutable attempt model,
  single-transport plan, monotonic deadline semantics, terminal outcomes, and
  deterministic clock test support.

### Modified Capabilities

None.

## Impact

- Adds pure Kotlin production types and JVM tests under the existing Android
  app module.
- Adds no Android framework, Service, Socket, Wi-Fi Direct, WebRTC, UI,
  database, identity, pairing, notification, Gradle, dependency, permission,
  or protocol changes.
- Leaves `SessionOrchestrator`, `SignalingControlCoordinator`,
  `IntercomService`, transport adapters, current deadline scheduling, and all
  runtime call paths unchanged.
