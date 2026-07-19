## Why

Recovery already creates a fresh attempt that retains the connected rider's
`TargetLock`, but adapter startup and passive verified-Socket handoff are not
locked to that target from the first recovery callback. A faster nearby rider
can therefore consume resources before the Coordinator rejects the request,
and the UI/foreground notification do not name the rider being recovered.

## What Changes

- Seed every recovery transport adapter with the immutable recovery attempt so
  LAN HELLO and Wi-Fi Direct group validation reject non-target identities from
  adapter startup, including before a transport is actively opened.
- Add a Service handoff gate that rejects and closes any verified recovery
  Socket whose `TargetLock` does not match the current recovery attempt.
- Keep Presence selection, ordering, and inbound requests subordinate to the
  existing Coordinator; none may replace the recovery target.
- Show `正在恢复与 {车友} 的连接` in both the in-app state detail and foreground
  notification, using the peer retained by `IntercomState.Recovering`.
- Add deterministic three-node B-down/C-faster coverage plus targeted adapter,
  Socket, state, and presentation tests.
- Record automated/emulator evidence while keeping OEM Wi-Fi Direct, RF,
  Bluetooth SCO, acoustic, power, and background checks
  `DEFERRED_TO_RELEASE_CANDIDATE`.

## Capabilities

### New Capabilities

- `recovery-target-lock`: Defines immutable original-target recovery across
  Coordinator state, transport ingress, verified Socket handoff, presentation,
  and three-node race verification.

### Modified Capabilities

None.

## Impact

- `IntercomService`, `LanDiscoveryCoordinator`, and `WifiDirectTunnel` recovery
  ingress wiring.
- Shared UI/notification status text and deterministic JVM tests.
- KUM-32 Rasen artifacts and the Sprint 4 verification index.
- No recovery timing/fallback policy, RESETTING policy, active-disconnect,
  Signaling v2 wire format, identity, pairing, database, WebRTC, dependency,
  signing, deployment, or production-release change.
