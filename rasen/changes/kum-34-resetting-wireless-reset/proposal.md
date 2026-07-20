## Why

Recovery currently ends after one exhausted attempt and returns directly to ordinary discovery, while the existing `RESETTING` state and `RecoveryExhausted` event are not connected to runtime cleanup. This loses the original-target recovery episode before the Sprint 4 rule can count three final failures and rebuild a contaminated wireless environment.

## What Changes

- Keep one target-bound recovery episode across fresh attempts, with a new attempt ID and immutable T+10 deadline for every retry.
- Count only terminal exhaustion of the complete recovery attempt; transport-local failures and duplicate or stale callbacks do not increment the streak.
- Retry the same `TargetLock` and transport order after the first and second final failures, using the existing bounded reconnect backoff while cleanup time continues to consume the new attempt budget.
- Enter visible `RESETTING` on the third consecutive final failure for that target.
- Bind reset completion to the exhausted attempt identity so stale callbacks or an old reset completion cannot advance product state.
- Reuse the production Wi-Fi Direct close chain (`cancelConnect` -> `removeGroup` -> `clearServiceRequests` -> `clearLocalServices` -> channel `close`), bound every Android action with a watchdog, and close LAN/NSD/UDP/Socket/delayed work before rebuilding discovery components.
- Reconcile cleanup completion against the latest Coordinator attempt/reset request so a deadline that expires during asynchronous teardown cannot strand the runtime or restart an obsolete attempt.
- Return to idle discovery only after reset cleanup and component rebuild complete; clear the recovery-failure streak on successful connection or completed reset.
- Preserve the KUM-27 single Coordinator/product-state writer boundary, KUM-32 TargetLock, KUM-33 T+3/T+10 timing, and KUM-37 hot audio owner.
- Keep KUM-35 active disconnect and KUM-36 final acceptance out of scope. Physical OEM/RF/SCO/acoustic/power/background checks remain `DEFERRED_TO_RELEASE_CANDIDATE`.

## Capabilities

### New Capabilities

- `repeated-recovery-reset`: Target-scoped consecutive recovery failure counting, bounded same-target retries, exact reset identity, ordered wireless teardown/rebuild, stale callback rejection, and return to idle discovery.

### Modified Capabilities

- None.

## Impact

- Product state and effects: `IntercomState`, `SessionEvent`, `SessionEffect`, and the existing Coordinator transition paths.
- Runtime execution: `IntercomService` resource cleanup/restart handling and existing Wi-Fi Direct/LAN close implementations.
- Persistence: no schema or pairing-record write changes; the consecutive streak belongs to the current runtime recovery episode and product state.
- Tests and evidence: deterministic Coordinator/orchestrator/resource-order tests, full JVM/lint/build gates, reusable emulator scenarios, fixed-SHA architecture review, CI, PR, and Linear evidence.
- No protocol, identity, TargetLock, WebRTC/audio ownership, database schema, dependency, signing, deployment, or release change.
