## Context

LAN discovery has two existing signals. NSD resolves a service into
`LanRiderDevice` and therefore into `RiderPresence`; UDP hello broadcasts carry
the same connection metadata but currently only emit a log. On the Xiaomi 13
device, UDP reception is continuous while NSD resolution of MI 6 never
completes, producing the observed "found in logs, no connect button" state.

## Goals / Non-Goals

**Goals:**

- Reuse complete UDP hello metadata as a LAN candidate.
- Keep the candidate selectable only while broadcasts remain fresh.
- Preserve one stable Presence grouping by device ID/runtime session.
- Allow the current targeted LAN connector to use the fallback endpoint.

**Non-Goals:**

- No NSD replacement or discovery restart policy.
- No automatic connection, requester-role memory, glare, recovery, or deadline
  change.
- No acceptance of incomplete identity claims.

## Decisions

1. **Parse into a pure value first.** A helper validates message type, local
   exclusion, stable device/session identity, source IP, and TCP port before the
   registry is touched.
2. **Reuse `LanDiscoveryDeviceRegistry`.** UDP and NSD are physical discovery
   endpoints for the same stable rider. Presence aggregation already groups
   them by device ID and transport availability.
3. **Use monotonic TTL.** Each UDP endpoint receives an absolute monotonic
   expiry refreshed by every hello. Socket receive timeouts trigger expiry.
   Wall-clock time is not used.
4. **Keep current trust boundaries.** UDP metadata remains a discovery claim.
   TargetLock is created only after explicit selection, and current Socket
   HELLO identity must still match before the channel is verified or media
   starts.

## Risks / Trade-offs

- UDP and NSD may expose duplicate LAN endpoints. Presence groups them under the
  same stable device; transport planning remains one LAN transport.
- A spoofed LAN broadcast can still create an unverified discovery row. This is
  no stronger than NSD discovery and cannot pass current Socket identity checks
  without a matching HELLO. Cryptographic LAN authentication remains out of
  scope.
- Too-short TTL can flicker under packet loss. Use three broadcast intervals,
  while each one-second hello refreshes the same endpoint.

## Migration Plan

1. Add failing parser and expiring-registry tests.
2. Register complete UDP hello frames and expire them on receive timeout.
3. Run full Gradle, CI, fixed-SHA review, and bidirectional two-device LAN
   selection without full Stop.

Rollback is a revert of the KUM-42 merge commit. There is no data migration.
