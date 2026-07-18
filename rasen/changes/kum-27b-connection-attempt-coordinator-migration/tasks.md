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
- [x] 2.6 Run targeted tests, `testDebugUnitTest`, `lintDebug`,
  `assembleDebug`, GitHub CI, and fixed-SHA read-only architecture review.
- [x] 2.7 Remediate the first review P0 by latching local terminal cleanup
  ahead of queued WebRTC, signaling, and owner-channel recovery callbacks.

Delivery gate after apply: deliver B2 as one atomic commit on Draft PR #4,
complete fixed-SHA review with P0=0/P1=0, and synchronize Linear evidence while
keeping KUM-27 In Progress. B3 may start only after this gate passes.

### B2 Gate Record

- Implementation Head `8b3a35eea4e0d949693ae5d166c3f155958557ab`:
  Review Round 1 REQUEST CHANGES, P0=1, P1=0.
- Remediation Head `aeb3926cf50b9b0f6345f6883111468ea1a971e1`:
  local terminal cleanup blocks queued WebRTC, signaling, and owner-channel
  recovery callbacks without consuming a recovery ID.
- Review Round 2: APPROVED, P0=0, P1=0, B2 complete YES, B3 allowed YES.
- Verification: 193 JVM tests passed; Lint 0 Fatal/0 Error/34 warnings;
  `assembleDebug` passed; Rasen strict validation 1/1.
- GitHub CI `29643472892`: success at the remediation Head.

## 3. B3: Immutable Total Deadline Ownership

- [x] 3.1 Freeze the atomic B3 deadline/pending-inbound boundary in proposal,
  specs, design, and tasks; strictly validate the change.
- [x] 3.2 Add deterministic failing tests for Coordinator-created outbound,
  paired-inbound, accepted-inbound, recovery, and glare-preserved deadlines.
- [x] 3.3 Replace the unpaired inbound sentinel attempt with a
  Coordinator-owned `PendingInboundRequest`; keep `currentAttempt` null until a
  valid local accept and create no attempt on reject/timeout/final-channel loss.
- [x] 3.4 Remove deadline inputs from production intent/recovery events and
  remove Service deadline creation plus request-delivery/remote-accept rebases.
- [x] 3.5 Replace implicit/reschedule paths with one explicit
  `ScheduleAttemptDeadline` effect per created attempt and a duplicate-safe
  physical scheduler.
- [x] 3.6 Add exact before/at/after deadline, no-rebase, no-sentinel,
  no-attempt-on-pending-terminal, and one-schedule regression coverage.
- [x] 3.7 Remediate the first review P1 by rejecting WebRTC success and glare
  at the exact total deadline and deferring physical timer cancellation until
  Coordinator-authorized success.
- [x] 3.8 Run targeted tests, `testDebugUnitTest`, `lintDebug`,
  `assembleDebug`, strict Rasen validation, GitHub CI, and fixed-SHA read-only
  architecture review.

Delivery gate after apply: deliver B3 as an atomic commit on Draft PR #4,
complete fixed-SHA review with P0=0/P1=0, and synchronize Linear evidence while
keeping KUM-27 In Progress. B4 may start only after this gate passes.

### B3 Gate Record

- Implementation Head `d6eb6ba828ab55f73d8c89e777ca5d1c74da47ad`:
  Review Round 1 REQUEST CHANGES, P0=0, P1=1.
- P1: WebRTC success or glare processed at the exact total deadline could beat
  the queued timeout, and Service canceled the physical timer before the
  Coordinator accepted success.
- Remediation Head `d96030c9ac3e37ced44b105069f90568ba2eaa3b`:
  exact-deadline success/glare are rejected, physical cancellation follows
  Coordinator-authorized `Connected`, and deterministic regressions cover both
  orderings.
- Review Round 2: APPROVED, P0=0, P1=0, B3 complete YES, B4 allowed YES.
- Verification: 197 JVM tests passed with 0 failures/errors/skipped; Lint 0
  Fatal/0 Error/34 warnings; `assembleDebug` passed; Rasen strict validation
  1/1.
- GitHub CI `29647590056`: success at the remediation Head.
- Physical-device testing: not required for B3; no adapter, WebRTC, permission,
  audio, database, or OEM implementation changed.
- Efficiency: elapsed 2h14m; workers 2 (one main writer and one reused read-only
  reviewer); review rounds 2; handoffs 1 runtime continuation; runtime-cumulative
  token usage 2,658,491 at the gate (checkpoint-only split unavailable); full
  repository rescans 0.

## 4. B4: Callback, Candidate And Cleanup Lifecycle

- [x] 4.1 Freeze the B4 upper-layer candidate/callback/exact-cleanup boundary
  in proposal, specs, design, and tasks; strictly validate the change.
- [x] 4.2 Add immutable `ConnectionCandidateContext` plus pure exact-session
  and current-Coordinator-winner predicates.
- [x] 4.3 Key pending media and the physical media locator by complete candidate
  context; remove `tunnelChosen` and channel-only policy claims.
- [x] 4.4 Make selection, signaling reader/send completion, SDP/ICE delivery,
  and close effects validate the exact runtime/attempt/channel/target context.
- [x] 4.5 Capture candidate context in WebRTC state, disconnect, audio, and
  error callbacks; stale callbacks must not affect a newer manager or attempt.
- [x] 4.6 Make duplicate starts and terminal cleanup idempotent while preserving
  exact newer-attempt sessions and the single Coordinator/state-writer boundary.
- [x] 4.7 Add deterministic JVM coverage for wrong runtime, attempt, channel,
  wire request, target, stale close/send/SDP/ICE/WebRTC callbacks, duplicate
  start, and newer-handle preservation.
- [x] 4.8 Remediate the first review P1 findings by attempt-scoping delayed
  cleanup effects and refusing pre-authorization physical session replacement.
- [x] 4.9 Run targeted tests, `testDebugUnitTest`, `lintDebug`, `assembleDebug`,
  strict Rasen validation, GitHub CI, and fixed-SHA read-only architecture
  review.

Delivery gate after apply: deliver B4 as one atomic implementation commit on
Draft PR #4, complete fixed-SHA review with P0=0/P1=0, and synchronize Linear
evidence while keeping KUM-27 In Progress. B5 may start only after this gate.

### B4 Gate Record

- Implementation Head `928ea95a00541d7f145677c2943dc8fecec065e3`:
  Review Round 1 REQUEST CHANGES, P0=0, P1=2.
- P1 findings: delayed terminal cleanup could release replacement-attempt
  resources, and Service could replace an occupied channel slot before
  Coordinator authorization.
- Remediation Head `03d49fa4e0c9c33e66b8e22fb4647c11914e44a5`:
  abort/restart/open effects revalidate current attempt ownership, occupied
  physical session slots fail closed, and deterministic replacement ordering
  tests cover both findings.
- Review Round 2: APPROVED, P0=0, P1=0, B4 complete YES, B5 allowed YES.
- Verification: 205 JVM tests passed with 0 failures/errors/skipped; Lint 0
  errors/34 existing warnings; `assembleDebug` passed; Rasen strict validation
  1/1.
- GitHub CI `29650223254`: success at the implementation Head; remediation CI
  `29650945945`: success at the approved Head.
- Physical-device testing: not required for B4; no adapter, WebRTC engine,
  permission, audio, database, or OEM implementation semantics changed.
- Efficiency: elapsed 1h39m; workers 2 (one main writer and one read-only
  reviewer); review rounds 2; handoffs 1 runtime continuation;
  runtime-cumulative token usage 3,823,237 at the gate (checkpoint-only split
  unavailable); full repository rescans 0.

## 5. B5: Adapter Remaining-Time Contract

- [x] 5.1 Freeze the B5 remaining-budget/task-token boundary in proposal,
  specs, design, and tasks; strictly validate the change.
- [ ] 5.2 Add pure monotonic remaining-budget and bounded-cap helpers with
  deterministic 0/1/cap-1/cap/greater-than-cap tests.
- [ ] 5.3 Clamp targeted LAN connect and attempt-owned HELLO exchange to
  remaining budget while preserving exact lease and handoff checks.
- [ ] 5.4 Bind P2P connect, watchdog, group validation, group-info retry, and
  targeted cleanup/recovery callbacks to immutable attempt/target/generation
  context and remaining budget.
- [ ] 5.5 Replace P2P Socket wall-clock ready/connect/retry loops with monotonic
  remaining time and exact attempt-aware ready/failure predicates.
- [ ] 5.6 Revalidate exact recovery attempt and positive remaining budget before
  delayed Service adapter restart.
- [ ] 5.7 Add deterministic regressions for expiry boundaries, replacement
  during LAN/P2P/Socket work, stale cleanup, exact deadline, and no KUM-28
  behavior.
- [ ] 5.8 Run targeted tests, `testDebugUnitTest`, `lintDebug`, `assembleDebug`,
  strict Rasen validation, GitHub CI, and fixed-SHA read-only architecture
  review.

Delivery gate after apply: deliver B5 as one atomic implementation commit on
Draft PR #4, complete fixed-SHA review with P0=0/P1=0, and synchronize Linear
evidence while keeping KUM-27 In Progress. B6 may start only after this gate.

## 6. B6: Full Regression And Physical Verification

Deferred until the B5 gate and contains no executable tasks in this checkpoint.
