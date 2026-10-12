## ADDED Requirements

### Requirement: Verify current Bluetooth native I/O
Bluetooth readiness MUST require the currently verified system route, the current media lease, matching actual Bluetooth native input/output and fresh PCM/rendered data.

#### Scenario: Communication route is Bluetooth but the recorder is built-in
- **WHEN** a connected current media session has verified system Bluetooth routing but its actual input or output is not Bluetooth
- **THEN** Bluetooth audio readiness remains false
- **AND** the diagnostic includes the current route revision and actual input/output types

### Requirement: Recover without another manual selection
The current connected media session MUST automatically reapply its preferred route after persistent native I/O mismatch, through the existing phone/focus coordinator, with at most two automatic attempts per media lease.

#### Scenario: Native input remains wrong after startup
- **WHEN** connected media has a Bluetooth native mismatch continuously for two seconds
- **THEN** the current coordinator suspends, reapplies and resumes audio without another user tap
- **AND** the existing PeerConnection, signaling ownership and transmission controls are preserved

#### Scenario: Interruption, phone preference or media replacement
- **WHEN** the user chooses phone audio, phone/focus interrupts, the upstream owner is revoked or media A is replaced by media B
- **THEN** media A's pending probe or timer cannot reapply Bluetooth or certify media B
- **AND** disconnected startup waiting cannot spend the automatic I/O recovery budget

#### Scenario: Activity returns from the background
- **WHEN** the current media has exhausted its two automatic retries and Activity stop/start rebinds the same Service
- **THEN** reapplying the saved preference does not grant additional automatic retries
- **AND** an explicit user selection, including the same Bluetooth option, can reset that media's recovery budget

#### Scenario: Same media metadata delivery is late
- **WHEN** native input/output metadata from the current media is delivered more than two seconds after its RTC sample or claims a future sample time
- **THEN** it cannot certify Bluetooth or spend automatic recovery budget
- **AND** fresh subsequent PCM cannot renew the age of the old device metadata

### Requirement: Restore granted Bluetooth permission
Permission grant refresh MUST reapply the saved Bluetooth preference once through the actual Activity/Service boundary, without replacing an explicit phone preference or claiming a missing headset.

#### Scenario: User grants permission in settings or the permission result
- **WHEN** BLUETOOTH_CONNECT changes from missing to granted while Bluetooth remains preferred
- **THEN** the current preferred route is reapplied without another audio-output tap
- **AND** subsequent refresh with unchanged granted permission causes no additional reapply
