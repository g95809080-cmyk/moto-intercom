## Why

KUM-27 established one immutable attempt owner and one total 10-second budget,
but every attempt still plans exactly one transport and therefore cannot start a
preferred candidate at T0, add a fallback at T+5, or deterministically prefer a
late preferred candidate over an earlier fallback. KUM-28 adds that bounded
race without reopening ownership, deadline, target, or media-authority rules.

## What Changes

- Evolve immutable `ChannelPlan` from exactly one transport to an ordered plan
  containing one preferred transport and at most one distinct fallback.
- Start only the preferred transport at attempt creation and schedule the
  fallback at the immutable T+5 milestone without rebasing the T+10 deadline.
- Keep definitive preferred-transport failure fail-closed but do not start the
  fallback before T+5; terminate only when every planned path is exhausted or
  the total deadline wins.
- Reuse the existing optional `preferredTransportHint` to identify a dual plan:
  race attempts send the preferred transport, while single plans send no hint.
  No Signaling v2 version or envelope field changes.
- Let the responder Coordinator wait at most one second when only a fallback
  candidate is selection-ready, select the preferred candidate immediately if
  it arrives, and otherwise commit the fallback at the bounded window end.
- Record the actual winning transport in product `Connected` state while the
  immutable attempt retains its original plan.
- Cancel or invalidate fallback/optimization milestones on winner, terminal,
  replacement, runtime rollover, and stop; late callbacks remain fail-closed.
- Extend deterministic JVM coverage and reuse the three-emulator development
  matrix. Hardware/OEM race behavior remains
  `DEFERRED_TO_RELEASE_CANDIDATE`.

## Capabilities

### New Capabilities

- `transport-race-arbitration`: Defines ordered transport planning, T0/T+5
  scheduling, bounded fallback-first optimization, unique winner ownership,
  failure ordering, stale-event handling, recovery, and verification.

### Modified Capabilities

None. The KUM-27 ownership contract remains the governing architecture and is
implemented by the same Coordinator.

## Impact

- Runtime: `ConnectionAttempt`, Coordinator race state, attempt milestones,
  `SessionEvent`/`SessionEffect`, product `Connected` state, Service effect
  execution, and targeted LAN/Wi-Fi Direct admission checks.
- Tests: domain, scheduler, Coordinator, orchestrator, Service guard, adapter,
  simultaneous-request, stale-callback, deadline, and cleanup regressions.
- No new dependency, permission, database, identity, pairing, notification,
  WebRTC engine, production signing, deployment, or release change.

