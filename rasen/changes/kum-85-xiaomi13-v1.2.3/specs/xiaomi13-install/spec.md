## ADDED Requirements

### Requirement: Test package has a truthful patch identity
The repair test package SHALL be 1.2.3+f587472/code7, with version.properties as authority. It SHALL contain the reviewed Source and SHALL NOT reuse distributed code6 or old functional suffix bb0fa92.

#### Scenario: Build for the requested Xiaomi13
- **WHEN** the user requests installation of the reviewed repairs
- **THEN** only version/evidence preparation differs from Source; the resulting package declares the new patch identity.

### Requirement: Upgrade preserves existing installation
The non-debuggable Release package SHALL use the same historical distribution certificate and install with adb install -r. Existing data and permissions SHALL not be cleared.

#### Scenario: Upgrade from 1.2.2
- **WHEN** the signed package is installed on the paired API36 Xiaomi13
- **THEN** package identity and code are verified, preserved settings/logs are inspected and startup is checked separately from two-device listening acceptance.
