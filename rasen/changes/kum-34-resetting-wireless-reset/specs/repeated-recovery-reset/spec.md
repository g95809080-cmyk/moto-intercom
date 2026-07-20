## ADDED Requirements

### Requirement: Consecutive final recovery failures stay target scoped
The Coordinator SHALL count only terminal exhaustion of a complete recovery attempt, SHALL keep the streak attached to the same immutable `TargetLock`, and SHALL never let a transport-local or stale event increment the streak.

#### Scenario: Transport-local failure does not increment the streak
- **WHEN** one planned recovery transport fails while another transport or the attempt deadline remains live
- **THEN** the final-failure streak remains unchanged and the same recovery attempt continues

#### Scenario: Duplicate terminal callback is inert
- **WHEN** a terminal callback for an already completed recovery attempt is delivered again
- **THEN** the Coordinator rejects it without incrementing the streak or changing product state

#### Scenario: Wrong attempt or target is inert
- **WHEN** a callback carries an old attempt ID or a different target identity
- **THEN** it cannot increment the current target's streak, create a retry, or enter `RESETTING`

### Requirement: First and second final failures create bounded same-target retries
After the first or second accepted final recovery failure, the Coordinator SHALL create a fresh recovery attempt for the same target and transport order, with a fresh attempt ID and immutable monotonic T+10 deadline. Runtime cleanup and the bounded reconnect backoff SHALL consume that new attempt budget rather than rebase it.

#### Scenario: First final failure retries the same target
- **WHEN** the first recovery attempt reaches a terminal final failure
- **THEN** product state remains `RECOVERING`, the count becomes one, and a fresh attempt preserves the same complete `TargetLock` and `ChannelPlan`

#### Scenario: Second final failure retries with another fresh identity
- **WHEN** the second recovery attempt reaches a terminal final failure
- **THEN** product state remains `RECOVERING`, the count becomes two, and the next attempt uses a new attempt ID and a new immutable deadline

#### Scenario: Retry startup uses bounded backoff
- **WHEN** a retry attempt is created after a final failure
- **THEN** Service finishes old Socket/LAN/P2P cleanup, waits the existing bounded reconnect backoff, and starts only the transports in the new immutable plan without extending its deadline

#### Scenario: Retry deadline expires during asynchronous cleanup
- **WHEN** the current retry reaches its immutable deadline before the previous physical cleanup callback completes
- **THEN** Service retains one cleanup owner, replaces the pending restart with the Coordinator's latest retry or reset request, and cannot strand the runtime or restart the expired attempt

#### Scenario: Active recovery signaling channel disconnects
- **WHEN** an accepted control channel disconnects while the same recovery attempt remains current
- **THEN** the Coordinator retires that channel context, preserves the attempt identity and absolute deadline, and re-emits the remaining deadline and fallback schedules for the rebuilt adapters

#### Scenario: Recovery success clears the episode streak
- **WHEN** any retry reaches verified WebRTC `CONNECTED`
- **THEN** the resulting `CONNECTED` state carries no prior recovery-failure streak and a later recovery episode starts from zero

### Requirement: Third final failure enters exact visible reset state
The third consecutive accepted final recovery failure for the same target SHALL terminate the attempt and enter `RESETTING`. The reset state and effect SHALL carry the runtime session, target device, exhausted attempt ID, and final count.

#### Scenario: Third final failure triggers reset
- **WHEN** a target-scoped recovery episode records its third complete final failure
- **THEN** the Coordinator clears active attempt ownership, enters `RESETTING` with count three, and emits exactly one wireless-reset effect

#### Scenario: Canceled recovery does not trigger reset
- **WHEN** recovery is canceled by a full stop or another non-failure terminal action
- **THEN** the action does not increment the final-failure streak or trigger `RESETTING`

### Requirement: Wireless reset performs ordered complete teardown and rebuild
While product state is `RESETTING`, Service SHALL invalidate old runtime callback generations, close current signaling/WebRTC attempt resources, close LAN server/NSD/UDP/Socket work, run Wi-Fi Direct cleanup in the order `cancelConnect`, `removeGroup`, `clearServiceRequests`, `clearLocalServices`, channel `close`, and rebuild fresh discovery components. The online audio-session owner SHALL remain governed by the existing KUM-37 lifecycle.

#### Scenario: Ordered Wi-Fi Direct cleanup continues through API failures
- **WHEN** any Wi-Fi Direct cleanup action reports failure or throws
- **THEN** cleanup records the error, advances to the next required action, closes the channel, and completes at most once

#### Scenario: Wi-Fi Direct cleanup listener never returns
- **WHEN** `cancelConnect`, `removeGroup`, `clearServiceRequests`, or `clearLocalServices` never invokes its Android callback
- **THEN** the bounded step watchdog records a timeout, advances exactly once, ignores any later callback, and still reaches channel close and discovery rebuild

#### Scenario: Delayed removeGroup retry outlives its close step
- **WHEN** `removeGroup` reports BUSY and its delayed retry becomes runnable after the close-step watchdog has already advanced
- **THEN** the retry fails its owning step-activity gate and cannot call the old manager or channel after discovery rebuild

#### Scenario: LAN and delayed work are retired before rebuild
- **WHEN** reset begins
- **THEN** old LAN server, NSD registration/discovery, UDP, targeted Socket, signaling sessions, attempt deadlines, milestones, and delayed callbacks are stopped or invalidated before fresh discovery ownership is installed

#### Scenario: Reset completion returns to idle discovery
- **WHEN** the exact reset cleanup finishes and fresh discovery adapters are installed
- **THEN** Service dispatches the matching reset completion and `SessionOrchestrator` transitions from `RESETTING` to `DISCOVERING`

### Requirement: Reset and recovery callbacks fail closed
All attempt callbacks during reset and all reset-completion callbacks SHALL be matched against current product ownership before they can change state or claim resources.

#### Scenario: Old attempt callback arrives during reset
- **WHEN** a callback from any exhausted recovery attempt arrives while state is `RESETTING`
- **THEN** it cannot open a transport, create media, change target, leave `RESETTING`, or claim rebuilt resources

#### Scenario: Stale reset completion cannot finish a newer reset
- **WHEN** reset completion carries a different exhausted attempt ID or runtime session
- **THEN** the event is rejected and current reset state remains unchanged

#### Scenario: Stop supersedes reset completion
- **WHEN** the user fully stops the runtime while reset cleanup is in flight
- **THEN** product state advances toward `OFFLINE` and the late reset completion cannot restart discovery

### Requirement: KUM-34 uses automated gates and defers physical acceptance
KUM-34 SHALL pass deterministic JVM coverage, applicable emulator verification, full Gradle and CI gates, and a fixed-SHA read-only architecture review with P0=0 and P1=0. Hardware-specific checks SHALL remain deferred without being represented as passed.

#### Scenario: Automated delivery gate passes
- **WHEN** Coordinator, stale-event, reset-order, rebuild, full JVM, lint, assemble, emulator, CI, Git, PR, Linear, and architecture evidence agree
- **THEN** KUM-34 may merge and complete without connected physical devices

#### Scenario: Physical checks are unavailable during development
- **WHEN** OEM Wi-Fi Direct, RF interference, Bluetooth SCO, real acoustics, power, thermal, or background-survival checks are not run
- **THEN** each remains `DEFERRED_TO_RELEASE_CANDIDATE` and does not block the KUM-34 intermediate merge
