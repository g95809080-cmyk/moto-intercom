## 1. Contract And Traceability

- [x] 1.1 Freeze the KUM-29 proposal, optimizing-winner specification, design,
  allowed scope, forbidden scope, and Release Candidate physical deferral.
- [x] 1.2 Map every KUM-29 Exit Criterion to the current coordinator, Service
  effect boundary, deterministic test, and PR #5 implementation commit.
- [x] 1.3 Strictly validate the complete Rasen change.

## 2. Deterministic Certification

- [x] 2.1 Prove fallback-first entry into `OPTIMIZING`, preferred arrival at
  999 ms, fallback selection at exact 1,000 ms, and deadline precedence.
- [x] 2.2 Prove first-owner wins, one accept, one WebRTC start, actual winner
  transport persistence, and rejection of a second owner claim.
- [x] 2.3 Prove bounded cleanup for dual-success, single-success, all-failure,
  cancellation, stale milestone, and deadline terminal paths.
- [x] 2.4 Confirm the certification branch has no Android runtime behavior diff.

## 3. Automated Gate

- [x] 3.1 Run KUM-29 targeted deterministic JVM tests.
- [x] 3.2 Run the complete `testDebugUnitTest` gate.
- [x] 3.3 Run `lintDebug`, `assembleDebug`, and `assembleDebugAndroidTest`.
- [x] 3.4 Run the applicable reusable emulator regression and preserve evidence.

## 4. Delivery And Review

- [x] 4.1 Add the issue-scoped verification report with exact commands, results,
  source SHA traceability, and deferred physical rows.
- [ ] 4.2 Commit atomically, push, and open a Draft PR bound to fixed Base/Head.
- [ ] 4.3 Complete read-only architecture review; remediate any in-scope P0/P1,
  rerun gates, and repeat review until P0=0 and P1=0.
- [ ] 4.4 Turn Ready and merge with a merge commit after PR CI succeeds; retain
  the remote branch and require green main CI.
- [ ] 4.5 Synchronize final evidence and KUM-29 Done in Linear while KUM-8 stays
  In Progress and KUM-30/KUM-31 remain unstarted.
