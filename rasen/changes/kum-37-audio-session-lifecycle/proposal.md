## Why

The current recovery teardown closes `IntercomManager`, `RiderAudioEngine`, and
`AudioRouteController` together, so every transient media loss rebuilds WebRTC
audio capture, VOX state, and the Bluetooth/phone communication route. KUM-37
must move platform-audio ownership to the online runtime while keeping each
authorized WebRTC connection independently replaceable.

## What Changes

- Add a Service-owned `AudioSessionController` whose lifetime matches one online
  runtime and which closes only on the full Stop path.
- Split `RiderAudioEngine` into retained platform resources and one replaceable
  media-session handle that owns PeerConnection, SDP/ICE state, and its sender.
- Keep exactly one active media-session lease and make callbacks from released
  leases stale before their physical PeerConnection is disposed.
- Preserve the current audio route, audio source/track, VOX state, and WebRTC
  factory while transport discovery is restarted.
- Remove audio-platform teardown and reinitialization from attempt/recovery
  cleanup while preserving all signaling, Socket, LAN, and Wi-Fi Direct cleanup.
- Add deterministic ownership tests, fake session/callback tests, Android
  instrumentation, emulator evidence, and explicit Release Candidate deferrals.
- Record the Sprint 4 ownership decision that supersedes the KUM-27A
  media-session lifetime only for audio platform resources; winner authorization
  and single-WebRTC ownership remain unchanged.

## Capabilities

### New Capabilities

- `hot-audio-session-lifecycle`: Defines online-runtime ownership of audio
  platform resources, replaceable single WebRTC sessions, stale callback
  exclusion, stop-only release, and automated/physical evidence boundaries.

### Modified Capabilities

None.

## Impact

- `IntercomService`, `IntercomManager`, `RiderAudioEngine`, and attempt resource
  cleanup.
- A new `AudioSessionController`, lifecycle-focused JVM tests, and Android
  instrumentation.
- A Sprint 4 ownership ADR and KUM-37 evidence in Linear/PR documentation.
- No Signaling v2, TargetLock, target selection, transport race, database,
  pairing, identity, notification policy, dependency, signing, deployment, or
  production-release change.
