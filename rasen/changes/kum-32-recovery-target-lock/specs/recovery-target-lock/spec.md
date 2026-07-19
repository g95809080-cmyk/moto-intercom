## ADDED Requirements

### Requirement: Recovery retains the original target
Every recovery attempt SHALL use a fresh `ConnectionAttemptId` while retaining
the connected attempt's immutable `TargetLock`, peer identity, and channel plan.
Only the existing Coordinator SHALL create or replace that attempt.

#### Scenario: Connected rider becomes unreachable
- **WHEN** a connected attempt for rider B loses signaling, media, or transport
- **THEN** the state enters `RECOVERING` with a fresh attempt whose target remains B

#### Scenario: Presence for another rider changes
- **WHEN** rider C becomes preferred, sorts first, or responds faster during recovery
- **THEN** the active recovery attempt and product target remain B

### Requirement: Recovery transport ingress is target-bound
Every LAN and Wi-Fi Direct adapter created for recovery SHALL receive the
current recovery attempt before discovery or group validation can transfer a
resource. A non-target LAN HELLO, Wi-Fi Direct group, or verified Socket SHALL
be rejected and its Socket/group/task resources cleaned without changing
product state.

#### Scenario: Non-target LAN HELLO arrives first
- **WHEN** rider C completes LAN HELLO before rider B during A's recovery
- **THEN** C's Socket is closed before control-channel ownership is transferred

#### Scenario: Non-target Wi-Fi Direct group forms
- **WHEN** a P2P group contains C while the recovery `TargetLock` names B
- **THEN** the group is rejected and removal/rediscovery cleanup runs for the same recovery attempt

#### Scenario: Non-target verified Socket reaches Service
- **WHEN** a verified responder Socket for C reaches the Service during recovery for B
- **THEN** the Service closes it and does not register or dispatch the control channel

#### Scenario: Original target reaches Service
- **WHEN** a verified Socket matches B's device and runtime session in the recovery `TargetLock`
- **THEN** it remains eligible for the existing Coordinator and signaling gates

### Requirement: Recovery presentation names the retained rider
The in-app recovery detail and foreground notification SHALL derive their rider
label from the peer retained by `IntercomState.Recovering` and SHALL display
`正在恢复与 {车友} 的连接`.

#### Scenario: Retained peer has a nickname
- **WHEN** recovery starts for a peer whose nickname is B
- **THEN** both UI and notification display `正在恢复与 B 的连接`

#### Scenario: Retained peer nickname is unavailable
- **WHEN** the retained nickname is blank
- **THEN** presentation uses the retained device name or the explicit `原车友` fallback without selecting another Presence

### Requirement: KUM-32 preserves later recovery scope
KUM-32 SHALL NOT change the recovery deadline, fallback milestone, retry
backoff, RESETTING threshold/cleanup, active-disconnect behavior, Signaling v2
wire format, pairing/database state, or WebRTC ownership.

#### Scenario: Recovery target lock is applied
- **WHEN** the new adapter and Service gates execute
- **THEN** the existing timing, transport-plan, state-writer, and media-owner behavior remains unchanged

### Requirement: Automated and deferred evidence are explicit
KUM-32 SHALL pass deterministic JVM tests, the applicable Android/emulator
matrix, full Gradle gates, CI, and fixed-SHA architecture review with P0=0 and
P1=0. Hardware-only evidence SHALL remain `DEFERRED_TO_RELEASE_CANDIDATE`.

#### Scenario: Three-node automated race runs
- **WHEN** A recovers B while C is discovered and responds first
- **THEN** automation proves C is rejected, B remains the target, and no second media owner appears

#### Scenario: Hardware validation has not run
- **WHEN** OEM Wi-Fi Direct, RF, Bluetooth SCO, acoustic, power, thermal, or background checks are unavailable during development
- **THEN** those rows remain `DEFERRED_TO_RELEASE_CANDIDATE` and do not block the KUM-32 intermediate merge gate
