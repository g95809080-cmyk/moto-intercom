## ADDED Requirements

### Requirement: Independent peer resources
The group media implementation SHALL share one audio platform while owning each peer connection independently and limiting local peers to three. The legacy entry MUST remain single-session.

#### Scenario: One of three peers leaves
- **WHEN** the middle peer lease closes
- **THEN** the other sessions and shared audio demand remain active

### Requirement: Revocable media effects
Media creation, signaling and callbacks MUST validate current immutable intent and peer link leases. Shared demand SHALL begin once and end after the last peer, including failure paths.

#### Scenario: Late close after replacement
- **WHEN** an old lease closes after a new session is active
- **THEN** it cannot close the new session or end its audio demand

### Requirement: Local playback isolation
Per-peer playback mute SHALL affect only the peer track and be reapplied on replacement. Phone interruption SHALL suspend shared I/O without closing peers or changing user mute.

#### Scenario: Muted peer reconnects
- **WHEN** a replacement remote track arrives
- **THEN** current local playback mute applies before playback
