## ADDED Requirements
### Requirement: Service mode ownership
The app SHALL use one foreground service with mutually exclusive legacy and group resources, including pending cleanup.
#### Scenario: Group active or cleanup unknown
- **WHEN** legacy mode is requested while group owns resources
- **THEN** the request is rejected without ending the group or acquiring another audio/network owner.
### Requirement: User group lifecycle
The app SHALL expose confirmed PRD create/join/select/member/audio/host controls and preserve a live room across page recreation without process resurrection.
#### Scenario: Recreated page
- **WHEN** an active group page is recreated
- **THEN** it observes the service snapshot and neither rejoins nor leaves automatically.
#### Scenario: Process restart
- **WHEN** a fresh service receives no explicit create/join action
- **THEN** it remains idle with no room, reconnection or microphone capture.
### Requirement: Real media evidence
The app SHALL report full voice readiness only from current-link RTP growth, actual ADM I/O and verified route evidence at both endpoints.
#### Scenario: Connected PC without actual I/O
- **WHEN** a PC is connected but capture or playback is not active
- **THEN** no voice confirmation is emitted.
### Requirement: Same-room network recovery
The app SHALL recover only the original authenticated room, with exclusive GO cleanup and attempt-bound callbacks.
#### Scenario: Recreated host network has new credentials
- **WHEN** an existing member retries after host GO recreation
- **THEN** BLE reauthentication refreshes network credentials only for the same room and host before reconnecting.
