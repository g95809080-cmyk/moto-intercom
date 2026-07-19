## Why

Recovery currently copies the previous attempt's transport order and reuses the
normal T+5 fallback milestone. That can retry a transport other than the last
successful path first and leaves only part of the intended fast window after an
additional Service backoff.

## What Changes

- Create each recovery attempt with the last successful transport first while
  preserving the same TargetLock and available transport set.
- Report each rebuilt adapter independently and open a transport only after
  that adapter's mandatory startup cleanup completes, without the normal
  failed-attempt discovery backoff.
- Schedule the recovery-only fallback exactly three seconds after the immutable
  recovery attempt start, while keeping the existing total T+10 deadline.
- Open the alternate transport for the same attempt and target when the fast
  window expires; stale, replaced, terminal, or late milestones remain inert.
- Add deterministic clock/transport/callback coverage for the production
  Service-to-adapter readiness ordering and update Sprint 4 and Release
  Candidate evidence without claiming physical OEM/RF validation.

## Capabilities

### New Capabilities

- `three-second-recovery-fallback`: Defines last-successful-transport recovery,
  the immutable three-second fast window, same-target alternate fallback, stale
  callback exclusion, and development/Release Candidate verification gates.

### Modified Capabilities

None. KUM-32 target locking, normal T0/T+5 transport race, T+6 optimization,
T+10 total deadline, Signaling v2, and media ownership remain unchanged.

## Impact

- Existing connection Coordinator recovery-plan and milestone creation.
- Service recovery restart timing after mandatory transport cleanup.
- Deterministic Coordinator/orchestrator and restart-delay tests.
- Sprint 4 verification index and Release Candidate physical queue.
- No protocol, identity, database, pairing, UI, notification, WebRTC ownership,
  dependency, signing, deployment, or production-release change.
