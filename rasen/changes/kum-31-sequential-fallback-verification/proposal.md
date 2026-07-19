## Why

The merged T0/T+5 race keeps retrying Wi-Fi Direct when Android reports the
exact targeted fallback as busy, but it neither retires the LAN targeted path
nor lets the Coordinator choose a sequential fallback. KUM-31 therefore cannot
prove its approved non-overlap behavior on devices where the two transports
cannot be active together.

## What Changes

- Treat a current targeted Wi-Fi Direct `BUSY` callback during the T+5 fallback
  as a conservative, attempt-local overlap-unavailable signal.
- Route that typed signal through `SessionOrchestrator`; let the existing
  Coordinator decide whether to retire the preferred transport and retry the
  same fallback without changing attempt identity, target, or total deadline.
- Reject duplicate, stale, expired, wrong-transport, and post-candidate signals.
- Make Service execute ordered retire/open effects while adapters remain
  bounded physical executors.
- Add deterministic coverage for sequential switch, idempotency, stale events,
  deadline, candidate ownership, cleanup, failure, recovery, and no wrong target.
- Complete the reusable emulator matrix and Release Candidate physical plan.
- Keep OEM, RF, Bluetooth, acoustic, power, and thermal evidence
  `DEFERRED_TO_RELEASE_CANDIDATE`.

## Capabilities

### New Capabilities

- `sequential-transport-fallback`: Defines exact-attempt overlap-unavailable
  signaling, Coordinator-owned preferred retirement, same-attempt fallback
  retry, stale-event exclusion, and development/Release Candidate gates.

### Modified Capabilities

None. The merged T0/T+5 schedule, one-second optimization, total deadline,
identity, signaling, and unique media-owner requirements remain unchanged.

## Impact

- Coordinator events, race bookkeeping, effects, and deterministic JVM tests.
- Wi-Fi Direct targeted failure routing and Service effect execution.
- Sprint 3 verification evidence and the KUM-31 Release Candidate queue.
- No protocol schema, database, identity, pairing, UI, notification, WebRTC,
  dependency, signing, deployment, or production-release change.
