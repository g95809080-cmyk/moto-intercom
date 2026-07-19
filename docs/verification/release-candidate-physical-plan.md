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
| OEM Wi-Fi Direct | Two phones per supported OEM family; discover, form group, connect, disconnect, and repeat after radio toggle | `DEFERRED_TO_RELEASE_CANDIDATE` |
| RF/range/interference | Validate near, normal riding distance, weak signal, and controlled interference without deadline extension | `DEFERRED_TO_RELEASE_CANDIDATE` |
| OEM background limits | Lock screen, background, screen-off, process pressure, and notification action on Xiaomi and another OEM | `DEFERRED_TO_RELEASE_CANDIDATE` |
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

Any failed mandatory row blocks production release. Unavailable hardware remains
`NOT_RUN`, not accepted or passed, until the user explicitly approves a revised
Release Gate. Production release always requires a separate explicit user
authorization.
