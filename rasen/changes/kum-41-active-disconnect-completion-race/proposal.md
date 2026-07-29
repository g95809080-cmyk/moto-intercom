## Why

Release Candidate testing on MI 6 and Xiaomi 13 showed that an explicit active
disconnect can leave both products stuck in the connected action state and
unable to reconnect until the full runtime is stopped. The Service currently
drops a completed signaling write when the peer closes the same control session
before the completion reaches the main thread, so the Coordinator never receives
the terminal event that authorizes exact-attempt cleanup.

## What Changes

- Preserve every signaling write result as an immutable Coordinator event before
  crossing back to the main thread.
- Dispatch that result even when the corresponding session closed or was removed
  before main-thread delivery.
- Keep stale/replacement protection in the existing Coordinator attempt and
  channel identity gates.
- Add deterministic coverage for successful and failed `DISCONNECT` completion
  after session closure, plus the existing local/remote disconnect lifecycle.
- Verify LAN disconnect, retained discovery, reconnect without full Stop, and
  current-Head two-device behavior.

## Capabilities

### New Capabilities

- `active-disconnect-completion`: Guarantees that an exact active-session
  disconnect reaches one terminal Coordinator event despite a concurrent socket
  close, allowing bounded cleanup and return to discovery.

### Modified Capabilities

None.

## Impact

- Production: `IntercomService` signaling-send completion delivery only.
- Tests: deterministic completion mapping and KUM-35 disconnect regression
  coverage.
- No protocol/schema, state ownership, deadline, target, discovery policy,
  identity, database, dependency, permission, audio ownership, signing, release,
  or deployment change.
