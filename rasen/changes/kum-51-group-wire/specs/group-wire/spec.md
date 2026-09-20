## ADDED Requirements

### Requirement: Bounded independent wire frames
The codec SHALL reject invalid magic/version/type, malformed UTF-8, oversized fields, truncated frames and trailing data before returning a message. It MUST NOT generate admission proofs.

#### Scenario: Untrusted oversized stream
- **WHEN** a stream announces a frame above 65536 bytes
- **THEN** the reader rejects it before allocating or reading the body

#### Scenario: Legacy protocol
- **WHEN** a V2 JSON frame is passed to the group decoder
- **THEN** it is rejected without changing legacy code

### Requirement: Current channel authorization
The gate SHALL validate room, current local intent, sender channel lease, admitted endpoints, direction permissions, link lease and strictly increasing sequence. It MUST remain a pure state transition.

#### Scenario: Old connection after replacement
- **WHEN** a frame carries an old control generation or membership incarnation
- **THEN** it is rejected and no sequence or product state is changed

#### Scenario: Replay after accepted frame
- **WHEN** the same or lower sequence is received using the returned state
- **THEN** it is rejected

#### Scenario: Forged coordinator command
- **WHEN** a member sends an end/remove/unblock command to the host
- **THEN** the host gate rejects it regardless of payload identity
