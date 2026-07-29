## ADDED Requirements

### Requirement: Connected owner send failure enters target-locked recovery

An unexpected signaling send failure on the established media-owner control channel SHALL enter the existing recovery flow rather than ordinary discovery.
The recovery attempt SHALL have a fresh attempt ID, preserve the connected
peer's TargetLock and transport plan, and retain existing monotonic timing.

#### Scenario: Send failure arrives before channel closure

- **WHEN** the connected media-owner signaling send fails before its reader
  reports channel closure
- **THEN** the product enters `RECOVERING`, restarts discovery for the same
  target, and the later channel-close callback is stale

#### Scenario: Wi-Fi returns during recovery

- **WHEN** recovery discovery restarts after the network is available and the
  original target is present
- **THEN** a new target-locked attempt opens automatically without a user tap

### Requirement: Intentional disconnect does not recover

User-requested active disconnect and full stop SHALL retain their existing terminal cleanup semantics.

#### Scenario: Disconnect send fails

- **WHEN** the owner channel is already in the `TERMINATING` phase for a user
  disconnect and its DISCONNECT send fails
- **THEN** cleanup returns to ordinary discovery without creating a recovery
  attempt
