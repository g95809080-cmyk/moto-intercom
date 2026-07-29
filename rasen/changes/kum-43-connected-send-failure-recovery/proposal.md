## Why

Release-candidate T4 showed that disabling Wi-Fi can make the established
media-owner control channel report a send failure before its reader reports
closure. The send-failure path currently terminates the successful attempt into
ordinary discovery, while the later close is stale. This drops the original
target and prevents automatic recovery after Wi-Fi returns.

Exact-Head physical validation also showed that socket EOF was wrapped as a
protocol violation. That path likewise terminated the connected owner into
ordinary discovery instead of reporting transport closure.

## What Changes

- Treat a media-owner signaling send failure in an established connected phase
  as unexpected connection loss.
- Preserve socket I/O failures as transport failures so established reader EOF
  reaches the existing channel-closed recovery path; malformed protocol frames
  remain protocol violations.
- Reuse the existing KUM-32/KUM-33 recovery transition, fresh attempt identity,
  immutable target, T+3 fallback, and T+10 deadline.
- Preserve the existing user-requested disconnect completion path.

## Impact

The change is confined to control-channel failure routing and deterministic
tests. It does not change discovery protocols, TargetLock, pairing, WebRTC,
audio ownership, Wi-Fi Direct setup, persistence, or release behavior.
