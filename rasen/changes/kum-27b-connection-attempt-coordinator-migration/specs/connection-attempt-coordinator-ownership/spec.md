## ADDED Requirements

### Requirement: Existing Coordinator creates production attempts
The existing `SignalingControlCoordinator` SHALL evolve in place as the only
production creator and logical owner of outbound and recovery
`ConnectionAttempt` instances in B2. It SHALL use an injected ID factory,
validate target intent, choose exactly one transport, preserve target and plan
for recovery, and expose one current attempt. No second Coordinator SHALL be
created.

#### Scenario: Outbound Presence intent
- **WHEN** `SessionOrchestrator` accepts a current Presence selection while
  discovering
- **THEN** the existing Coordinator creates exactly one fresh attempt and
  returns the connecting state plus one targeted-open effect

#### Scenario: Duplicate outbound intent
- **WHEN** another Presence intent arrives after an attempt already became
  current
- **THEN** it creates no second attempt and consumes no additional ID

#### Scenario: Recovery creation
- **WHEN** a current connected attempt loses its media/signaling path and the
  event carries the existing recovery deadline input
- **THEN** the Coordinator creates one fresh recovery attempt preserving the
  target lock and single-transport plan

### Requirement: Service and reducer do not create production attempts
`IntercomService` SHALL provide intent data and execute effects but SHALL NOT
mint outbound/recovery attempt IDs or construct their `ConnectionAttempt`.
The generic reducer SHALL NOT construct outbound/recovery attempts or decide
their terminal transitions on the live orchestrated path.

#### Scenario: Service submits outbound intent
- **WHEN** Service dispatches a selected Presence
- **THEN** the event contains no pre-created attempt ID and no attempt object

#### Scenario: Recovery callback
- **WHEN** Service reports a recovery-eligible disconnect
- **THEN** it supplies no recovery attempt ID or prebuilt recovery attempt

### Requirement: First logical terminal outcome wins
The Coordinator SHALL record at most one logical terminal outcome for each
attempt. A later duplicate or contradictory timeout, cancellation, failure,
disconnect, success, stop, or recovery-exhausted event MUST NOT overwrite the
first outcome or mutate product state for that terminal attempt.

#### Scenario: Timeout precedes failure
- **WHEN** a current attempt times out and a delayed transport failure follows
- **THEN** the outcome remains `TIMED_OUT` and the late failure has no state or
  effect authority

#### Scenario: Cancellation precedes timeout
- **WHEN** local cancellation terminates an attempt before its timer callback
- **THEN** the outcome remains `CANCELED` and the timer callback is stale

#### Scenario: Success precedes a queued timeout
- **WHEN** WebRTC success is accepted before a queued timeout callback
- **THEN** the outcome remains `SUCCESS` and the timeout cannot undo the
  connected state

#### Scenario: Local disconnect precedes a recovery callback
- **WHEN** local disconnect has entered terminal cleanup and a queued WebRTC,
  signaling, or owner-channel close callback arrives
- **THEN** the Coordinator completes the existing cleanup without creating a
  recovery attempt or consuming another attempt ID

### Requirement: SessionOrchestrator remains the state writer
The Coordinator SHALL return state/effect decisions, but only
`SessionOrchestrator` SHALL assign product state. Service, timer callbacks,
transport callbacks, and reducers SHALL NOT directly assign product state.

#### Scenario: Current transport fails
- **WHEN** the Coordinator accepts a contextual transport-open failure
- **THEN** `SessionOrchestrator` applies its deterministic discovering decision
  and Service only executes the returned cleanup effect

### Requirement: B2 preserves the B3 deadline boundary
B2 SHALL treat the existing absolute monotonic deadline as an opaque supplied
value. It MUST NOT compute a second deadline, rebase or extend a deadline,
replace the inbound `Long.MAX_VALUE` sentinel, remove
`RescheduleAttemptDeadline`, change the 10-second behavior, or modify adapter
timeouts. Those coupled changes belong to B3.

#### Scenario: Outbound creation during B2
- **WHEN** the Coordinator creates an outbound attempt
- **THEN** it stores the supplied deadline unchanged and never recomputes it

#### Scenario: Inbound confirmation during B2
- **WHEN** an unpaired inbound request awaits human confirmation
- **THEN** B2 preserves the existing representation and behavior for the later
  atomic B3 pending-request/deadline cutover

### Requirement: KUM-28 remains absent
B2 SHALL preserve one transport per `ChannelPlan` and MUST NOT add T+5 fallback,
dual-channel racing, an optimization window, `OPTIMIZING`, or any KUM-28
behavior.

#### Scenario: B2 review
- **WHEN** the fixed B2 diff is reviewed
- **THEN** no second transport scheduler or KUM-28 product behavior exists
