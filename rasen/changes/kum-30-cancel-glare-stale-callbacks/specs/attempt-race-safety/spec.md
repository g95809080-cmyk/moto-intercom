## ADDED Requirements

### Requirement: Immediate current-attempt cancellation
The Coordinator SHALL serialize user cancellation as a terminal decision for the
exact current runtime and attempt. It SHALL close every candidate, invalidate
delayed work, and resume discovery without allowing a later callback to revive
the canceled attempt.

#### Scenario: Cancel before winner selection
- **WHEN** the user cancels while multiple current candidates are waiting or being selected
- **THEN** the attempt records one canceled outcome, every candidate receives bounded cleanup, and no media start is emitted

#### Scenario: Cancel after an owner is selected
- **WHEN** the user cancels after one channel becomes media owner
- **THEN** one disconnect path runs for that owner, all non-owners close, and repeated cancellation cannot start recovery or duplicate cleanup

#### Scenario: Late callback after cancellation
- **WHEN** a winner, send-completion, Socket, milestone, or terminal callback for the canceled attempt arrives later
- **THEN** it has no product-state, media-owner, transport-open, or replacement-resource effect

### Requirement: Deterministic simultaneous-request arbitration
Both endpoints SHALL compare the same canonical requester-oriented
`WireRequestKey` values to choose one request direction and WebRTC role. The
result MUST NOT depend on physical Socket role, callback arrival order, or
collection iteration order.

#### Scenario: Both endpoints request simultaneously
- **WHEN** each target sends a requester HELLO for its own attempt
- **THEN** both endpoints retain the same lower canonical request key and assign complementary requester/responder roles

#### Scenario: Losing request has multiple Sockets
- **WHEN** the losing wire request is present on more than one verified channel
- **THEN** every losing channel receives `GLARE_LOST` and none can replace the winning attempt

#### Scenario: Glare at the total deadline
- **WHEN** simultaneous-request arbitration is processed at or after the immutable attempt deadline
- **THEN** the expired attempt cannot be replaced by glare handling or start media

### Requirement: Duplicate idempotency and unique media claim
Duplicate REQUEST, physical Socket, selection, send-completion, and terminal callbacks SHALL
be idempotent for an exact current context. At most one channel
SHALL become media owner and at most one `StartWebRtc` effect SHALL be emitted.

#### Scenario: Exact owner request repeats
- **WHEN** the current owner channel repeats its REQUEST before or after acceptance
- **THEN** no second confirmation, reject, cleanup deadline, owner claim, or WebRTC start is produced

#### Scenario: Duplicate physical Socket arrives
- **WHEN** another verified Socket carries the same wire request
- **THEN** it either joins the still-eligible candidate set or is rejected and closed without replacing the current owner

#### Scenario: Conflicting callbacks are concurrent
- **WHEN** many valid and duplicate winner, send-completion, and cancellation events are submitted concurrently
- **THEN** mailbox order commits one owner, emits one WebRTC start and one terminal cleanup path, and retains no attempt channels afterward

### Requirement: Stale media frames fail closed
Every OFFER, ANSWER, and ICE envelope SHALL match the pinned source, target,
runtime session, and attempt before reader handoff. Media dispatch SHALL also
require the exact current attempt, wire request, channel, target, verified peer,
role, and media owner.

#### Scenario: Old media envelope arrives on a Socket
- **WHEN** an OFFER, ANSWER, or ICE envelope carries an old attempt identity
- **THEN** the Socket closes before the frame reaches the signaling callback or current WebRTC manager

#### Scenario: Old physical session callback arrives after replacement
- **WHEN** a callback belongs to a removed session or a reused channel identifier with different exact context
- **THEN** only the stale session closes and replacement resources remain current

#### Scenario: Current media frame arrives
- **WHEN** the pinned envelope and exact candidate context both match the sole current media owner
- **THEN** the frame may be queued for or delivered to that owner's WebRTC manager exactly once

### Requirement: Development verification and physical deferral
KUM-30 SHALL pass targeted and full JVM tests, Lint, debug builds, the applicable
three-emulator matrix, CI, and fixed-SHA architecture review with P0=0 and P1=0.
Physical OEM, RF, Bluetooth, acoustic, power, and thermal checks SHALL remain
`DEFERRED_TO_RELEASE_CANDIDATE`.

#### Scenario: Intermediate merge gate
- **WHEN** KUM-30 is proposed for merge
- **THEN** automated, emulator, CI, review, Git, PR, Linear, and deferred-physical evidence are complete and mutually consistent

#### Scenario: Hardware evidence is unavailable during development
- **WHEN** a check requires real radio, OEM, Bluetooth, microphone, speaker, listening, power, or thermal behavior
- **THEN** it remains deferred with a final procedure and does not block the KUM-30 intermediate merge
