## 1. Contract And Traceability

- [x] 1.1 Freeze KUM-30 proposal, specification, design, allowed scope,
  forbidden scope, and Release Candidate physical deferral.
- [x] 1.2 Map every KUM-30 Exit Criterion to current ownership/trust boundaries,
  deterministic tests, and final evidence.
- [x] 1.3 Strictly validate the complete Rasen change.

## 2. Deterministic Certification

- [x] 2.1 Prove cancellation with multiple candidates emits one terminal outcome,
  closes every candidate, aborts once, and rejects late callbacks.
- [x] 2.2 Prove canonical simultaneous-request arbitration on both physical roles
  and complete `GLARE_LOST` broadcast for losing Sockets.
- [x] 2.3 Prove duplicate REQUEST, duplicate physical Socket, duplicate winner,
  send-completion, and terminal callbacks are idempotent.
- [x] 2.4 Prove old-attempt OFFER, ANSWER, and ICE frames fail before reader
  handoff and cannot reach the current media context.
- [x] 2.5 Stress the real mailbox from concurrent workers and prove one WebRTC
  start, one terminal cleanup path, and no retained attempt channels.

## 3. Root-Cause Remediation

- [x] 3.1 Run the new regressions against the merged baseline and record whether
  a runtime defect exists.
- [x] 3.2 Record runtime remediation as not required because every new regression
  passes the merged baseline; add no second owner.
- [x] 3.3 Confirm no protocol, database, target, transport schedule, UI, KUM-31,
  signing, deployment, or release behavior entered the diff.

## 4. Automated Gate

- [x] 4.1 Run KUM-30 targeted deterministic JVM tests.
- [x] 4.2 Run the complete `testDebugUnitTest` gate and record exact totals.
- [x] 4.3 Run `lintDebug`, `assembleDebug`, and `assembleDebugAndroidTest`.
- [x] 4.4 Run the reusable three-emulator matrix, preserve fresh evidence, and
  record the API 36 ATD black-frame screenshot limitation without accepting the
  screenshots as visual evidence.

## 5. Delivery And Review

- [x] 5.1 Update the authoritative Sprint 3 verification report with bound SHA,
  exact automated evidence, and deferred physical rows.
- [x] 5.2 Commit atomically, push, and open the KUM-30 Draft PR.
- [x] 5.3 Complete fixed-SHA read-only architecture review and remediate any
  in-scope P0/P1 until APPROVED with P0=0 and P1=0.
- [x] 5.4 Synchronize the final pre-merge PR and Linear evidence while KUM-8
  remains In Progress and KUM-31 remains Todo.
