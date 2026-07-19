## 1. Contract And Planning

- [x] 1.1 Create the KUM-28 Rasen change from green merged KUM-27 main.
- [x] 1.2 Freeze proposal, transport-race spec, technical design, allowed scope,
  forbidden scope, automated strategy, and Release Candidate deferral.
- [x] 1.3 Strictly validate all planning artifacts and commit the planning gate.

## 2. Ordered Plan And Milestones

- [ ] 2.1 Evolve immutable `ChannelPlan` to one preferred plus optional distinct
  fallback transport and update candidate membership contracts.
- [ ] 2.2 Add immutable fallback/optimization milestone values and a
  deterministic exact-attempt scheduler.
- [ ] 2.3 Add fake-clock tests for plan immutability, T+5-1/T+5/T+5+1,
  duplicate scheduling, cancellation, replacement, and runtime rollover.

## 3. T0/T+5 Targeted Transport Ownership

- [ ] 3.1 Create dual plans only when both Presence transports are available;
  open preferred at T0 and schedule one fallback milestone.
- [ ] 3.2 Route exact fallback milestones through the Coordinator and emit an
  exact-transport open effect only while the attempt has no winner.
- [ ] 3.3 Track per-transport open/failure state so one failure cannot terminate
  a viable or not-yet-due planned path and cannot move T+5 earlier.
- [ ] 3.4 Preserve the ordered plan for recovery with a fresh attempt ID,
  deadline, and milestones.

## 4. Bounded Optimization And Winner

- [ ] 4.1 Emit the existing preference hint only for dual plans and derive the
  responder's immutable inbound plan without changing Signaling v2 schema.
- [ ] 4.2 Admit same-attempt channels from either planned transport while
  rejecting wrong target/runtime/attempt/wire/deadline callbacks.
- [ ] 4.3 Enter `OPTIMIZING` when fallback is selection-ready first, select a
  preferred arrival immediately, or select fallback at the bounded window end.
- [ ] 4.4 Record the actual winner transport in Connected state, start exactly
  one WebRTC session, and close/release every loser.
- [ ] 4.5 Cancel or invalidate all race milestones on winner, cancel, timeout,
  replacement, stop, and runtime rollover.

## 5. Deterministic Regression

- [ ] 5.1 Cover T0-only preferred, no fallback before T+5, exact T+5 fallback,
  and preferred success suppressing fallback.
- [ ] 5.2 Cover preferred/fallback/all-path failure ordering, total deadline,
  cancel, replacement, stale callbacks, and recovery.
- [ ] 5.3 Cover preferred-first, fallback-first, preferred-during-window,
  fallback-at-window-end, exact deadline, simultaneous request, and glare.
- [ ] 5.4 Cover same target/attempt across transports, one accept, one media
  owner, actual winner transport, loser cleanup, and no second WebRTC session.
- [ ] 5.5 Run all targeted tests and the complete `testDebugUnitTest` gate.

## 6. Delivery And Gate

- [ ] 6.1 Run `lintDebug`, `assembleDebug`, `assembleDebugAndroidTest`, strict
  Rasen validation, PowerShell parser checks, and the reusable emulator matrix.
- [ ] 6.2 Commit atomically, push the branch, and create a Draft PR whose body
  binds Base/Head, scope, tests, behavior, deferred physical rows, and rollback.
- [ ] 6.3 Complete fixed-SHA read-only architecture review and automatically
  remediate every in-scope P0/P1 with a new commit, CI, and re-review.
- [ ] 6.4 Update `docs/verification/sprint-3-final-verification.md`, PR, and
  Linear evidence; prove working tree clean and remote ahead/behind 0/0.
- [ ] 6.5 After all gates pass, turn the PR Ready, merge with a merge commit,
  retain the branch, require green main CI, then close KUM-28 and Sprint 3.
