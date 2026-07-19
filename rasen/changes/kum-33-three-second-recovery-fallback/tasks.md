## 1. Contract And Baseline

- [x] 1.1 Reconcile KUM-32 merge commit, exact-main CI, Linear Done state, and
  Sprint 4 evidence before binding the KUM-33 base.
- [x] 1.2 Freeze KUM-33 proposal, specification, design, allowed scope,
  forbidden scope, and Release Candidate physical deferrals.
- [x] 1.3 Strictly validate the complete Rasen change and bounded execution contract.

## 2. Deterministic Recovery Policy

- [x] 2.1 Add tests proving a fresh recovery attempt preserves TargetLock and
  transport set while preferring the last successful LAN or Wi-Fi Direct path.
- [x] 2.2 Add virtual-clock tests for recovery T+3, normal-attempt T+5, immutable
  T+10, single-transport recovery, and exact boundary behavior.
- [x] 2.3 Add tests for early recovery success, late/stale milestone exclusion,
  replacement, prior-attempt callbacks, and one media owner.
- [x] 2.4 Add tests for cleanup readiness before/after T+3 and zero extra
  recovery backoff while ordinary discovery retains its bounded backoff.

## 3. Coordinator And Service Implementation

- [x] 3.1 Derive the immutable recovery plan from the connected winner transport.
- [x] 3.2 Add recovery-only T+3 milestone creation without changing the normal
  T+5 race or total deadline.
- [x] 3.3 Route exact rebuilt-adapter readiness through the existing Coordinator
  and open preferred/due alternate transports without a Service policy fork.
- [x] 3.4 Remove only the post-cleanup recovery backoff and preserve mandatory
  cleanup, ordinary failure backoff, target, stale-event, and media gates.

## 4. Automated Verification

- [x] 4.1 Run KUM-33 targeted JVM tests and strict Rasen validation.
- [x] 4.2 Run full JVM, `lintDebug`, `assembleDebug`, and
  `assembleDebugAndroidTest` and record exact totals.
- [x] 4.3 Run applicable instrumentation and the reusable three-emulator matrix;
  preserve fresh evidence and do not treat ATD black frames as visual PASS.
- [x] 4.4 Update the Sprint 4 verification index and Release Candidate queue,
  keeping OEM/RF/audio/power/background rows `DEFERRED_TO_RELEASE_CANDIDATE`.

## 5. Review And Delivery

- [ ] 5.1 Commit atomically, push, open the KUM-33 Draft PR, and synchronize
  Linear while KUM-9 remains In Progress and KUM-34 remains Todo.
- [ ] 5.2 Complete fixed-SHA read-only architecture review and remediate any
  in-scope P0/P1 until APPROVED with P0=0 and P1=0.
- [ ] 5.3 Mark the PR Ready, merge with a merge commit, verify exact-main CI,
  close KUM-33, and retain the remote branch before starting KUM-34.
