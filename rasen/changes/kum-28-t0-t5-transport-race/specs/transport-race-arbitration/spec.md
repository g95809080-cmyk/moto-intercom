## ADDED Requirements

### Requirement: Immutable ordered channel plan
Every `ConnectionAttempt` SHALL contain an immutable `ChannelPlan` with exactly
one preferred transport and zero or one distinct fallback transport. The target,
runtime, attempt identity, trigger, ordered plan, and total deadline MUST NOT be
rewritten while the attempt is active.

#### Scenario: Single available transport
- **WHEN** only one eligible transport is available for a target
- **THEN** the plan contains that transport as preferred and has no fallback

#### Scenario: Two available transports
- **WHEN** LAN and Wi-Fi Direct are both eligible for a target
- **THEN** the plan records one preferred transport, the other as fallback, and preserves that order immutably

#### Scenario: Invalid plan
- **WHEN** a plan is empty, repeats the preferred transport as fallback, or contains more than two transports
- **THEN** construction fails before an attempt can become current

### Requirement: Preferred T0 and fallback T+5 scheduling
The Coordinator SHALL open only the preferred transport at attempt start. For a
dual plan it SHALL schedule exactly one fallback milestone at T+5 on the same
monotonic timeline and SHALL keep the original T+10 total deadline unchanged.

#### Scenario: Attempt starts
- **WHEN** a dual-plan attempt is created at T0
- **THEN** only the preferred open effect is emitted and one fallback milestone is scheduled for T+5

#### Scenario: Before fallback boundary
- **WHEN** a fallback milestone callback arrives before T+5
- **THEN** it has no transport, product-state, or media effect

#### Scenario: Exact fallback boundary
- **WHEN** the current attempt has no winner and the monotonic clock reaches T+5
- **THEN** the Coordinator emits exactly one targeted open effect for the fallback transport

#### Scenario: Preferred succeeds before fallback
- **WHEN** the preferred candidate becomes the media winner before T+5
- **THEN** the fallback transport is never opened for that attempt

#### Scenario: Total deadline remains immutable
- **WHEN** the fallback opens, a candidate verifies, optimization begins, or a winner is selected
- **THEN** the attempt retains its original T+10 deadline

### Requirement: Planned transport failure ordering
A targeted open failure SHALL apply only to the reported current transport. A
dual-plan attempt MUST remain active while another planned transport is open,
has a live candidate, or is still scheduled for T+5. A preferred hard failure
before T+5 MUST NOT move the fixed fallback milestone earlier.

#### Scenario: Preferred fails before T+5
- **WHEN** the preferred targeted open fails before the fallback milestone
- **THEN** the attempt remains current and the fallback still starts at T+5

#### Scenario: Fallback fails while preferred remains viable
- **WHEN** the fallback targeted open fails and the preferred path is still open or has a live candidate
- **THEN** the attempt remains active until success, terminal failure, cancellation, or total timeout

#### Scenario: Every path is exhausted
- **WHEN** every planned transport has failed and no live candidate remains
- **THEN** the Coordinator records one terminal failure and cleans the attempt

### Requirement: Same attempt and target across transports
Every preferred and fallback callback SHALL carry and validate the same current
runtime, `ConnectionAttemptId`, `TargetLock`, expected remote runtime, and total
deadline. A transport MUST NOT choose a different target or create a second
logical attempt.

#### Scenario: Fallback channel verifies
- **WHEN** a fallback control channel verifies for the current dual plan
- **THEN** it joins the existing attempt and wire request rather than creating a new attempt

#### Scenario: Wrong target or old attempt
- **WHEN** a preferred or fallback callback has a different target, runtime, attempt, wire request, or expired deadline
- **THEN** it is closed or rejected without winner, product-state, retry, or media effect

### Requirement: Existing signaling hint identifies dual intent
A dual-plan requester SHALL send the existing `preferredTransportHint` on each
current control channel. A single-plan requester SHALL send no hint. The
responder SHALL derive the immutable inbound plan from that hint and SHALL NOT
change the Signaling v2 version, message type, or envelope fields.

#### Scenario: Dual request
- **WHEN** a responder receives a current request with a preferred transport hint
- **THEN** it creates one plan with the hint as preferred and the other supported transport as fallback

#### Scenario: Single request
- **WHEN** a responder receives a current request without a preferred transport hint
- **THEN** it creates a single-transport plan for the verified channel transport

### Requirement: Bounded fallback-first optimization
The responder Coordinator SHALL select the preferred candidate immediately when
it is selection-ready. If only a fallback candidate is ready for a dual plan,
it SHALL enter product `OPTIMIZING` and wait no longer than one monotonic second,
bounded by the total attempt deadline.

#### Scenario: Preferred is ready first
- **WHEN** the preferred candidate is present when media selection begins
- **THEN** it is selected immediately without an optimization wait

#### Scenario: Fallback is ready first
- **WHEN** only the fallback candidate is present for a dual plan
- **THEN** the Coordinator enters `OPTIMIZING` and schedules one bounded optimization milestone

#### Scenario: Preferred arrives during the window
- **WHEN** the matching preferred candidate arrives before the optimization milestone
- **THEN** the Coordinator selects it immediately and the fallback cannot become media owner

#### Scenario: Window expires with fallback only
- **WHEN** the optimization milestone is reached before the total deadline and only the fallback remains eligible
- **THEN** the Coordinator selects that fallback exactly once

#### Scenario: Total deadline wins
- **WHEN** the total deadline is reached at or before the optimization decision
- **THEN** the attempt times out and no candidate can become winner

### Requirement: Exactly one winner and one media session
Only the existing Coordinator SHALL award the winner. Exactly one current
verified channel SHALL become media owner, product `Connected` SHALL record that
channel's actual transport, and every loser SHALL be rejected or closed without
starting a second WebRTC session.

#### Scenario: Preferred wins
- **WHEN** the preferred candidate wins selection
- **THEN** product state records the preferred transport and fallback resources are released

#### Scenario: Fallback wins
- **WHEN** the bounded window ends without a preferred candidate
- **THEN** product state records the fallback transport and preferred resources are released

#### Scenario: Late loser callback
- **WHEN** a loser reports accept, media, disconnect, failure, or cleanup after the award
- **THEN** it cannot replace the winner, start WebRTC, clear winner resources, or change product state

### Requirement: Milestone and terminal cleanup
Fallback and optimization milestones SHALL be exact-attempt tasks. Winner,
cancel, timeout, replacement, stop, and runtime rollover SHALL cancel or
invalidate them. Cleanup MAY finish after logical terminal state but MUST NOT
open a transport, select a winner, retry, or revive the attempt.

#### Scenario: Attempt replacement
- **WHEN** a new attempt replaces the current attempt before an old milestone runs
- **THEN** the old callback has no effect on the new attempt or its resources

#### Scenario: User cancellation
- **WHEN** the user cancels during the T0-T+5 wait or optimization window
- **THEN** no later fallback open or winner selection occurs

#### Scenario: Runtime rollover
- **WHEN** the Service runtime stops or rolls over
- **THEN** all old race milestones and transport callbacks fail closed

### Requirement: Recovery preserves the race plan
Recovery SHALL create a fresh attempt ID and total deadline while preserving the
original target and ordered channel plan. It SHALL repeat preferred-at-T0 and
fallback-at-T+5 scheduling without carrying the previous winner or old tasks.

#### Scenario: Recovery starts
- **WHEN** a connected dual-plan attempt enters recovery
- **THEN** the fresh recovery attempt keeps the target and ordered plan, opens preferred first, and schedules its own fallback milestone

### Requirement: Automated and Release Candidate verification
KUM-28 SHALL provide deterministic fake-clock/fake-transport coverage for T0,
T+5, the one-second optimization window, T+10, cancellation, simultaneous
request, stale callbacks, replacement, unique winner, and cleanup. Applicable
Android/emulator/CI gates and fixed-SHA architecture review MUST pass with P0=0
and P1=0. Hardware-only behavior SHALL remain
`DEFERRED_TO_RELEASE_CANDIDATE` until final release acceptance.

#### Scenario: Development gate
- **WHEN** KUM-28 is proposed for merge
- **THEN** JVM, emulator, CI, architecture, Git, PR, Linear, and deferred-physical evidence are complete and mutually consistent

#### Scenario: Hardware evidence is unavailable during development
- **WHEN** OEM Wi-Fi Direct concurrency, RF, Bluetooth, background, or real acoustic behavior cannot be represented reliably in automation
- **THEN** the row is deferred with a final procedure and does not block the intermediate merge

