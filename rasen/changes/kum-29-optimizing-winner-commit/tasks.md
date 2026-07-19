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

## 3. Review Remediation

- [x] 3.1 Validate the exact-expiry mailbox-order finding and freeze the selection
  cohort at or after the monotonic optimization milestone.
- [x] 3.2 Recheck the immutable total deadline immediately before a media-owner
  claim can be committed.
- [x] 3.3 Validate the unbounded loser-close finding and add an exact-context
  monotonic one-second physical close watchdog.
- [x] 3.4 Add deterministic exact-expiry, total-deadline, scheduler replacement,
  channel cancellation, and runtime cancellation tests.
- [x] 3.5 Validate the duplicate-owner watchdog finding and make repeated requests
  on an exact active channel idempotent without a reject or cleanup effect.
- [x] 3.6 Preserve the earliest exact-key loser-close deadline so repeated rejects
  cannot postpone cleanup, with deterministic regression coverage.

## 4. Automated Gate

- [x] 4.1 Run KUM-29 targeted deterministic JVM tests.
- [x] 4.2 Run the complete `testDebugUnitTest` gate.
- [x] 4.3 Run `lintDebug`, `assembleDebug`, and `assembleDebugAndroidTest`.
- [x] 4.4 Re-run the reusable three-emulator matrix after runtime remediation and
  preserve fresh evidence.

## 5. Delivery And Review

- [x] 5.1 Add the issue-scoped verification report with exact commands, results,
  source SHA traceability, and deferred physical rows.
- [ ] 5.2 Commit atomically, push, and update the Draft PR to a fixed remediation
  Base/Head.
- [ ] 5.3 Complete read-only architecture re-review; remediate any in-scope P0/P1,
  rerun gates, and repeat review until P0=0 and P1=0.
- [ ] 5.4 Turn Ready and merge with a merge commit after PR CI succeeds; retain
  the remote branch and require green main CI.
- [ ] 5.5 Synchronize final evidence and KUM-29 Done in Linear while KUM-8 stays
  In Progress and KUM-30/KUM-31 remain unstarted.
