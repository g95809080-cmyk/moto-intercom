# MotoIntercom Release Candidate Physical Acceptance Plan

Status: Plan complete; execution `DEFERRED_TO_RELEASE_CANDIDATE`

This document is the required physical evidence queue. No row below is claimed
as passed by JVM, fake, emulator, or CI evidence.

## Entry gate

- All approved features and Sprints are complete.
- All intermediate PRs are merged to `main` with green CI.
- P0=0 and P1=0.
- Automated and emulator matrices are complete.
- A release-candidate APK hash and source SHA are fixed.

## Physical matrix

| Area | Required physical procedure | Development status |
| --- | --- | --- |
| T0/T+5 transport race | With weak/unavailable LAN and healthy Wi-Fi Direct, prove LAN starts at T0, fallback starts no earlier than T+5, only one winner reaches media, and the loser is released | `DEFERRED_TO_RELEASE_CANDIDATE` |
| Sequential fallback without stable overlap | On at least two OEM/Android families, reproduce unavailable or unreliable LAN/P2P overlap; prove the same attempt and target retire LAN before retrying P2P, preserve the immutable T+10 deadline, and create only one media owner | `DEFERRED_TO_RELEASE_CANDIDATE` |
| OEM Wi-Fi Direct | Two phones per supported OEM family; discover, form group, connect, disconnect, and repeat after radio toggle | `DEFERRED_TO_RELEASE_CANDIDATE` |
| RF/range/interference | Validate near, normal riding distance, weak signal, and controlled interference without deadline extension | `DEFERRED_TO_RELEASE_CANDIDATE` |
| OEM background limits | Lock screen, background, screen-off, process pressure, and notification action on Xiaomi and another OEM | `DEFERRED_TO_RELEASE_CANDIDATE` |
| Hot audio recovery lifecycle | Establish audio on two phones, interrupt only the active media/transport path, verify the same SCO/communication route and capture/VOX platform remain active without remote send or local loopback, reconnect with exactly one media stream, then verify full Stop releases the route and microphone | `DEFERRED_TO_RELEASE_CANDIDATE` |
| Recovery target lock | Establish A-B, make B unavailable while C advertises/responds first over LAN and Wi-Fi Direct, and prove A names and retries only B while every C Socket/group/HELLO is rejected and cleaned | `DEFERRED_TO_RELEASE_CANDIDATE` |
| Three-second recovery fallback | Establish A-B over each transport, interrupt B under controlled RF/OEM conditions, prove the last successful transport owns the first 3 seconds, then prove the alternate starts without target/deadline replacement and exactly one media stream wins | `DEFERRED_TO_RELEASE_CANDIDATE` |
| Three-failure wireless reset | Force three complete same-target recovery failures on at least two OEM/Android families; prove visible `RESETTING`, ordered cancel/removeGroup/clear requests/clear services/channel close, LAN/NSD/UDP/Socket retirement, no stale callback takeover, fresh discovery rebuild, and no audio-owner duplication | `DEFERRED_TO_RELEASE_CANDIDATE` |
| Active disconnect stays online | Establish LAN and Wi-Fi Direct sessions in both directions, disconnect locally and from the peer, and prove signaling/WebRTC/current transport close while Service, discovery, foreground notification, presence, and the hot audio platform remain online with no remote media; then issue full Stop and prove complete teardown | `DEFERRED_TO_RELEASE_CANDIDATE` |
| Sprint 4 composite acceptance | With A paired to B and C present, execute target-locked recovery, preferred T+3 fallback, immutable T+10, three final failures and wireless reset, stale callback rejection, intentional active disconnect, full Stop, and audio recovery; prove one B media owner throughout | `DEFERRED_TO_RELEASE_CANDIDATE` |
| Bluetooth SCO | Connect/disconnect headset before and during a session; verify route recovery and one media stream | `DEFERRED_TO_RELEASE_CANDIDATE` |
| Microphone/speaker | Bidirectional spoken phrases, mute/cancel/disconnect, and no audio after stop | `DEFERRED_TO_RELEASE_CANDIDATE` |
| Hardware AEC | Speaker-mode speech with echo observation on both endpoints | `DEFERRED_TO_RELEASE_CANDIDATE` |
| Human listening | Bidirectional intelligibility, clipping, silence, and recovery confirmation | `DEFERRED_TO_RELEASE_CANDIDATE` |
| Power/thermal/background survival | Long-running session with battery, thermal, process, and reconnect evidence | `DEFERRED_TO_RELEASE_CANDIDATE` |

## Core scenario sequence

1. Bind source SHA, APK SHA-256, device serials, OS/OEM, and stable identities.
2. Discover the intended target while a non-target node is present.
3. Establish LAN and Wi-Fi Direct scenarios in both requester directions.
4. Confirm exactly one Signaling/WebRTC session and target/attempt identity.
5. Speak and hear a short phrase in both directions.
6. Exercise cancellation, simultaneous request, stale callback, network loss,
   recovery, process restart, background/lock screen, and notification paths.
7. Actively disconnect from each side and verify Socket, group, media, service,
   task, and database cleanup.
8. Capture logs, screenshots, service/audio dumps, database integrity, and human
   listening confirmation.

## Release decision

KUM-36 automated evidence binds the development matrix to source `c5e9808` and
does not convert any physical row to PASS. Emulator Wi-Fi Direct/RF/OEM gaps,
ATD black framebuffers, real audio hardware, and long-duration behavior remain
explicit Release Candidate work.

Any failed mandatory row blocks production release. Unavailable hardware remains
`NOT_RUN`, not accepted or passed, until the user explicitly approves a revised
Release Gate. Production release always requires a separate explicit user
authorization.
