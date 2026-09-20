## ADDED Requirements

### Requirement: Room admission is scoped and bounded
The domain SHALL enforce a distinct room/runtime identity, verified admission evidence and a capacity of four including the host.
#### Scenario: Competing final seat
- **WHEN** two valid join events are reduced serially for one available seat
- **THEN** exactly one is admitted and the other receives FULL without media effects.
#### Scenario: Invalid proof or old room
- **WHEN** room, version, identity or credential evidence is invalid
- **THEN** admission is denied and no member slot is allocated.

### Requirement: Recovery preserves only the affected member
The domain SHALL reserve a lost member for 60 seconds from first detection and release the seat at the deadline while retaining recovery eligibility.
#### Scenario: Deadline and full return
- **WHEN** the deadline expires and a newcomer occupies the seat
- **THEN** the old member waits without displacing anyone and can resume when a seat is available.
#### Scenario: Stale callbacks
- **WHEN** a previous runtime, incarnation or control generation reports loss or departure
- **THEN** the replacement member is unchanged and no new reservation begins.

### Requirement: Host authority and terminal decisions are enforced
The domain SHALL allow only the current host to remove, unblock or end the room, and SHALL revoke removed or departed membership.
#### Scenario: Removed member retries
- **WHEN** a removed stable device submits valid credentials with a new runtime
- **THEN** it remains rejected until host unblock, which does not itself admit the member.

### Requirement: Readiness requires both endpoints of every active pair
The domain SHALL track link generations independently from roster revisions and require both endpoints to confirm current audio readiness.
#### Scenario: Four members
- **WHEN** only the host's three pairs are ready
- **THEN** the room is partial rather than fully ready; all six pairs are necessary.
#### Scenario: Unrelated membership change
- **WHEN** C joins or leaves while A and B remain admitted
- **THEN** the A-B lease and evidence remain valid.

### Requirement: Participation is process-local and revocable
The local model SHALL start idle and keep per-room mute choices only during valid participation.
#### Scenario: Stop and delayed completion
- **WHEN** participation stops then an old callback completes
- **THEN** it cannot restore participation, audio intent or mute state.
