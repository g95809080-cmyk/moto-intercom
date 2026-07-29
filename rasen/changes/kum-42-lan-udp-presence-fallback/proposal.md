## Why

KUM-42 device evidence is asymmetric: MI 6 resolves Xiaomi 13 through NSD and
shows a selectable rider, while Xiaomi 13 continuously receives MI 6 LAN UDP
hello broadcasts but never records an NSD-resolved rider. The UDP hello already
contains the stable device ID, runtime session, nickname, model, protocol
version, IP, and TCP port, but the receiver currently logs and discards it.

## What Changes

- Promote a complete LAN UDP hello into the existing LAN device registry.
- Give UDP-derived candidates a short monotonic TTL refreshed by each hello.
- Remove and republish the candidate after its TTL when broadcasts stop.
- Keep NSD candidates and all existing Presence, TargetLock, signaling, and
  connection-attempt validation unchanged.
- Add deterministic parse, refresh, expiry, and malformed/self-frame tests.

## Capabilities

### New Capabilities

- `lan-udp-presence-fallback`: Supplies a TTL-bounded LAN Presence candidate
  when OEM/Android NSD resolution is asymmetric.

### Modified Capabilities

None.

## Impact

- Production: `LanDiscoveryCoordinator` and its LAN device registry only.
- Tests: LAN registry and UDP hello parsing/expiry regression coverage.
- No protocol/schema version, product-state ownership, deadline, TargetLock,
  Socket identity, pairing, database, audio, release, or deployment change.
