## ADDED Requirements

### Requirement: Exact attempt overlap-unavailable signal
The Wi-Fi Direct adapter SHALL report overlap unavailable only for a current
targeted P2P fallback when Android returns BUSY in the product's
LAN-preferred/P2P-fallback plan. The event MUST carry the immutable attempt and
MUST NOT change product state directly.

#### Scenario: Current P2P fallback is busy
- **WHEN** Android returns BUSY for the current attempt's opened P2P fallback
- **THEN** the adapter emits one or more exact-attempt overlap-unavailable events for Coordinator arbitration

#### Scenario: Passive or single-transport P2P is busy
- **WHEN** BUSY occurs without a targeted dual plan or P2P is not the fallback
- **THEN** no sequential-fallback event is emitted

#### Scenario: Non-busy failure
- **WHEN** the targeted adapter reports a failure other than BUSY
- **THEN** existing bounded failure and retry behavior remains unchanged

### Requirement: Coordinator-owned sequential fallback
The Coordinator SHALL accept an overlap-unavailable event only for the exact
owned/current, unexpired attempt after its fallback opened, before terminal or
media ownership, and before any preferred requester control candidate exists.
It SHALL retire the preferred transport and retry the same fallback in that
order without rewriting attempt identity, target, plan, or total deadline.

#### Scenario: Sequential switch is eligible
- **WHEN** the exact current P2P fallback is busy and no LAN requester channel is verified
- **THEN** the Coordinator emits ordered retire-LAN then open-P2P effects for the same attempt

#### Scenario: Preferred channel already exists
- **WHEN** a valid LAN requester control candidate already belongs to the attempt
- **THEN** the overlap-unavailable event cannot retire LAN or open another media path

#### Scenario: Duplicate overlap callback
- **WHEN** BUSY repeats after the sequential switch was accepted
- **THEN** no second retire or open effect is emitted

#### Scenario: Deadline has expired
- **WHEN** overlap-unavailable arrives at or after the immutable total deadline
- **THEN** it has no switch, transport-open, target, or media effect

### Requirement: Retired transport fails closed
After sequential fallback retires the preferred transport, it SHALL no longer
count as viable race work. A late control channel or failure callback from that
transport MUST NOT join, revive, or prolong the attempt.

#### Scenario: Retired LAN channel arrives late
- **WHEN** a requester control channel for the retired LAN path verifies later
- **THEN** the channel is rejected and closed before it can send a request or become media owner

#### Scenario: Fallback fails after retirement
- **WHEN** the retried P2P fallback fails and no live candidate remains
- **THEN** the attempt records one failure and performs terminal cleanup rather than waiting on retired LAN

#### Scenario: Attempt is canceled or replaced
- **WHEN** a BUSY or retired-path callback belongs to a canceled or replaced attempt
- **THEN** it has no effect on the new attempt, target, resources, or deadline

### Requirement: Service executes ordered physical effects
Service SHALL retire and open only transports supplied by current-attempt
Coordinator effects. The LAN and P2P adapters SHALL remain physical executors
and SHALL retain their existing remaining-time and exact-target checks.

#### Scenario: Retire LAN then retry P2P
- **WHEN** Service receives the accepted sequential-fallback effects
- **THEN** it releases the exact LAN targeted lease before asking P2P to retry the same attempt

#### Scenario: Stale retire effect
- **WHEN** Service receives a retire effect for a non-current attempt
- **THEN** it leaves current transport resources unchanged

### Requirement: Development verification and physical deferral
KUM-31 SHALL pass deterministic JVM tests, Lint, debug builds, the reusable
three-emulator matrix, CI, and fixed-SHA architecture review with P0=0 and P1=0.
The Release Candidate plan SHALL keep OEM, RF, Bluetooth, acoustic, power, and
thermal rows `DEFERRED_TO_RELEASE_CANDIDATE` until final acceptance.

#### Scenario: Development gate passes
- **WHEN** automated, emulator, CI, architecture, Git, PR, Linear, and deferred-physical evidence agree
- **THEN** KUM-31 and Sprint 3 may complete without online physical devices

#### Scenario: Hardware evidence is unavailable
- **WHEN** a check requires real OEM radio overlap, RF, Bluetooth, acoustics, power, or thermal behavior
- **THEN** it remains deferred and cannot be represented as a development PASS
