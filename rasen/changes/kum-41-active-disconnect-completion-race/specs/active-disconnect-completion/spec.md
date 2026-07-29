## ADDED Requirements

### Requirement: Signaling send completion survives concurrent session closure
The Service SHALL convert every accepted control-message write result into one
immutable Coordinator event before posting it to the main thread. A session
closing or leaving the Service session map before main-thread delivery MUST NOT
suppress that result.

#### Scenario: Peer closes after successful DISCONNECT write
- **WHEN** a `DISCONNECT` write succeeds and the peer closes the same control session before main-thread delivery
- **THEN** Service dispatches `SignalingMessageSent` for the exact runtime, attempt, channel, and message type

#### Scenario: Writer closes after failed DISCONNECT write
- **WHEN** a `DISCONNECT` write fails and the writer closes the control session before main-thread delivery
- **THEN** Service dispatches `SignalingSendFailed` for the exact runtime, attempt, channel, message type, and failure

### Requirement: Coordinator remains the sole disconnect terminal authority
Completion delivery SHALL NOT mutate product state directly. The existing
Coordinator MUST accept only a completion matching its current immutable
runtime, attempt, and channel ownership, and stale completion from an old
session MUST NOT terminate or clean a replacement attempt.

#### Scenario: Exact completion ends active disconnect
- **WHEN** the current owner-channel `DISCONNECT` completion is delivered
- **THEN** the Coordinator authorizes exact-attempt release and transitions the same runtime to `DISCOVERING`

#### Scenario: Old completion follows replacement
- **WHEN** an old session completion arrives after its attempt has ended or been replaced
- **THEN** the Coordinator rejects it without changing current state, target, media, transport, or discovery ownership

### Requirement: Explicit disconnect remains bounded and reconnectable
After local or accepted peer disconnect, the product SHALL leave the connected
action state, retain its online runtime and discovery owners, and allow the same
peer to be selected and connected again without a full Stop or process restart.

#### Scenario: LAN disconnect and reconnect without full Stop
- **WHEN** two devices connected over LAN explicitly disconnect, rediscover, and either available selection entry initiates a new connection without full Stop
- **THEN** both return to `DISCOVERING`, expose selectable Presence, establish exactly one new session, and carry bidirectional audio

#### Scenario: Concurrent close callbacks
- **WHEN** socket close, media close, and signaling completion callbacks race during explicit disconnect
- **THEN** exact-attempt cleanup completes once and no callback can strand `CONNECTED`, erase a replacement attempt, or require full runtime restart
