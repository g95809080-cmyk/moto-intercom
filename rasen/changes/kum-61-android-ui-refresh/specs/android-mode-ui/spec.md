## ADDED Requirements

### Requirement: Present the active mode from service facts
The Android home SHALL display active group state from the service observer and SHALL route back to the group screen without sending Leave. Single-rider action labels SHALL match the existing dispatched action.

#### Scenario: Return while group owns resources
- **WHEN** the service publishes a busy group snapshot
- **THEN** home displays group status and a return action instead of single-rider call controls

### Requirement: Preserve a failed room entry
The room screen SHALL retain the six-digit input in current composition on failure, SHALL prevent duplicate starts during permission requests, and SHALL not persist room codes.

#### Scenario: Permission is denied
- **WHEN** joining is rejected by permissions
- **THEN** the input remains available for retry

### Requirement: Explain scope and destructive actions
The UI SHALL distinguish session audio from default preferences, SHALL require confirmation to end the whole room or remove a member, and SHALL keep diagnostics accessible through disclosure.

#### Scenario: Host ends a room
- **WHEN** the host chooses to end the room
- **THEN** a dialog explains that all members leave and the code expires before dispatch
