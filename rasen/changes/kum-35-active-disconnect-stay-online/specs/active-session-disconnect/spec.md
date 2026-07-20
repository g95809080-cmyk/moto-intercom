## ADDED Requirements

### Requirement: Local disconnect ends only the current attempt
The product SHALL route a user disconnect for a current attempt through the sole Coordinator, send one `DISCONNECT` on the exact media-owner control channel when available, and transition the same runtime to `DISCOVERING` without entering `STOPPING` or `OFFLINE`.

#### Scenario: Connected rider disconnects locally
- **WHEN** the user requests disconnect for the exact connected attempt
- **THEN** the Coordinator sends `DISCONNECT` on the owner channel before exact attempt cleanup and product state becomes `DISCOVERING`

#### Scenario: Owner send fails during local disconnect
- **WHEN** the owner control channel fails before `DISCONNECT` send completion
- **THEN** the same local cancellation completes once, no recovery attempt is created, and the runtime remains `DISCOVERING`

#### Scenario: User cancels an in-progress or recovering attempt
- **WHEN** the user requests disconnect for the exact `CONNECTING`, `OPTIMIZING`, or `RECOVERING` attempt
- **THEN** the attempt is canceled without incrementing recovery failures and the runtime returns to `DISCOVERING`

### Requirement: Peer disconnect suppresses recovery for that session
An accepted owner-channel `DISCONNECT` SHALL be treated as explicit peer intent, terminate the exact current attempt, and prevent the ended session from entering or continuing recovery.

#### Scenario: Peer disconnects a connected session
- **WHEN** the exact media-owner channel receives a valid peer `DISCONNECT`
- **THEN** the Coordinator records explicit cancellation, releases the attempt, and returns to `DISCOVERING` without creating recovery

#### Scenario: Peer disconnects during recovery
- **WHEN** the exact current recovery owner receives a valid peer `DISCONNECT`
- **THEN** the recovery episode ends without a fresh attempt, failure-count increment, or `RESETTING`

#### Scenario: Stale or non-owner disconnect arrives
- **WHEN** `DISCONNECT` carries a stale attempt, wrong runtime/target, or non-owner channel
- **THEN** it cannot end the current attempt, release current media, switch target, or suppress valid recovery

### Requirement: Attempt cleanup preserves the online runtime
Intentional disconnect SHALL release only exact attempt signaling, WebRTC, media, LAN client lease, and Wi-Fi Direct selected-transport ownership. Service, runtime identity, discovery adapters, presence aggregation, foreground notification, and runtime-owned audio resources SHALL remain online.

#### Scenario: Exact active-session release executes
- **WHEN** the Coordinator emits its immutable exact-attempt release authorization, state is the same runtime's `DISCOVERING`, and current logical attempt/channel ownership is empty
- **THEN** Service releases matching attempt resources, keeps discovery and audio owners alive, and publishes searching/available status

#### Scenario: Stale release follows replacement
- **WHEN** an old release effect runs after a new attempt or active/pending channel ownership exists
- **THEN** Service completes the old attempt's immutable-identity cleanup but skips connection-state/searching finalization, so replacement media, transport leases, status, discovery, and audio resources remain unchanged

#### Scenario: No remote media after disconnect
- **WHEN** intentional disconnect completes
- **THEN** no PeerConnection, sender, remote track, media callback binding, or duplicate media owner remains while the retained audio platform is unauthorized to transmit remotely

### Requirement: Full Stop remains a separate runtime action
Only explicit full Stop or Service destruction SHALL transition through `STOPPING` to `OFFLINE` and close discovery, foreground operation, runtime identity, and the KUM-37 audio owner.

#### Scenario: Primary action has a current attempt
- **WHEN** product state is `CONNECTING`, `OPTIMIZING`, `CONNECTED`, or `RECOVERING`
- **THEN** the primary action is labeled and routed as current-rider disconnect, not full Stop

#### Scenario: Primary action is online without a current attempt
- **WHEN** product state is `DISCOVERING` or `RESETTING`
- **THEN** the primary action remains full Stop and may enter `STOPPING`

#### Scenario: Full Stop after disconnect
- **WHEN** the user first disconnects to `DISCOVERING` and then explicitly stops intercom
- **THEN** the runtime follows `STOPPING -> OFFLINE` and closes all runtime-owned resources exactly once

### Requirement: Unexpected loss keeps recovery behavior
Signaling failure, owner-channel close, WebRTC `DISCONNECTED`/`FAILED`, and transport loss without an accepted explicit `DISCONNECT` SHALL continue to use the approved KUM-33/KUM-34 recovery and reset policy.

#### Scenario: Unexpected connected-session loss
- **WHEN** a connected media or control channel fails without local or remote explicit disconnect intent
- **THEN** product state enters target-locked `RECOVERING` with the existing immutable deadline/fallback/reset behavior

#### Scenario: Explicit and unexpected events race
- **WHEN** explicit local disconnect is accepted before a queued media/channel loss event for the same attempt
- **THEN** cancellation wins once and the queued loss cannot create recovery

### Requirement: KUM-35 uses automated gates and defers physical acceptance
KUM-35 SHALL pass deterministic JVM coverage, applicable instrumentation and three-emulator verification, full Gradle/CI gates, and fixed-SHA read-only architecture review with P0=0 and P1=0. Hardware-specific checks SHALL remain deferred without being represented as passed.

#### Scenario: Automated delivery gate passes
- **WHEN** local/remote/unexpected disconnect, exact cleanup, UI policy, stale-event, full JVM/lint/build, emulator, CI, Git, PR, Linear, and architecture evidence agree
- **THEN** KUM-35 may merge and complete without connected physical devices

#### Scenario: Physical checks remain unavailable
- **WHEN** OEM Wi-Fi Direct, RF, Bluetooth SCO, real acoustics, power, thermal, or background-survival checks are not run
- **THEN** each remains `DEFERRED_TO_RELEASE_CANDIDATE` and does not block the KUM-35 intermediate merge
