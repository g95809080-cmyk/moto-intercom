## ADDED Requirements

### Requirement: Preserve main contracts
The integration SHALL retain main startup/background/group UI, group encryption and canonical process resource ownership while applying the separately reviewed Android fixes.

#### Scenario: Legacy and group resource ownership
- **WHEN** legacy asynchronous cleanup remains active
- **THEN** all compatibility callers observe the same canonical owner and group startup remains blocked until actual release

### Requirement: Independent peer rendering with shared capture
Group peers SHALL retain independent PC, renderer, mute and readiness while sharing one capture platform. Native callbacks MUST remain lock-free with respect to the session registry and validate their original producer.

#### Scenario: Middle peer closes
- **WHEN** the middle of three actual media peers closes
- **THEN** the other peers retain their PC, shared capture grant and current outputs and their actual RTP and writes continue

#### Scenario: Interrupted group resumes
- **WHEN** a group is suspended and resumed
- **THEN** the old capture/output producers are revoked, fresh native capture and per-peer Android writes resume, and PC identities remain unchanged

### Requirement: Peer readiness and failure isolation
Readiness SHALL require current shared capture and that peer's current rendered PCM and RTP. A single renderer failure MUST NOT revoke other peers' resources; fatal shared capture failure SHALL terminate the current group through its writer.

#### Scenario: One renderer is paused or muted
- **WHEN** another peer continues to render
- **THEN** the paused peer cannot borrow readiness, and the muted peer writes silence without stopping the other peers or capture

#### Scenario: Playback error arrives after recovery
- **WHEN** an old output worker fails while Main is already processing a newer recovery
- **THEN** its receipt cannot close the replacement output, while a current output failure still reaches its own peer

#### Scenario: Availability proof is revoked
- **WHEN** one peer or the shared route loses actual audio readiness
- **THEN** the room retains all current media leases, clears local confirmations, and requires fresh per-peer RTP evidence to restore voice readiness

### Requirement: Validation scope
The final gate SHALL include Android JVM, lint, APK, native SDK regression and remote CI on a fixed SHA. iOS SHALL remain explicitly excluded by user instruction and version identity SHALL be unchanged.

#### Scenario: Scope and version boundary
- **WHEN** the Android integration is reviewed
- **THEN** no iOS source, version properties, signing, release, merge or physical installation is performed

### Requirement: Concurrent pending socket cleanup
Pending socket cleanup SHALL snapshot concurrent registries without relying on a size-then-next single-element path and SHALL finish resource release when the last lease is simultaneously removed by its real release callback.

#### Scenario: Last pending lease releases during close
- **WHEN** the actual LAN HELLO or Wi-Fi Direct ready worker removes the last pending lease while the adapter takes its closing snapshot
- **THEN** close completes, sockets and executors are released, queued admission cannot install the closed session, and repeated close is safe

#### Scenario: Native fixture disposal boundary
- **WHEN** a native regression closes its own engines
- **THEN** the fixture waits for each actual disposal receipt, RTC executor termination, exact ownership token release and observed producer termination before another fixture begins, without clearing other owners or weakening the global release assertion
