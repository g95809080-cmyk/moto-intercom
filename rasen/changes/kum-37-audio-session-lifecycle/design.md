## Context

`IntercomService` currently creates `AudioRouteController` when an online
runtime starts, but `abortResourcesAndResumeDiscovery()` treats it as an attempt
resource: it closes and nulls the route, invalidates the transient Service
generation, then creates another route after the recovery backoff.

`IntercomManager` similarly creates one `RiderAudioEngine` per accepted media
session. That engine owns both long-lived platform audio resources
(`JavaAudioDeviceModule`, `PeerConnectionFactory`, `AudioSource`, local
`AudioTrack`, and VOX state) and per-session resources (PeerConnection, sender,
SDP/ICE state, and callbacks). Closing the manager therefore destroys both
layers.

KUM-37 changes this ownership without changing product-state authority.
`SessionOrchestrator` remains the only product-state writer, Coordinator winner
authorization remains mandatory before WebRTC, and Service remains the Android
lifecycle/effect executor. KUM-32 through KUM-36 remain out of scope.

## Goals / Non-Goals

**Goals:**

- Match route and audio-platform lifetime to one online runtime.
- Replace only PeerConnection and signaling/media callback bindings during
  transient disconnect and recovery.
- Preserve local audio source/track and VOX state across media sessions.
- Permit exactly one active media session and fail closed on stale callbacks.
- Keep no remote sender, remote track, or loopback path while no authorized
  session exists.
- Prove ownership with deterministic fakes and actual Android instrumentation;
  defer hardware/OEM/acoustic observations to the Release Candidate.

**Non-Goals:**

- No recovery target lock, three-second window, fallback policy, retry backoff,
  failure counter, `RESETTING`, active disconnect, or Sprint 5 work.
- No Signaling v2, TargetLock, identity, transport race, database, pairing,
  notification policy, UI redesign, dependency, signing, deployment, or release
  change.
- No claim that an emulator proves Bluetooth SCO, hardware AEC, OEM routing, or
  human listening quality.

## Decisions

1. **Add one Service-owned `AudioSessionController`.** The controller owns the
   existing `AudioRouteController` and one retained `RiderAudioEngine`. Service
   creates it after runtime identity is ready and closes it only from the full
   Stop/destroy path. Attempt cleanup does not receive an audio close callback.
   This makes the runtime boundary explicit instead of relying on nullable
   Service fields as policy.

2. **Bind route callbacks to `RuntimeSessionId`, not transient
   `SessionGeneration.Token`.** Recovery deliberately invalidates and replaces
   the transport generation. Audio callbacks must survive that rollover but
   still fail closed after runtime stop/replacement, so they check `running` and
   the immutable active runtime ID.

3. **Split retained engine resources from a `RiderMediaSession` handle.**
   `RiderAudioEngine` initializes the audio device module, factory, source,
   local track, executor, and VOX once. `openSession()` creates one handle that
   owns PeerConnection, sender, remote-description state, pending ICE, and
   callbacks. Releasing the handle disposes only those session resources.
   Reusing the source/track is smaller and safer than recreating the complete
   engine or introducing a second live audio engine.

4. **Invalidate the logical lease before asynchronous disposal.**
   `AudioSessionController` marks a handle non-current before calling close.
   The engine combines that lease with Service's immutable media-context check
   before posting callbacks. A single RTC executor preserves old-dispose then
   new-open ordering, while an old late close cannot release the replacement.

5. **Represent no-remote-media by absence of a media handle.** Retained audio
   source/track and VOX state are platform resources, not authorization. Without
   a current handle there is no PeerConnection, RtpSender, remote track, or
   signaling callback binding. Service still creates a handle only while
   executing the existing current-winner `StartWebRtc` effect.

6. **Preserve audio status during attempt cleanup.** Recovery clears physical
   transport/media connection state and the remote rider locator, but it does
   not reset Bluetooth readiness or publish the standby route while the runtime
   still owns the same route. Full Stop retains the existing complete reset.

7. **Supersede only the old KUM-27A audio-resource row.** The prior design said
   the whole `RiderAudioEngine` closed with a media session. Sprint 4 now makes
   platform audio runtime-owned while the WebRTC session remains
   Coordinator-authorized and media-session-owned. All winner, identity, stale
   callback, and single-media-owner rules remain in force.

8. **Use layered evidence.** JVM tests use fake engine/session/route handles to
   prove reuse, exactly-once close, no concurrent session, stale lease rejection,
   and Stop-only platform release. Android instrumentation opens two sequential
   actual WebRTC sessions on one engine and verifies retained platform object
   identity and one active PeerConnection. Emulator logs and the full Gradle/CI
   gate provide integration evidence. Real SCO, microphone, speaker, AEC,
   listening, power, and thermal behavior remain Release Candidate work.

## Risks / Trade-offs

- **A WebRTC callback races session replacement.** -> Invalidate the controller
  lease first, retain Service candidate checks, and serialize physical disposal
  and new session creation on one executor.
- **A second session is opened before the first is released.** -> Reject it
  synchronously; Service must close the old manager before creating the new one.
- **The retained local track accidentally implies remote send.** -> Keep sender
  and PeerConnection inside the media handle; no handle means no network media
  path or remote playout object.
- **Engine initialization fails before a peer connects.** -> Report through the
  runtime-scoped error callback; a later media open fails closed instead of
  creating a partial second engine.
- **Android/WebRTC capture-thread behavior differs by OEM.** -> Automated tests
  prove resource ownership, no reinitialization, and no remote path. Real SCO,
  microphone continuity, route prompts, AEC, and listening remain explicitly
  `DEFERRED_TO_RELEASE_CANDIDATE` under the accepted validation ADR.
- **A retained route makes old UI status stale.** -> Runtime-scoped route
  callbacks continue updating the same online runtime; attempt cleanup no longer
  overwrites route status with standby.

## Migration Plan

1. Strictly validate the Rasen contract and accepted ownership ADR.
2. Add the controller and split retained/session media resources with JVM tests.
3. Rewire Service/manager lifecycle and remove attempt-level audio teardown.
4. Run targeted JVM tests, full JVM, Lint, debug/test APK builds, and Android
   instrumentation/emulator scenarios.
5. Complete fixed-SHA review, CI, merge-commit delivery, green main CI, and
   Linear evidence before starting KUM-32.

Rollback is one revert of the KUM-37 merge commit. No data or protocol migration
is required.

## Open Questions

None.
