## ADDED Requirements

### Requirement: Immutable connection-attempt foundation
The B1 domain foundation SHALL use the existing `ConnectionAttempt` type as
the single attempt representation. Each instance SHALL expose an immutable
attempt ID, runtime ID, target lock, trigger, single-transport channel plan,
preferred transport, and monotonic `deadlineAt` value. The domain type MUST
NOT hold Android Context, Service, Socket, Wi-Fi Direct group, WebRTC, UI, or
other physical-resource references.

#### Scenario: Complete attempt construction
- **WHEN** a caller constructs an attempt with all required identity, target,
  trigger, transport, and deadline fields
- **THEN** the attempt exposes exactly those values and does not derive them
  from mutable Service state

#### Scenario: Identity and target remain fixed
- **WHEN** time advances or an unrelated event is evaluated
- **THEN** the attempt ID and target device remain unchanged

### Requirement: Single-transport channel plan
`ChannelPlan` SHALL contain exactly one transport throughout KUM-27. It MUST
reject both an empty plan and a plan containing more than one transport.

#### Scenario: One transport is accepted
- **WHEN** a plan is created for exactly one LAN or Wi-Fi Direct transport
- **THEN** that transport is exposed as the preferred transport

#### Scenario: Empty transport plan is rejected
- **WHEN** a plan is created with no transport
- **THEN** construction fails before an attempt can use the plan

#### Scenario: Multiple transports are rejected
- **WHEN** a plan is created with LAN and Wi-Fi Direct together
- **THEN** construction fails and no transport race is represented

### Requirement: Monotonic deadline semantics
An attempt deadline SHALL be represented as an immutable monotonic timestamp.
The attempt SHALL be valid strictly before that timestamp and expired when the
monotonic clock reaches or passes it. Wall-clock time MUST NOT participate in
the calculation.

#### Scenario: Before deadline
- **WHEN** an event timestamp is less than `deadlineAt`
- **THEN** the event is not stale on time grounds

#### Scenario: At deadline
- **WHEN** an event timestamp equals `deadlineAt`
- **THEN** the event is stale because the attempt is expired

#### Scenario: After deadline
- **WHEN** an event timestamp is greater than `deadlineAt`
- **THEN** the event is stale because the attempt is expired

#### Scenario: Clock advancement cannot rebase deadline
- **WHEN** a deterministic clock advances after attempt construction
- **THEN** the attempt retains its original `deadlineAt` value

### Requirement: Contextual stale-event predicate
The pure domain layer SHALL accept an attempt-sensitive event only when its
attempt ID and target device match the attempt and its monotonic occurrence
timestamp is strictly before the attempt deadline.

#### Scenario: Old attempt event
- **WHEN** an event carries a different attempt ID
- **THEN** it is stale even when its target and timestamp otherwise match

#### Scenario: Wrong target event
- **WHEN** an event carries a different target device ID
- **THEN** it is stale even when its attempt ID and timestamp otherwise match

#### Scenario: Current event
- **WHEN** attempt ID and target match and the event occurred before deadline
- **THEN** the event is current at the B1 domain boundary

### Requirement: Terminal outcome vocabulary
The domain layer SHALL define distinct terminal outcomes for success,
cancellation, and timeout without making Service or adapter state the source
of truth.

#### Scenario: Success outcome
- **WHEN** a logical attempt succeeds
- **THEN** the domain can represent `SUCCESS` as its terminal outcome

#### Scenario: Cancellation outcome
- **WHEN** a logical attempt is canceled
- **THEN** the domain can represent `CANCELED` as its terminal outcome

#### Scenario: Timeout outcome
- **WHEN** a logical attempt reaches its deadline
- **THEN** the domain can represent `TIMED_OUT` as its terminal outcome

### Requirement: Deterministic clock test support
The clock abstraction SHALL expose monotonic time without Android framework or
wall-clock dependencies. A test-only fake clock SHALL advance by exact,
non-negative durations and MUST reject backward movement.

#### Scenario: Exact advancement
- **WHEN** the fake clock advances by a specified duration
- **THEN** its next timestamp increases by exactly that duration

#### Scenario: Backward movement is rejected
- **WHEN** a caller attempts to advance the fake clock by a negative duration
- **THEN** the clock rejects the operation

### Requirement: B1 has no runtime cutover
B1 SHALL NOT wire the new clock, event predicate, or terminal outcome into the
current runtime. It MUST NOT migrate attempt creation or termination, replace
deadline ownership, route callbacks, change an adapter, create a second live
Coordinator, or implement any KUM-28 behavior.

#### Scenario: Existing runtime remains unchanged
- **WHEN** the B1 change is built and tested
- **THEN** existing Android runtime call paths and observable connection
  behavior remain unchanged
