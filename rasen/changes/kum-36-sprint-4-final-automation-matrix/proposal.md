## Why

Sprint 4 now contains the approved target-locked recovery, hot audio lifetime,
three-second fallback, repeated-failure wireless reset, and active-disconnect
behavior, but its final Exit Criteria still lack one bounded, reproducible
acceptance matrix. KUM-36 must prove those already-approved behaviors together,
including a faster third node, three complete recovery failures, stale events,
process restart, and synthetic PCM recovery, without treating unavailable phone
hardware as passed.

## What Changes

- Add deterministic JVM acceptance coverage that follows one A-B recovery while
  C responds first, preserves B's TargetLock and immutable deadline, and permits
  exactly one B media owner.
- Add deterministic repeated-failure, cancel, stale-callback, reset-completion,
  and cleanup assertions across the complete Sprint 4 recovery episode.
- Add an androidTest-only Sprint 4 acceptance fixture and a reusable
  `sprint4-final` three-emulator scenario that composes recovery timing, reset,
  active disconnect, synthetic PCM, shared networking, network fault/recovery,
  and process restart evidence.
- Update the Sprint 4 verification index and Release Candidate physical plan so
  automated evidence and hardware deferrals are explicit and non-overlapping.
- Run full Gradle, three-emulator, CI, fixed-SHA architecture review, PR, merge,
  exact-main CI, and Linear closure gates.

## Capabilities

### New Capabilities

- `sprint-4-final-automation-matrix`: Defines the deterministic and emulator
  acceptance contract for KUM-36 and the evidence required to close Sprint 4.

### Modified Capabilities

- None. This change verifies approved Sprint 4 behavior and does not introduce a
  new product state, protocol, database contract, or runtime owner.

## Impact

- JVM and androidTest acceptance fixtures for Coordinator, TargetLock, deadline,
  reset, stale-event, media-owner, audio, and cleanup invariants.
- `scripts/emulator/run-scenario.ps1` gains one bounded aggregate scenario and
  includes it in the full matrix.
- Sprint 4 and Release Candidate verification documentation gains final evidence
  and explicit `DEFERRED_TO_RELEASE_CANDIDATE` rows.
- No production release, signing, deployment, protocol/schema, identity,
  pairing/database, dependency, permission, or product-behavior change is
  planned.
