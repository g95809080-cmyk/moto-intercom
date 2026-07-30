# MotoIntercom Release Candidate Physical Acceptance Plan

Status: **GITHUB PERSONAL-USE RELEASE AUTHORIZED**

Release boundary updated: 2026-07-30

The user accepted the current feature set and the explicitly listed remaining
hardware risks for a GitHub-hosted APK. This is not an app-store production
release.

This document records the final automated, two-device, and accepted-residual
evidence for the GitHub personal-use release.

## Entry gate

- All approved features and Sprints are complete.
- All intermediate PRs are merged to `main` with green CI.
- P0=0 and P1=0.
- Automated and emulator matrices are complete.
- A release-candidate APK hash and source SHA are fixed.

Entry gate result: PASS.

- Application source anchor: `8d164a458316afab672e70ec7c179838d0923b5a`
- Exact-main CI: `30509400377` - success
- Debug APK SHA-256:
  `D26BFFEE8157F719B3028E6B71E4F593F8B0037D0177A7CA7341061FB6B84639`
- Debug certificate SHA-256:
  `7F20F38DC1D7372CDE34CAC6E0E17D80EC995AC298C298FD0D24605E1A8070F3`
- Automated result: 323/323 JVM tests; 49 suites; 0 failures/errors/skipped;
  Lint 0 fatal/0 errors/29 warnings; `assembleDebug` and
  `assembleDebugAndroidTest` PASS; architecture reviews APPROVED with
  P0=0/P1=0.

The GitHub APK intentionally keeps the Android debug signing identity used by
the two physically accepted installations. This preserves in-place update
compatibility. It is not an app-store production-signing identity.

## Physical matrix

| Area | Required physical procedure | Development status |
| --- | --- | --- |
| T0/T+5 transport race | With weak/unavailable LAN and healthy Wi-Fi Direct, prove LAN starts at T0, fallback starts no earlier than T+5, only one winner reaches media, and the loser is released | `AUTOMATED_PASS / PHYSICAL_TIMING_ACCEPTED_RESIDUAL` |
| Sequential fallback without stable overlap | On at least two OEM/Android families, reproduce unavailable or unreliable LAN/P2P overlap; prove the same attempt and target retire LAN before retrying P2P, preserve the immutable T+10 deadline, and create only one media owner | `AUTOMATED_PASS / PHYSICAL_OVERLAP_ACCEPTED_RESIDUAL` |
| OEM Wi-Fi Direct | Two phones per supported OEM family; discover, form group, connect, disconnect, and repeat after radio toggle | `PASS` |
| RF/range/interference | Validate near, normal riding distance, weak signal, and controlled interference without deadline extension | `ACCEPTED_RESIDUAL_RISK` |
| OEM background limits | Lock screen, background, screen-off, process pressure, and notification action on Xiaomi and another OEM | `PASS_LOCK_BACKGROUND / PROCESS_PRESSURE_ACCEPTED_RESIDUAL` |
| Hot audio recovery lifecycle | Establish audio on two phones, interrupt only the active media/transport path, verify the same SCO/communication route and capture/VOX platform remain active without remote send or local loopback, reconnect with exactly one media stream, then verify full Stop releases the route and microphone | `PASS` |
| Recovery target lock | Establish A-B, make B unavailable while C advertises/responds first over LAN and Wi-Fi Direct, and prove A names and retries only B while every C Socket/group/HELLO is rejected and cleaned | `AUTOMATED_PASS / THIRD_PHONE_ACCEPTED_RESIDUAL` |
| Three-second recovery fallback | Establish A-B over each transport, interrupt B under controlled RF/OEM conditions, prove the last successful transport owns the first 3 seconds, then prove the alternate starts without target/deadline replacement and exactly one media stream wins | `AUTOMATED_PASS / PHYSICAL_TIMING_ACCEPTED_RESIDUAL` |
| Three-failure wireless reset | Force three complete same-target recovery failures on at least two OEM/Android families; prove visible `RESETTING`, ordered cancel/removeGroup/clear requests/clear services/channel close, LAN/NSD/UDP/Socket retirement, no stale callback takeover, fresh discovery rebuild, and no audio-owner duplication | `AUTOMATED_PASS / PHYSICAL_SEQUENCE_ACCEPTED_RESIDUAL` |
| Active disconnect stays online | Establish LAN and Wi-Fi Direct sessions in both directions, disconnect locally and from the peer, and prove signaling/WebRTC/current transport close while Service, discovery, foreground notification, presence, and the hot audio platform remain online with no remote media; then issue full Stop and prove complete teardown | `PASS` |
| Sprint 4 composite acceptance | With A paired to B and C present, execute target-locked recovery, preferred T+3 fallback, immutable T+10, three final failures and wireless reset, stale callback rejection, intentional active disconnect, full Stop, and audio recovery; prove one B media owner throughout | `AUTOMATED_PASS / THIRD_PHONE_ACCEPTED_RESIDUAL` |
| Bluetooth SCO | Connect/disconnect headset before and during a session; verify route recovery and one media stream | `PASS` |
| Microphone/speaker | Bidirectional spoken phrases, mute/cancel/disconnect, and no audio after stop | `PASS` |
| Hardware AEC | Speaker-mode speech with echo observation on both endpoints | `BASIC_SPEAKER_PASS / FORMAL_AEC_ACCEPTED_RESIDUAL` |
| Human listening | Bidirectional intelligibility, clipping, silence, and recovery confirmation | `PASS` |
| Power/thermal/background survival | Long-running session with battery, thermal, process, and reconnect evidence | `30_MINUTE_PASS / FORMAL_METRICS_ACCEPTED_RESIDUAL` |

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

The final two-device sessions covered LAN and Wi-Fi Direct in both requester
directions, active disconnect and rediscovery, lock-screen/background audio,
Bluetooth headset connect/disconnect/reconnect without duplicate playout,
process restart, full Stop without residual audio, a 30-minute session, and
automatic same-target Wi-Fi Direct outage recovery.

The user explicitly accepted the remaining physical-only risks above for this
GitHub personal-use APK. App-store publication and a production signing key
remain out of scope.
