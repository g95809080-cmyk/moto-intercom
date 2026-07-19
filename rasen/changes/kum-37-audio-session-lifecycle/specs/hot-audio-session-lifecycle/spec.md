## ADDED Requirements

### Requirement: Online runtime owns audio platform resources
`IntercomService` SHALL create one `AudioSessionController` for an online
runtime. The controller SHALL own the communication route and retained
`RiderAudioEngine` platform resources until the runtime is fully stopped.

#### Scenario: Online runtime starts
- **WHEN** a permitted runtime enters its online discovery lifecycle
- **THEN** exactly one audio route and one retained audio engine are initialized for that runtime

#### Scenario: Discovery restarts after media loss
- **WHEN** the current signaling, WebRTC, or transport resources are torn down for recovery
- **THEN** the runtime keeps the same audio route and retained audio engine instances

#### Scenario: Runtime fully stops
- **WHEN** the user stops intercom or the Service runtime is destroyed
- **THEN** the current media session, retained audio engine, and audio route are released exactly once

### Requirement: WebRTC session is replaceable and singular
The retained audio engine SHALL expose a replaceable media-session handle. Each
handle SHALL own only its PeerConnection, SDP/ICE state, sender binding, remote
track callbacks, and session callbacks. At most one handle SHALL be active.

#### Scenario: Authorized media starts
- **WHEN** the current Coordinator winner authorizes WebRTC for an accepted channel
- **THEN** one media-session handle attaches the retained local audio track to one new PeerConnection

#### Scenario: Media session ends
- **WHEN** the current media context disconnects, fails, is replaced, or is canceled
- **THEN** its PeerConnection and session state close without releasing the retained factory, audio device module, audio source, local track, VOX state, or route

#### Scenario: Concurrent media open is attempted
- **WHEN** a second media-session handle is requested before the current handle is released
- **THEN** the request fails closed and no second PeerConnection or media sender is created

#### Scenario: Reconnect succeeds
- **WHEN** a released media session is followed by a newly authorized media session in the same runtime
- **THEN** the new PeerConnection reuses the retained audio platform resources and does not reinitialize the communication route

### Requirement: Released media callbacks are stale
The audio-session owner SHALL invalidate a media-session lease before physical
session disposal. Session callbacks SHALL require both the current lease and the
existing immutable Service media context.

#### Scenario: Old callback arrives after release
- **WHEN** SDP, ICE, connection-state, audio-level, or error work from a released media session arrives late
- **THEN** it cannot update product state, send signaling, claim media, close the replacement session, or alter the retained route

#### Scenario: Old release arrives after replacement
- **WHEN** cleanup for an older media session runs after a replacement session is active
- **THEN** only the older physical session is affected and the replacement remains current

### Requirement: No remote media exists without an active session
Retained platform resources SHALL NOT imply an authorized remote media path.
Without an active media-session handle there SHALL be no PeerConnection sender,
remote track, signaling callback binding, or local-loopback playback.

#### Scenario: Runtime is discovering or recovering
- **WHEN** the online runtime has no active authorized media-session handle
- **THEN** retained capture/VOX resources remain owned by the runtime while no media is transmitted to or played from a remote peer

#### Scenario: New winner is not yet authorized
- **WHEN** transport recovery or discovery finds a channel before the Coordinator emits `StartWebRtc`
- **THEN** the audio-session controller does not create a media-session handle

### Requirement: KUM-37 preserves later Sprint boundaries
KUM-37 SHALL change only audio ownership and lifecycle. It SHALL NOT choose a
recovery target, implement a three-second recovery window, count final recovery
failures, enter `RESETTING`, or change active-disconnect product behavior.

#### Scenario: KUM-37 recovery cleanup executes
- **WHEN** existing recovery effects restart discovery
- **THEN** all current target, deadline, transport, and product-state semantics remain unchanged except that audio platform resources are retained

### Requirement: Automated gate and physical deferral are explicit
KUM-37 SHALL pass deterministic JVM tests, fake session/callback tests, Android
instrumentation, the applicable emulator matrix, full Gradle gates, CI, and a
fixed-SHA architecture review with P0=0 and P1=0. Hardware-only audio evidence
SHALL remain `DEFERRED_TO_RELEASE_CANDIDATE` until final acceptance.

#### Scenario: Development gate passes
- **WHEN** automated, emulator, CI, architecture, Git, PR, Linear, and evidence records agree
- **THEN** KUM-37 may complete and merge without connected physical devices

#### Scenario: Hardware evidence is not run
- **WHEN** Bluetooth SCO, OEM route UI, real microphone/speaker, hardware AEC, listening, power, or thermal behavior has not been tested on physical devices
- **THEN** each row remains `DEFERRED_TO_RELEASE_CANDIDATE` and is never reported as PASS
