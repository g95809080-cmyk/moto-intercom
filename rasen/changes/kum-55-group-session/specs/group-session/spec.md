## ADDED Requirements
### Requirement: Authenticated bootstrap and admission
The system SHALL disclose private network credentials only after complete PAKE confirmation and SHALL require fresh IP-channel authentication before room admission.
#### Scenario: Wrong code or cancelled exchange
- **WHEN** code confirmation fails or the owner is cancelled
- **THEN** no credentials, admission proof, seat or media is granted.
### Requirement: Single writer and current channel authority
Only the group product writer SHALL change room state; all frames and effects SHALL match current intent, channel and member/link generations.
#### Scenario: Old socket after reconnection
- **WHEN** a replaced socket emits a frame or close callback
- **THEN** it cannot revoke or mutate its successor's membership or media.
### Requirement: Recovery preserves room rules
The host SHALL preserve a lost member seat for sixty seconds from first detected loss, and clients SHALL retry the same room without evicting admitted members.
#### Scenario: Full room on low-frequency retry
- **WHEN** a member returns after its reservation expired and all seats are occupied
- **THEN** it remains waiting and retries later without removing a member.
### Requirement: Local audio and complete mesh readiness
Audio interruption SHALL affect only the local participant; readiness SHALL require connected media and remote track evidence for current links.
#### Scenario: One participant takes a phone call
- **WHEN** local audio becomes unavailable
- **THEN** control membership stays live and other healthy pairs remain usable, with user mute/block preferences preserved.
