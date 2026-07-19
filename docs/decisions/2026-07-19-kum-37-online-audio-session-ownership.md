# KUM-37 Online Audio Session Ownership Decision

Date: 2026-07-19

Status: Accepted

## Decision

One online `RuntimeSessionId` owns one Service-managed `AudioSessionController`.
That controller owns the Android communication route and retained
`RiderAudioEngine` platform resources: `JavaAudioDeviceModule`,
`PeerConnectionFactory`, `AudioSource`, local `AudioTrack`, VOX state, and the
single RTC executor.

Each Coordinator-authorized media connection owns a replaceable
`RiderMediaSession` containing only PeerConnection, sender binding, SDP/ICE
state, remote-track callbacks, and media-session callbacks. At most one media
session may be active. Releasing it does not release the online audio platform.

Transient attempt failure, transport teardown, and `RECOVERING` restart close
the current signaling/PeerConnection/transport resources but retain the same
audio controller. Full Stop or Service destruction releases the media session,
engine, and route exactly once.

## Authority Boundaries

- `SessionOrchestrator` remains the only product-state writer.
- The connection Coordinator remains the only authority that selects the
  current attempt and WebRTC winner.
- `IntercomService` owns Android component lifetime and executes effects.
- `AudioSessionController` and `RiderAudioEngine` never select a target,
  transport, recovery policy, or product state.
- No PeerConnection or sender exists before the existing current-winner
  `StartWebRtc` authorization.
- Released media callbacks must pass both the current audio-session lease and
  immutable Service media-context checks.

## Superseded Decision

The KUM-27A ownership matrix stated that the whole `RiderAudioEngine` could not
exist before an accepted winner and closed with the media session. KUM-37
supersedes only that lifetime row:

- platform audio resources now exist for the online runtime; and
- PeerConnection/session resources still require accepted-winner authorization
  and still close with the media session.

KUM-27A rules for identity, target, attempt, winner, stale callbacks, signaling,
and single WebRTC ownership remain unchanged.

## No-Remote-Media Rule

Retained platform resources are not a remote-media authorization. When no
media-session handle exists there is no PeerConnection, RtpSender, remote track,
signaling binding, or local-loopback playback. Recovery may retain audio source,
track, VOX, and routing state without transmitting media to another rider.

## Validation

Development evidence consists of deterministic JVM lifecycle/fake callback
tests, Android instrumentation, the applicable emulator matrix, full Gradle
gates, CI, and fixed-SHA architecture review with P0=0/P1=0.

Bluetooth SCO, OEM route prompts, real microphone/speaker continuity, hardware
AEC, listening quality, power, thermal behavior, and long-duration background
survival remain `DEFERRED_TO_RELEASE_CANDIDATE` under the accepted development
validation decision. They are never represented as development PASS.

## Scope Boundary

This decision does not implement KUM-32 target retention, KUM-33 three-second
recovery/fallback, KUM-34 RESETTING, KUM-35 active disconnect, KUM-36 final
matrix work, any protocol/database change, signing, deployment, or release.
