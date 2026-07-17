## Why

KUM-27 cannot safely migrate to one immutable 10-second connection-attempt budget while attempt termination, deadline rebasing, winner claims, and cleanup are split across the reducer, `SignalingControlCoordinator`, `IntercomService`, and transport adapters. KUM-27A freezes the current and target ownership contract before any runtime behavior changes are allowed.

## What Changes

- Inventory every creator, scheduler, consumer, canceller, terminal decision, stale guard, and cleanup path associated with a `ConnectionAttempt`.
- Define the target boundary in which `SessionOrchestrator` remains the only product-state writer and one `ConnectionAttemptCoordinator` owns the logical attempt, immutable total deadline, transport candidates, and winner selection.
- Define the migration sequence, compatibility phases, old-owner removal points, rollback boundaries, and required tests for KUM-27B.
- Record callback validation requirements for LAN, Wi-Fi Direct, DNS-SD, HELLO, Signaling v2, SDP/ICE, PeerConnection, timeout, notification, recovery, and Service restart paths.
- Freeze KUM-27A as documentation-only. It does not add the Coordinator, change Android behavior, start a second transport, or implement KUM-28.

## Capabilities

### New Capabilities

- `connection-attempt-ownership`: Defines the auditable ownership, deadline, callback-validation, terminal-decision, winner, and cleanup contract required before KUM-27B implementation.

### Modified Capabilities

None. KUM-27A changes no existing runtime requirement or protocol behavior.

## Impact

- Adds Rasen planning artifacts for Linear issue KUM-27A and an independently reviewable architecture gate.
- Identifies future KUM-27B migration points in `SessionOrchestrator`, `IntercomStateMachine`, `SignalingControlCoordinator`, `IntercomService`, LAN discovery, Wi-Fi Direct, Socket signaling, and their tests without editing those files now.
- Adds no Android source changes, Gradle dependencies, permissions, database changes, identity changes, pairing changes, notification changes, or release behavior.
- KUM-27B remains blocked until a fixed-base/fixed-head `motointercom-product-architect` review returns APPROVED with P0=0 and P1=0.
