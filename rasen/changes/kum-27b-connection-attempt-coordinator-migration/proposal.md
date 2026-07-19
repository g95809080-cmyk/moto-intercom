## Why

KUM-27B needs one immutable connection-attempt vocabulary and a deterministic
monotonic time seam before ownership can move safely. B1 completed that pure
domain foundation, and B2 moved production attempt creation plus first-terminal
ownership into the existing `SignalingControlCoordinator`. B3 removes the
remaining mutable total-deadline paths and the false attempt used while an
unpaired inbound request awaits confirmation. B4 closes the remaining
upper-layer callback, logical-candidate, winner, and exact-cleanup ownership
gaps between that Coordinator and Service-owned physical handles. B5 makes
every targeted LAN/P2P/Socket operation consume the same immutable monotonic
attempt budget and reject stale adapter tasks.

## What Changes

- Add immutable value types for attempt identity, target identity, trigger,
  preferred transport, a single-transport `ChannelPlan`, monotonic deadline,
  and terminal outcome.
- Add a monotonic clock abstraction plus a deterministic fake clock for pure
  JVM boundary tests.
- Add matching/staleness helpers and tests for attempt, target, and deadline
  boundaries without routing any existing callback through the new model.
- Inject a deterministic attempt-ID factory into the existing Coordinator and
  make it create outbound and recovery attempts from intent data.
- Make the same Coordinator track the current logical attempt and preserve the
  first terminal outcome for timeout, cancellation, failure, disconnect, and
  success ordering.
- Route production attempt-ending events through the Coordinator before the
  generic reducer; `SessionOrchestrator` remains the only product-state writer.
- Inject the B1 `MonotonicClock` into the existing Coordinator and make it the
  only production source of every attempt's immutable 10-second deadline.
- Remove deadline inputs from outbound and recovery events, remove Service
  deadline creation, and replace deadline rebasing with one explicit
  `ScheduleAttemptDeadline` effect per newly created attempt.
- Represent an unpaired inbound request awaiting confirmation as a
  Coordinator-owned `PendingInboundRequest`, not a `ConnectionAttempt`; create
  the inbound attempt only after a current, valid local acceptance.
- Add an immutable connection-candidate context carrying runtime, attempt,
  channel, wire request, target, transport, and verified peer identity.
- Require Service signaling delivery, send completion, WebRTC callbacks, media
  buffering, and close effects to re-check the current Coordinator-owned
  candidate and winner before touching a physical handle.
- Replace channel-only Service policy claims with one contextual physical media
  locator; stale cleanup closes only the exact old handle and cannot close or
  authorize a replacement.
- Add pure remaining-budget helpers and pass immutable attempt context through
  LAN connect/HELLO, P2P connect/group/watchdog/retry, Socket-ready/connect
  loops, and delayed recovery restart execution.
- Clamp each targeted local cap or delay to the remaining total budget; cleanup
  may finish after terminal revocation but cannot revive or mutate a replacement
  attempt.
- Complete B6 with the full automated regression, a reusable two-to-three
  emulator matrix, deterministic synthetic-PCM verification, and a release
  physical-test plan. Physical execution is explicitly deferred to the Release
  Candidate and is never reported as passed during development.
- Keep KUM-28 transport fallback/racing behavior absent from this change.

## Capabilities

### New Capabilities

- `connection-attempt-domain-model`: Defines the B1 immutable attempt model,
  single-transport plan, monotonic deadline semantics, terminal outcomes, and
  deterministic clock test support.
- `connection-attempt-coordinator-ownership`: Defines the B2 production
  creation owner, current-attempt record, first-terminal-wins contract, and B3
  immutable total-deadline/pending-inbound cutover plus the B4 contextual
  candidate/callback/cleanup and B5 adapter remaining-budget contracts.

### Modified Capabilities

None. The approved KUM-27A ownership contract remains authoritative.

## Impact

- Evolves the existing `SignalingControlCoordinator` in place; no second
  Coordinator is created.
- Changes attempt-related event/effect ownership in `SessionOrchestrator`,
  `IntercomStateMachine`, `SignalingControlCoordinator`, and `IntercomService`
  while preserving Service as the physical effect executor.
- Preserves the 10-second attempt budget and 15-second human confirmation
  window, but makes attempt deadlines immutable and strictly monotonic.
- Changes Service-side callback routing, media buffering, and exact physical
  cleanup without changing Signaling v2 or WebRTC protocol semantics.
- Changes targeted LAN/P2P/Socket timeout and retry execution only by clamping
  existing local caps to the immutable remaining attempt budget and adding
  stale task checks. TargetLock, UI, database, pairing, identity, notification,
  permissions, and release dependencies remain unchanged.
- B6 adds Android test-runner dependencies, test-only synthetic audio helpers,
  emulator orchestration scripts, and verification documents. None of these
  classes or harnesses are packaged in the release application code path.
