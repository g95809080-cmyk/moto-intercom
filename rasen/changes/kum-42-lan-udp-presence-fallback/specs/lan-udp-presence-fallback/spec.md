## ADDED Requirements

### Requirement: Complete LAN UDP hello creates a bounded discovery candidate
The LAN discovery runtime SHALL register a UDP hello as a LAN candidate only
when it carries a non-local stable device ID, runtime session ID, source
address, and valid TCP port.

#### Scenario: NSD is unavailable but UDP hello is complete
- **WHEN** a current runtime receives a complete remote `MOTOCOM_HELLO`
- **THEN** the remote rider appears as an available LAN Presence candidate and can be explicitly selected

#### Scenario: Hello is local or incomplete
- **WHEN** a hello claims the local device or omits stable identity or a valid endpoint
- **THEN** it is ignored without changing Presence or connection ownership

### Requirement: UDP candidate lifetime is monotonic and refreshed
Each UDP-derived LAN candidate MUST have a monotonic expiry refreshed by every
matching hello and MUST be removed after broadcasts remain absent for the
configured TTL.

#### Scenario: Repeated hello refreshes the same endpoint
- **WHEN** another matching hello arrives before expiry
- **THEN** the existing endpoint remains available and its expiry moves forward

#### Scenario: Broadcasts stop
- **WHEN** no matching hello arrives through the expiry boundary
- **THEN** the UDP endpoint is removed and the updated Presence snapshot is published

### Requirement: UDP remains an unverified discovery claim
The fallback SHALL NOT bypass explicit Presence selection, TargetLock, current
Socket identity validation, attempt deadline, or Coordinator ownership.

#### Scenario: Selected UDP endpoint opens a Socket
- **WHEN** the user selects a UDP-derived Presence candidate
- **THEN** the normal USER attempt and Signaling v2 HELLO checks execute before the control channel or media can become active
