## Context

KUM-27A approved one future Coordinator owner while preserving
`SessionOrchestrator` as the only product-state writer. B1 completed the
framework-free `ConnectionAttempt` domain model and deterministic clock seam.
B2 now moves production attempt creation and first-terminal ownership into the
existing `SignalingControlCoordinator` without creating another Coordinator.

The current runtime intentionally still rebases copied attempts and sources
absolute deadlines from Service and Coordinator clock lambdas. Removing those
paths, the `Long.MAX_VALUE` inbound sentinel, and external deadline inputs is
the atomic B3 cutover, not B2.

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

**Non-Goals:**

- No deadline source, scheduler, rebase, sentinel, or 10-second behavior change.
- No callback, candidate, winner, Service, adapter, signaling, WebRTC, Target
  Lock, persistence, notification, UI, Gradle, or dependency change.
- No second live Coordinator, B3 implementation, or KUM-28 behavior.

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

This checkpoint follows an equivalent deterministic full-feature subset:
proposal, specs/design/tasks, one writer apply, targeted and full verification,
one read-only architecture review loop, Draft PR update, and evidence sync.
B3, archive, merge, deployment, and KUM-28 are outside this checkpoint.

Execution is fixed to Rasen 0.1.3 with `DO_NOT_TRACK=1` and
`RASEN_TELEMETRY=0`. Maximum concurrent write workers is one; the reviewer is
read-only; leaf workers do not delegate; no browser/chrome-use, Greptile,
auto-decompose, automatic merge, or automatic deployment is permitted.

## Risks / Trade-offs

- [The legacy data-class `copy` paths can still create a new instance with a
  later raw deadline] -> B2 does not call or expand those paths. Their atomic
  removal remains in B3.
- [Raw deadline and typed deadline could diverge] -> `deadlineAt` is derived
  from the existing raw value and introduces no second stored field.
- [A pure event predicate could be mistaken for completed callback migration]
  -> Keep it unused by production call paths in B1 and state that routing is
  deferred.
- [A test fake could leak into production] -> Keep it under `app/src/test` and
  verify release assembly plus source imports.
- [Moving creation before moving deadline source can create two deadline
  owners] -> Treat the absolute deadline as an opaque compatibility input in
  B2; only its existing caller computes it and the Coordinator never modifies
  it until the atomic B3 cutover.
- [Protocol tombstones can be mistaken for logical terminal ownership] -> Keep
  protocol `AttemptOutcome` separate and prove that only the Coordinator's
  logical mailbox decides first-terminal product behavior.

## Migration Plan

1. B1: add the pure domain foundation and deterministic tests. Complete.
2. B2: inject deterministic ID creation into the existing Coordinator, move
   outbound/recovery construction to it, and remove Service/reducer ID and
   attempt construction authority.
3. B2: route attempt-ending events through one first-terminal mailbox while
   preserving `SessionOrchestrator` as the product-state writer.
4. Run targeted and full gates, deliver one atomic B2 commit, and perform a
   fixed-SHA read-only architecture review.
5. B3 later performs the atomic deadline/pending-inbound cutover described
   above. B4-B6 and KUM-28 remain deferred.

Rollback is commit-level to the approved B1 head. B2 changes no schema,
protocol, dependency, identity, pairing, database, permission, or persisted
data, and restoring B1 restores the previous production ownership paths.

## Open Questions

None for B2. The fixed B2/B3 boundary above is mandatory; implementation must
stop rather than partially move the inbound sentinel or deadline rebases.
