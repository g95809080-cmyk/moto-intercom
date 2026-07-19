## ADDED Requirements

### Requirement: Bounded fallback-first optimization
When the responder receives a valid fallback candidate, the system SHALL enter
`OPTIMIZING` before the preferred candidate arrives and SHALL schedule a monotonic
winner decision no later than one second after that candidate became ready and no
later than the immutable total attempt deadline.

#### Scenario: Preferred candidate arrives inside the window
- **WHEN** a valid preferred candidate for the same runtime, attempt, target, and wire request arrives before the optimization milestone
- **THEN** the system selects the preferred candidate immediately and invalidates the queued fallback decision

#### Scenario: Window expires without preferred candidate
- **WHEN** the optimization milestone elapses while only a valid fallback candidate remains selection-ready
- **THEN** the system selects the fallback candidate and does not extend the optimization window

#### Scenario: Preferred candidate arrives at exact expiry
- **WHEN** a preferred candidate event is processed at or after the optimization milestone timestamp
- **THEN** it cannot join the frozen selection cohort or replace the fallback winner regardless of mailbox order

#### Scenario: Total deadline and optimization coincide
- **WHEN** the immutable attempt deadline is reached at the same monotonic timestamp as the optimization decision
- **THEN** timeout wins, no media candidate is selected, and all attempt resources are cleaned

### Requirement: Unique winner and media ownership
The system SHALL accept at most one current candidate as media owner for an
attempt, and only that candidate SHALL be allowed to create the Signaling/WebRTC
media session.

#### Scenario: Dual candidate success
- **WHEN** both planned transports produce valid candidates for the same current attempt
- **THEN** deterministic selection commits exactly one owner, sends one accept, rejects the loser, and ignores any later owner claim

#### Scenario: Single candidate success
- **WHEN** only one valid planned candidate is available when selection occurs
- **THEN** that candidate becomes the sole media owner and at most one WebRTC start effect is emitted

#### Scenario: Stale selection callback
- **WHEN** a selection callback carries an old attempt, wrong target, wrong wire request, or non-current channel
- **THEN** it cannot claim media ownership or start WebRTC

### Requirement: Bounded loser and terminal cleanup
The system SHALL close every non-winning channel and terminate every no-winner
attempt without leaving a second Socket, Wi-Fi Direct group owner, delayed race
task, or media session under the completed attempt.

#### Scenario: Winner selected from two candidates
- **WHEN** one candidate becomes the committed media owner and a loser reject writer completes or blocks
- **THEN** every other candidate is closed no later than its independent one-second monotonic close deadline while the winner remains current

#### Scenario: All opened paths fail
- **WHEN** all opened planned transports fail before any winner is committed
- **THEN** the attempt records one failed terminal outcome, closes all remaining channels, cancels its milestones, and resumes discovery

#### Scenario: Cancellation or timeout during optimization
- **WHEN** the user cancels or the total deadline expires while the attempt is optimizing
- **THEN** all candidate resources are closed and the queued optimization milestone has no later side effect

### Requirement: Development evidence and physical deferral
KUM-29 completion SHALL require deterministic automated evidence and a fixed-SHA
architecture review, while physical-only OEM and hardware rows SHALL remain
explicitly deferred to the Release Candidate.

#### Scenario: Development gate passes
- **WHEN** targeted JVM tests, the full unit gate, Lint, debug builds, applicable emulator regression, CI, and architecture review all pass with P0=0 and P1=0
- **THEN** KUM-29 may be merged and marked Done without claiming physical hardware acceptance

#### Scenario: Physical-only row is reported
- **WHEN** evidence depends on OEM Wi-Fi Direct concurrency, RF, Bluetooth, real audio, power, or thermal behavior
- **THEN** the row is marked `DEFERRED_TO_RELEASE_CANDIDATE` rather than PASS
