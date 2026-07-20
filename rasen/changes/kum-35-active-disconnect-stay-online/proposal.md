## Why

The product currently exposes one online action that always performs a full runtime stop, even though the Coordinator already has attempt-scoped `DISCONNECT` ordering. A rider therefore cannot end the current conversation while keeping the Service, discovery, notification, and hot audio runtime available for the next rider.

## What Changes

- Route the online primary action to an exact `DisconnectRequested` while a current connection attempt exists, and reserve full Stop for the online idle/reset state.
- Make local and remote explicit `DISCONNECT` terminate the exact active attempt into `DISCOVERING` without treating the event as an unexpected recovery failure.
- Add one Coordinator-authorized physical effect that releases the exact signaling/WebRTC/selected-transport session while preserving the runtime-owned Service, discovery adapters, foreground notification, presence aggregation, and KUM-37 audio session.
- Keep unexpected signaling/WebRTC loss on the existing KUM-33/KUM-34 recovery path and keep full Stop as the only `STOPPING -> OFFLINE` path.
- Add deterministic Coordinator, Service-effect, action-policy, stale-callback, and local/remote/unexpected-disconnect tests, followed by full Gradle, emulator, CI, and fixed-SHA review gates.
- Keep OEM/RF/Bluetooth SCO/acoustic/power/thermal/background checks `DEFERRED_TO_RELEASE_CANDIDATE`.

## Capabilities

### New Capabilities

- `active-session-disconnect`: Defines local and remote explicit disconnect semantics, exact attempt-resource release, online discovery continuity, full-stop separation, stale-event exclusion, and automated delivery gates.

### Modified Capabilities

- None.

## Impact

- Product policy and effects: `SignalingControlCoordinator`, `SessionEvent`, `SessionEffect`, and `SessionOrchestrator` tests.
- Runtime execution: `IntercomService`, exact media/control cleanup, LAN/Wi-Fi Direct attempt leases, status, and notification continuity.
- UI: state-sensitive primary action and labels in `MainActivity` and `MainScreen`.
- Verification: focused JVM/instrumentation coverage, reusable emulator matrix, Sprint 4 evidence, Draft PR, CI, architecture review, and Linear.
- No Signaling v2 wire-shape, TargetLock, deadline, winner, pairing/database, dependency, permission, signing, deployment, or release change.
