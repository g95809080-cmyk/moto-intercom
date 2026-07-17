## Context

KUM-27A approved one future Coordinator owner while preserving
`SessionOrchestrator` as the only product-state writer. The current repository
already has one `ConnectionAttempt` type with immutable identity, target,
trigger, `ChannelPlan`, and a raw monotonic deadline value. It also already
rejects multi-transport plans. B1 must strengthen this type as the future
domain boundary without moving any current owner or changing runtime behavior.

The current runtime intentionally still rebases copied attempts and injects
clock lambdas in several components. Removing those paths belongs to later
atomic ownership checkpoints, not B1.

## Goals / Non-Goals

**Goals:**

- Evolve the existing `ConnectionAttempt` model in place; do not introduce a
  parallel attempt representation.
- Give monotonic timestamps a pure value type and a replaceable clock seam.
- Express preferred transport, deadline expiration, attempt/target matching,
  and terminal outcomes in framework-free domain code.
- Provide deterministic JVM tests and a test-only fake clock.

**Non-Goals:**

- No attempt creation or termination ownership migration.
- No current deadline scheduler, timeout, rebase, or 10-second behavior change.
- No callback, candidate, winner, Service, adapter, signaling, WebRTC, Target
  Lock, persistence, notification, UI, Gradle, or dependency change.
- No second live Coordinator, B2 implementation, or KUM-28 behavior.

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

### 6. Bounded pipeline

This checkpoint follows an equivalent deterministic full-feature subset:
proposal, specs/design/tasks, one writer apply, targeted and full verification,
one read-only architecture review loop, Draft PR, and evidence sync. Ship,
archive, retro, merge, deployment, and B2 are outside this run.

Execution is fixed to Rasen 0.1.3 with `DO_NOT_TRACK=1` and
`RASEN_TELEMETRY=0`. Maximum concurrent write workers is one; the reviewer is
read-only; leaf workers do not delegate; no browser/chrome-use, Greptile,
auto-decompose, automatic merge, or automatic deployment is permitted.

## Risks / Trade-offs

- [The legacy data-class `copy` paths can still create a new instance with a
  later raw deadline] -> B1 does not call or expand those paths; the new
  `deadlineAt` is immutable per instance. Their atomic removal remains in the
  later deadline-ownership checkpoint.
- [Raw deadline and typed deadline could diverge] -> `deadlineAt` is derived
  from the existing raw value and introduces no second stored field.
- [A pure event predicate could be mistaken for completed callback migration]
  -> Keep it unused by production call paths in B1 and state that routing is
  deferred.
- [A test fake could leak into production] -> Keep it under `app/src/test` and
  verify release assembly plus source imports.

## Migration Plan

1. Add the pure timestamp, clock, event context, and terminal outcome types.
2. Add derived helpers to the existing `ConnectionAttempt` type without
   changing its constructor or callers.
3. Add deterministic B1 JVM tests and run targeted plus full repository gates.
4. Deliver as one atomic commit and Draft PR, then perform fixed-SHA read-only
   architecture review.
5. Stop. B2 and later ownership cutovers require separate authorization.

Rollback is commit-level and restores the exact pre-B1 runtime because B1 has
no production wiring, schema, protocol, dependency, or data change.

## Open Questions

None for B1. Production clock assembly and owner cutovers remain intentionally
deferred to their authorized checkpoints.
