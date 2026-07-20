## 1. Contract And Baseline

- [x] 1.1 Bind KUM-34 to merge base `34f715d77c80e492ce90ecdd7efc6d1603a74d8e`, exact-main CI `29703574642`, branch `feat/kum-34-resetting-wireless-reset`, and Linear In Progress state.
- [x] 1.2 Freeze KUM-34 proposal, specification, design, allowed scope, forbidden KUM-35/KUM-36 scope, and Release Candidate physical deferrals.
- [x] 1.3 Strictly validate all four Rasen artifacts before implementation.

## 2. Deterministic Recovery Policy

- [x] 2.1 Add failing Coordinator tests proving only complete recovery-attempt terminal failures increment the target-scoped streak.
- [x] 2.2 Add tests proving the first and second final failures create fresh same-target attempts with new IDs, immutable deadlines, preserved plan order, and bounded retry startup.
- [x] 2.3 Add tests proving the third final failure enters exact `RESETTING`, while canceled, duplicate, stale, wrong-target, and transport-local events do not increment or reset.
- [x] 2.4 Add reducer/orchestrator tests for exact reset completion, stale completion rejection, success/reset streak clearing, and full-stop supersession.

## 3. Coordinator And Service Implementation

- [x] 3.1 Extend recovery/reset state, event, and effect context with the minimum failure count and exhausted-attempt identity required for stale-safe ownership.
- [x] 3.2 Route all recovery terminal roots through one Coordinator retry-or-reset decision without changing normal attempt, KUM-33 T+3/T+10, TargetLock, or winner behavior.
- [x] 3.3 Execute retry cleanup/backoff and third-failure wireless reset through existing Service resource ownership, generation gates, LAN close, and ordered Wi-Fi Direct close.
- [x] 3.4 Rebuild discovery and dispatch exact reset completion once, preserving the KUM-37 runtime audio owner and rejecting all old callbacks.

## 4. Automated Verification

- [x] 4.1 Run focused KUM-34 JVM tests and strict Rasen validation.
- [x] 4.2 Run full `testDebugUnitTest`, `lintDebug`, `assembleDebug`, and `assembleDebugAndroidTest`; record exact totals and hashes.
- [x] 4.3 Run applicable instrumentation and reusable multi-emulator reset/rebuild scenarios; preserve fresh logs/evidence and do not treat ATD black frames as visual PASS.
- [x] 4.4 Update Sprint 4 verification and RC physical plan, keeping OEM/RF/SCO/acoustic/power/thermal/background rows `DEFERRED_TO_RELEASE_CANDIDATE`.

## 5. Review And Delivery

- [x] 5.1 Commit atomically, push, open one KUM-34 Draft PR, and synchronize Linear while KUM-9 remains In Progress and KUM-35/KUM-36 remain Todo.
- [x] 5.2 Complete fixed Base/Head read-only architecture review and remediate all in-scope P0/P1 findings until APPROVED with P0=0 and P1=0.
  - [x] Validate initial review P1 findings for Android close callbacks, cleanup/deadline reconciliation, active-channel schedule rearming, and production-order coverage.
  - [x] Add bounded close-step watchdogs and deterministic never-callback coverage for every required Wi-Fi Direct action.
  - [x] Coalesce cleanup-time attempt/reset replacements, preserve immutable deadlines, rearm active recovery schedules, and cover exact adapter rebuild/reset completion.
  - [x] Re-run 284 JVM tests, lint/build/test-APK gates, focused reset instrumentation, and the full three-emulator matrix after remediation.
  - [x] Validate the second review P1 for a `removeGroup` BUSY retry outliving the close-step watchdog.
  - [x] Bind every delayed `removeGroup` retry to the owning step-active gate and add deterministic timeout/retry race coverage.
  - [x] Complete the third fixed-SHA review at `083585a` with APPROVED, P0=0, P1=0, and exact-Head CI `29711081197` success.
- [x] 5.3 Mark the PR Ready, merge with a merge commit, retain the remote branch, verify exact-main CI, set KUM-34 Done, and only then start KUM-35. Completed as merge `8dcb3f6` with exact-main CI `29711575580` success.
