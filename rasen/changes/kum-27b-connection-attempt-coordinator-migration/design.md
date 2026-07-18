## Context

KUM-27A approved one future Coordinator owner while preserving
`SessionOrchestrator` as the only product-state writer. B1 completed the
framework-free `ConnectionAttempt` domain model and deterministic clock seam.
B2 moved production attempt creation and first-terminal ownership into the
existing `SignalingControlCoordinator` without creating another Coordinator.

B3 performs the previously reserved atomic cutover: the Coordinator owns every
total attempt deadline, attempts are scheduled once, and an unpaired inbound
request awaiting confirmation is no longer represented by a sentinel attempt.

## Goals / Non-Goals

**Goals:**

- Evolve the existing `ConnectionAttempt` model in place; do not introduce a
  parallel attempt representation.
- Give monotonic timestamps a pure value type and a replaceable clock seam.
- Express preferred transport, deadline expiration, attempt/target matching,
  and terminal outcomes in framework-free domain code.
- Provide deterministic JVM tests and a test-only fake clock.
- Make the existing Coordinator create outbound and recovery attempts with a
  deterministic ID factory while retaining the existing supplied deadline.
- Make it own the current logical attempt and the first terminal outcome.
- Remove live reducer and Service authority to mint IDs, construct production
  attempts, or independently end an attempt.
- Wire `MonotonicClock` into the Coordinator and create each total deadline
  exactly once without external deadline inputs or later rebasing.
- Replace the unpaired inbound sentinel with a Coordinator-owned pending-request
  model and create the real inbound attempt only on valid local acceptance.
- Schedule each created attempt once through an explicit Service effect.

**Non-Goals:**

- No change to the 10-second attempt budget or 15-second human decision window.
- No callback/candidate cleanup migration beyond the B3 deadline and pending
  confirmation boundary; that remains B4.
- No adapter remaining-time contract change; that remains B5.
- No second live Coordinator, transport race, fallback scheduler, optimization
  window, or other KUM-28 behavior.

## Decisions

### 1. Evolve the existing model only

`ConnectionAttempt` remains the only runtime attempt type and retains its
existing constructor and raw `deadlineElapsedRealtimeMs` field for source and
behavior compatibility. B1 adds derived domain views and pure predicates; no
runtime producer or consumer is switched to them.

Alternative considered: add a separate future `ConnectionAttempt` model.
Rejected because two representations would create mapping drift and future
dual-owner risk.

### 2. One monotonic timestamp value, no duplicate storage

`MonotonicTimestamp` wraps elapsed monotonic milliseconds and rejects negative
values. `ConnectionAttempt.deadlineAt` is derived from the existing deadline
field, so B1 stores no second deadline and cannot diverge from current data.
Expiration is defined as `now >= deadlineAt`; validity is strictly before it.

Alternative considered: change the constructor field type immediately.
Rejected because that would force a broad runtime cutover and violate the B1
boundary.

### 3. Framework-free clock seam

`MonotonicClock` returns `MonotonicTimestamp`. B1 provides no Android-backed
production implementation and does not wire the interface into existing
components. A mutable `FakeMonotonicClock` exists only in JVM test sources and
supports exact forward advancement.

Alternative considered: call `SystemClock.elapsedRealtime()` from the domain
layer. Rejected because it would make the model Android-dependent and harder
to test deterministically.

### 4. Event currency is a pure contextual predicate

`ConnectionAttemptEventContext` carries only attempt ID, target device ID, and
monotonic occurrence time. `ConnectionAttempt.accepts(context)` requires exact
attempt and target equality plus occurrence before the fixed deadline.
Adapters and callbacks are not routed through this predicate in B1.

### 5. Terminal outcomes are vocabulary, not a new owner

`ConnectionAttemptTerminalOutcome` defines `SUCCESS`, `CANCELED`, and
`TIMED_OUT`. B1 does not store current terminal state in Service or attempt
instances and does not decide outcomes. The evolved Coordinator will own that
decision in a later authorized checkpoint.

### 6. B2 production creation boundary

The existing Coordinator receives an injected attempt-ID factory. A current
Presence selection carries target/runtime/transport availability plus the
existing absolute deadline value; the Coordinator validates that intent,
chooses exactly one transport, creates the ID and complete attempt, records it
as current, and returns the state/effect decision to `SessionOrchestrator`.
`IntercomService` no longer mints the outbound ID and the reducer no longer
constructs the production attempt.

Recovery events carry only the already-existing absolute recovery deadline.
The Coordinator creates the fresh recovery ID and attempt while preserving the
connected attempt's target and single-transport plan. Service does not create a
`RecoveryAttemptSpec`, and the reducer does not construct a recovery attempt.

This is an explicit compatibility phase: the Coordinator owns attempt identity,
target, trigger, plan, and construction, while the existing caller remains the
single source of the absolute deadline value until B3. The Coordinator stores
that value unchanged and neither computes nor rebases it in B2, so there are
not two deadline decision owners.

### 7. B2 first-terminal mailbox

The Coordinator records at most one logical terminal outcome for each attempt.
Timeout, local cancellation, transport-open failure, signaling failure,
disconnect, recovery exhaustion, stop, and WebRTC success/failure are routed
through it before the generic reducer. Later contradictory or duplicate events
cannot overwrite the first outcome or change product state.

Signaling `AttemptOutcome` remains protocol-response/tombstone state in B2; it
is not a second logical attempt-terminal authority. `SessionOrchestrator`
continues to apply every product-state assignment and emit returned effects.

### 8. B2/B3 atomic boundary

B2 deliberately leaves these coupled paths untouched:

- outbound request-delivery and remote-accept deadline rebases;
- unpaired inbound `Long.MAX_VALUE` sentinel and accept-time rebase;
- `RescheduleAttemptDeadline` and Service timer execution;
- paired inbound deadline creation in the existing Coordinator;
- adapter timeout and remaining-budget contracts.

B3 must replace all of them together with Coordinator-owned monotonic deadline
creation and the approved `PendingInboundRequest` representation. B2 must not
introduce a temporary second deadline, compatibility Coordinator, or alternate
inbound representation.

### 9. Bounded pipeline

This change follows an equivalent deterministic full-feature subset:
proposal, specs/design/tasks, one writer apply, targeted and full verification,
one read-only architecture review loop, Draft PR update, and evidence sync.
Archive, deployment, and KUM-28 remain outside B3.

Execution is fixed to Rasen 0.1.3 with `DO_NOT_TRACK=1` and
`RASEN_TELEMETRY=0`. Maximum concurrent write workers is one; the reviewer is
read-only; leaf workers do not delegate; no browser/chrome-use, Greptile,
auto-decompose, automatic merge, or automatic deployment is permitted.

### 10. B3 has one monotonic deadline source

The existing Coordinator receives the B1 `MonotonicClock`. Outbound and
recovery attempts use `clock.now() + 10 seconds`. A paired inbound request uses
its verified request occurrence time plus 10 seconds. A locally accepted
unpaired request uses the accepted event occurrence time plus 10 seconds.
Glare preserves the already-created attempt deadline. Validity is strict:
`now >= deadlineAt` is expired.

WebRTC success and glare may advance product state only while the Coordinator's
monotonic clock is strictly before that deadline. Service keeps the physical
timer until the Coordinator has accepted success and published the matching
`Connected` state, so a callback queued at the boundary cannot cancel timeout.

`ConnectPresenceRequested`, channel-loss events, signaling-loss events, and
WebRTC terminal events no longer carry caller-created recovery or attempt
deadlines. Service therefore cannot create or reset a logical attempt budget.

### 11. Pending inbound confirmation is not an attempt

`PendingInboundRequest` is owned by the Coordinator and contains the verified
wire request key, target, peer, single transport, eligible channel set,
confirmation surface/nonce, and monotonic human-decision deadline. It is not
stored in `ownedAttempt`, is not exposed as `currentAttempt`, and never causes
an attempt timer to be scheduled.

`IntercomState.IncomingConfirmation` is only a product-state projection of the
pending request identity and peer. Immediate unavailable/busy responses create
neither a pending request nor an attempt. Reject, timeout, confirmation-surface
loss, and final channel loss terminate the pending request without creating or
cancelling an attempt timer. A valid local accept creates exactly one inbound
attempt from the verified remote attempt ID and target.

### 12. One explicit attempt-deadline schedule effect

The Coordinator emits `ScheduleAttemptDeadline` exactly once alongside the
first effects for every newly created attempt. Service executes that physical
timer effect. `beginTargetedTransport()` and `startWebRtc()` do not schedule or
rebase timers. Request delivery, remote acceptance, media selection, and media
start preserve the original deadline. The scheduler ignores a duplicate
schedule for the same current attempt so a late duplicate effect cannot move
its timer.

### 13. B3 remains below callback and adapter migration

B3 validates the events it directly changes by runtime, attempt, target/wire
identity, and strict deadline. It does not claim that all Socket, P2P, SDP/ICE,
or delayed adapter callbacks have completed the B4 migration, and it does not
change adapter retry/remaining-budget APIs reserved for B5.

## Risks / Trade-offs

- [The legacy data-class `copy` paths can create a later deadline] -> B3 removes
  every production deadline rebase and tests that request delivery, remote
  acceptance, glare, and media start preserve the original value.
- [Raw deadline and typed deadline could diverge] -> `deadlineAt` is derived
  from the existing raw value and introduces no second stored field.
- [A pure event predicate could be mistaken for completed callback migration]
  -> Keep it unused by production call paths in B1 and state that routing is
  deferred.
- [A test fake could leak into production] -> Keep it under `app/src/test` and
  verify release assembly plus source imports.
- [A pending inbound request can accidentally become a live attempt] -> Store it
  separately, expose no `currentAttempt`, emit no attempt schedule, and test all
  reject/timeout/channel-loss exits.
- [A delayed duplicate schedule can rebase the physical timer] -> Emit one
  schedule effect at creation and make same-attempt duplicate scheduling a
  no-op.
- [Protocol tombstones can be mistaken for logical terminal ownership] -> Keep
  protocol `AttemptOutcome` separate and prove that only the Coordinator's
  logical mailbox decides first-terminal product behavior.

## Migration Plan

1. B1: add the pure domain foundation and deterministic tests. Complete.
2. B2: move production creation and first-terminal ownership into the existing
   Coordinator. Complete and approved.
3. B3: add failing deadline/pending-inbound boundary tests.
4. B3: atomically remove external deadline inputs, all rebases, the sentinel
   attempt, and implicit Service scheduling; add one explicit schedule effect.
5. Reject WebRTC success and glare at the exact total deadline, and cancel the
   physical timer only after Coordinator-authorized success.
6. Run targeted/full gates and fixed-SHA read-only review, then record evidence.
7. B4-B6 remain deferred until their preceding gates; KUM-28 remains absent.

Rollback is commit-level to the approved B1 head. B2 changes no schema,
protocol, dependency, identity, pairing, database, permission, or persisted
data, and restoring B1 restores the previous production ownership paths.

## Open Questions

None for B3. The deadline/pending-inbound cutover is atomic; implementation
must not leave an external deadline source, rebase, sentinel attempt, or second
schedule path.
