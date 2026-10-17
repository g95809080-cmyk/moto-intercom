## ADDED Requirements

### Requirement: Idle discovery initialization survives disconnected hints
The adapter SHALL preserve current startup and service-registration callbacks when a disconnected broadcast arrives without an active or pending physical link.

#### Scenario: Startup query is outstanding
- **WHEN** discovery starts without an owned connection and a disconnected hint arrives before its group query returns
- **THEN** the same startup query can complete service registration and discovery becomes ready.

#### Scenario: Service callback is queued
- **WHEN** service registration is in flight on an idle adapter and a disconnected hint arrives
- **THEN** registration callbacks remain current and no connection-loss event is emitted.

#### Scenario: Closed startup returns late
- **WHEN** the adapter closes before a startup callback returns
- **THEN** the late callback cannot republish services or restart discovery.

#### Scenario: Targeted connection loses its group
- **WHEN** a current targeted or selected connection receives confirmed native group loss
- **THEN** the existing ownership, failure and cleanup rules still apply.

### Requirement: SDK capture cursor is separate from PCM frame range
The pinned SDK capture bridge SHALL expose its complete capacity-sized captured frame through an independent buffer view and SHALL preserve the SDK buffer position and limit.

#### Scenario: SDK mute advances the original buffer cursor
- **WHEN** a complete native frame passes through the SDK mute branch with its original position at capacity
- **THEN** the PCM callback receives the full frame from offset zero, RNNoise continues processing when authorized, and the SDK cursor remains unchanged.

#### Scenario: Invalid format or revoked producer
- **WHEN** actual PCM format is unsupported or producer/revision ownership is revoked
- **THEN** existing silence, model isolation and error handling remain enforced.
