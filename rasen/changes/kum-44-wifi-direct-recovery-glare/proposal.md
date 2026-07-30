## Why

KUM-44's deterministic glare convergence is reached only after both peers open
a recovery signaling channel. Physical Wi-Fi Direct evidence showed that the
first and second KUM-34 retries spent most of their immutable T+10 budget
closing and recreating discovery adapters, so the peers could reach the third
failure without opening that channel.

## What Changes

- Keep the KUM-44 canonical same-target recovery glare decision.
- On the first and second final recovery failures, close attempt-owned media,
  signaling, and targeted sockets while retaining the live LAN and Wi-Fi Direct
  discovery adapters.
- Rebind retained adapters to the fresh Coordinator-owned attempt only when the
  runtime, complete `TargetLock`, planned transport, and deadline are current.
- Preserve an existing Wi-Fi Direct group or in-flight exact-target connect
  where possible, and suppress stale callbacks from the replaced attempt.
- Keep the existing 1.5-second bounded retry delay inside each fresh immutable
  T+10 deadline.
- Keep the third consecutive final failure on the complete KUM-34 cleanup and
  rebuild path.

## Impact

- Runtime execution: `IntercomService`, `WifiDirectTunnel`, and
  `LanDiscoveryCoordinator`.
- Domain gate: one shared pure attempt-reuse predicate.
- Tests: deterministic reuse admission plus existing recovery, stale callback,
  glare, cleanup, and third-reset suites.
- No protocol, TargetLock, deadline, product-state writer, WebRTC/audio,
  database, identity, UI, signing, deployment, or release change.
