## ADDED Requirements

### Requirement: Authorized pair signaling
The service MUST accept HTTPS only, authenticate provisioned devices, authorize both members of a provisioned pair and bind every message to its current runtime, session and call. It MUST bound message size, queue depth and lease lifetime.

#### Scenario: An unauthorized or stale sender publishes
- **WHEN** a token, pair membership, runtime, call or lease is invalid or expired
- **THEN** the service rejects the operation and no peer receives the payload.

#### Scenario: Both authorized members join
- **WHEN** two distinct members join with valid current leases
- **THEN** both receive the same call identity and complementary roles, and can exchange ordered offer, answer and ICE events.

### Requirement: Temporary relay credentials
The service MUST issue time-limited TURN REST credentials without revealing its shared secret. Clients MUST fail closed for missing, invalid or expired relay configuration.

#### Scenario: A relay session opens
- **WHEN** a current grant contains valid TURN credentials
- **THEN** native ICE uses RELAY policy and the existing audio owner opens at most one media session.

#### Scenario: Relay configuration expires
- **WHEN** credentials expire before opening or the grant is invalid
- **THEN** no direct-ICE fallback or second audio owner is created.

### Requirement: Native ownership and callback isolation
Android validation MUST acquire the same process-wide ownership token as the production service, use the existing AudioSessionController and finish native disposal before releasing ownership. iOS MUST reject overlapping starts and ignore callbacks from closed or replaced PeerConnections.

#### Scenario: The production owner is active
- **WHEN** a developer probe tries to start
- **THEN** the probe fails without creating an ADM or PeerConnection.

#### Scenario: An old callback arrives after close
- **WHEN** the callback belongs to a previous media generation
- **THEN** it cannot update, signal or close the current media session.

### Requirement: Reproducible relay evidence
The developer validation SHALL report the actual transport-selected candidate pair, current-session RTP growth and decoded synthetic audio at both peers. Missing evidence MUST remain unverified. Normal nearby behavior MUST retain its existing defaults.

#### Scenario: Software-only two-device verification
- **WHEN** each device sends a distinct synthetic tone through its native capture path
- **THEN** a pass requires a selected local relay candidate, growing media counters and the other device's decoded tone at both endpoints.

#### Scenario: Only local emulator evidence exists
- **WHEN** public cellular, latency percentile, silent-keepalive or iOS background tests have not run
- **THEN** the report lists those gates as unverified and does not claim production readiness.
