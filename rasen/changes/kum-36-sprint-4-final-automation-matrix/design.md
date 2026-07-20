## Context

KUM-37 and KUM-32 through KUM-35 implement the approved Sprint 4 behavior. The
existing `SessionOrchestrator` remains the sole product-state writer and the
existing `SignalingControlCoordinator` owns attempt identity, terminal outcome,
TargetLock, immutable monotonic deadline, recovery fallback, winner selection,
and repeated-failure reset transitions. Existing focused JVM and instrumentation
tests prove each slice independently, while the emulator scripts already provide
shared networking, NSD/Socket exchange, synthetic PCM, fault injection, restart,
and evidence collection.

KUM-36 is an acceptance and evidence change. Its purpose is to bind those seams
into one reproducible matrix, close any ordinary implementation defect exposed by
that matrix, and keep hardware-only claims in the Release Candidate queue.

## Goals / Non-Goals

**Goals:**

- Prove A remains locked to B while C responds first during recovery, including
  visible B recovery identity, the T+3 fallback boundary, immutable T+10 total
  deadline, stale/wrong-target rejection, and one B media owner.
- Prove three complete final recovery failures enter exact `RESETTING`, required
  cleanup ordering completes, stale events cannot take over, and exact reset
  completion returns the same runtime to `DISCOVERING`.
- Prove user cancellation, process restart, synthetic PCM pause/recovery/stop,
  no second media stream, and resource cleanup through deterministic and emulator
  evidence.
- Produce a complete Sprint 4 evidence index and RC physical-test plan with every
  unexecuted hardware row marked `DEFERRED_TO_RELEASE_CANDIDATE`.

**Non-Goals:**

- No new product state, Coordinator, recovery policy, timeout, retry count,
  transport race, TargetLock, winner rule, or audio owner.
- No real Wi-Fi Direct/RF/OEM/SCO/acoustic/power claim from ATD emulators.
- No Signaling v2, WebRTC contract, database/pairing/identity, dependency,
  permission, signing, deployment, or release change.
- No later-Sprint implementation inside the KUM-36 branch.

## Decisions

1. **Use existing authorities, not a test-only product model.** Deterministic
   scenarios drive the real `SessionOrchestrator`/`SignalingControlCoordinator`
   domain events with fake monotonic time and immutable test channels. Assertions
   observe emitted effects, state, TargetLock, deadline, and winner identity.

2. **Add one composite JVM boundary without duplicating every focused test.** The
   KUM-36 suite covers cross-feature ordering and references the existing focused
   suites in the final gate. Socket-level third-node rejection remains covered by
   `Kum26LogicalNodeAcceptanceTest`; the new suite proves the complete Sprint 4
   product transition around that admission boundary.

3. **Keep Android acceptance deterministic.** The androidTest fixture runs the
   real domain/Coordinator code on every emulator and records structured KUM-36
   evidence. It does not depend on emulator Wi-Fi Direct radio behavior or claim
   that AOSP ATD reproduces OEM hardware.

4. **Aggregate, do not replace, the emulator matrix.** A new `sprint4-final`
   scenario explicitly runs the KUM-36 instrumentation plus existing recovery,
   reset, active-disconnect, synthetic-audio, fault/recovery, and restart seams.
   `all` includes it while retaining smoke, NSD, hot-audio, and earlier scenarios.
   Every command accepts only explicit `emulator-*` serials.

5. **Separate automated evidence from physical acceptance.** Shared-network and
   fake transport tests prove protocol/domain behavior. OEM Wi-Fi Direct, RF,
   background limits, Bluetooth SCO, microphones/speakers, hardware AEC, human
   listening, power, and thermal checks remain
   `DEFERRED_TO_RELEASE_CANDIDATE`, never PASS.

6. **Treat failures according to the active authorization.** Ordinary test,
   build, lint, emulator, or review defects inside the approved Sprint 4 behavior
   are repaired and revalidated. Any required product/architecture change or new
   high-risk residual risk pauses the goal for user direction.

## Verification Plan

1. Strictly validate all four Rasen artifacts at base `178e076`.
2. Add failing KUM-36 JVM and instrumentation acceptance tests, then make only
   the minimum approved-scope changes required to pass.
3. Run focused JVM tests, full JVM, lint, debug/test APK builds, and script parse
   checks.
4. Run focused `sprint4-final` and full three-emulator matrices, scan logs for
   crash/ANR/instrumentation/test failures, inspect screenshots, and hash evidence.
5. Push one branch, open one Draft PR, wait exact-Head CI, complete fixed-SHA
   read-only architecture review, remediate P0/P1, merge with a merge commit,
   retain the remote branch, and verify exact-main CI.

## Risks / Trade-offs

- **ATD cannot prove OEM Wi-Fi Direct or RF behavior.** -> Use real domain/fake
  transport and shared-network evidence, record the exact limitation, and retain
  mandatory physical rows for RC.
- **An aggregate script could hide which seam failed.** -> Keep each invocation in
  a separately named result file and fail immediately on a missing `OK (` marker.
- **A final matrix could become a second specification.** -> Bind assertions to
  existing KUM-32 through KUM-35 contracts and do not add new runtime policy.
- **Repeated tests increase runtime.** -> Keep one composite acceptance class and
  reuse the existing three-node cluster instead of creating more devices.

## Rollback

Revert the eventual KUM-36 merge commit. The change is verification-only unless a
separately reviewed approved-scope defect fix becomes necessary; there is no
protocol or database migration to reverse.

## Open Questions

- None. Hardware-only execution remains deferred to the final Release Candidate.
