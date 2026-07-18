## 1. B1: Coordinator Domain Model And Fake Clock

- [x] 1.1 Add framework-free monotonic timestamp/clock and terminal outcome
  domain types without production runtime wiring.
- [x] 1.2 Evolve the existing `ConnectionAttempt` in place with preferred
  transport, typed deadline, and pure attempt/target/deadline event matching.
- [x] 1.3 Add a deterministic test-only fake clock and a reusable B1 attempt
  fixture.
- [x] 1.4 Add targeted JVM tests for complete fields, immutable deadline,
  exact clock advancement, before/at/after deadline, mismatched attempt and
  target, single-transport validation, and success/cancel/timeout outcomes.
- [x] 1.5 Strictly validate Rasen artifacts and run targeted JVM tests,
  `testDebugUnitTest`, `lintDebug`, and `assembleDebug`.

Delivery gate after apply: deliver B1 as one atomic commit and Draft PR,
complete fixed-SHA read-only architecture review with P0=0/P1=0, and
synchronize Linear evidence while keeping KUM-27 In Progress. These are
pipeline delivery gates, not additional implementation tasks.

## 2. B2: Attempt Creation And Termination Ownership

- [x] 2.1 Update proposal, specs, design, and tasks with the fixed B2/B3
  compatibility boundary and strict validation.
- [x] 2.2 Add deterministic attempt-ID creation, one current-attempt record,
  and a bounded first-terminal mailbox to the existing Coordinator.
- [x] 2.3 Move outbound and recovery attempt construction out of Service and
  the reducer while preserving the existing externally supplied deadlines.
- [x] 2.4 Route production timeout, cancellation, transport failure,
  disconnect, recovery exhaustion, stop, and WebRTC terminal ordering through
  the Coordinator; keep `SessionOrchestrator` as the only state writer.
- [x] 2.5 Add deterministic JVM coverage for one creation owner, duplicate
  intent rejection, recovery identity/target/plan retention, and
  first-terminal-wins ordering.
- [ ] 2.6 Run targeted tests, `testDebugUnitTest`, `lintDebug`,
  `assembleDebug`, GitHub CI, and fixed-SHA read-only architecture review.

Delivery gate after apply: deliver B2 as one atomic commit on Draft PR #4,
complete fixed-SHA review with P0=0/P1=0, and synchronize Linear evidence while
keeping KUM-27 In Progress. B3 may start only after this gate passes.

## 3. B3: Immutable Total Deadline Ownership

Deferred. Not authorized and contains no executable tasks in this checkpoint.

## 4. B4: Callback, Candidate And Cleanup Lifecycle

Deferred. Not authorized and contains no executable tasks in this checkpoint.

## 5. B5: Adapter Remaining-Time Contract

Deferred. Not authorized and contains no executable tasks in this checkpoint.

## 6. B6: Full Regression And Physical Verification

Deferred. Not authorized and contains no executable tasks in this checkpoint.
