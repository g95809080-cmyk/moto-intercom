# Sprint 4 Final Verification

Status: **KUM-37 AUTOMATED GATE PASS - CI AND ARCHITECTURE REVIEW PENDING**

Evidence state: 2026-07-19

## Bound revision

- Repository: `g95809080-cmyk/moto-intercom`
- Branch: `feat/kum-37-audio-session-lifecycle`
- Pull request: pending
- Sprint 4 base: `bd35ea69955001dc175f376f58ab4e6b84d9c223`
- Verified KUM-37 source head: `823046f0c83aae0c68bee370d65fae16205a758a`
- Evidence/review head: pending
- GitHub Actions: pending
- Fixed-SHA architecture review: pending
- Linear: KUM-9 In Progress; KUM-37 In Progress; KUM-32 through KUM-36 Todo

This is the single Sprint 4 evidence index. Later Sprint 4 issues append their
bound source, CI, review, emulator, and deferred physical evidence here.

## KUM-37 delivery boundary

KUM-37 changes audio resource lifetime without changing product-state or
connection authority:

- one Service-owned `AudioSessionController` belongs to the online runtime;
- `AudioRouteController`, `JavaAudioDeviceModule`, `PeerConnectionFactory`,
  local `AudioSource`/`AudioTrack`, VOX state, and the RTC executor remain owned
  across transient media/transport recovery;
- each authorized media connection borrows one replaceable
  `RiderMediaSession` containing PeerConnection, sender, SDP/ICE state, remote
  track, and session callbacks;
- a second concurrent media session fails closed;
- released leases are stale before asynchronous physical disposal;
- without a media lease there is no PeerConnection, remote sender/playout, or
  signaling callback binding; and
- full Stop/destroy releases the media session, retained engine, and route.

`SessionOrchestrator` remains the only product-state writer. The existing
Coordinator remains the only attempt/winner authority and still gates every
`StartWebRtc`. KUM-32 target recovery, KUM-33 three-second fallback, KUM-34
`RESETTING`, KUM-35 active disconnect, and KUM-36 final matrix behavior are not
implemented.

## KUM-37 automated evidence

| Check | Bound revision | Result | Evidence |
| --- | --- | --- | --- |
| Targeted JVM | `823046f` | PASS | 5 tests; audio lifecycle plus attempt cleanup; 0 failures |
| Full JVM gate | `823046f` | PASS | 248 tests; 36 suites; 0 failures, errors, or skipped |
| Lint | `823046f` | PASS | 0 Fatal, 0 Error, 34 existing warnings |
| Debug APK | `823046f` | PASS | `assembleDebug` |
| Android test APK | `823046f` | PASS | `assembleDebugAndroidTest` |
| Actual WebRTC instrumentation | `823046f` | PASS | 1 API 36 emulator test; two sequential PeerConnections reused the same ADM/factory/source/track and never overlapped |
| Rasen strict validation | `823046f` | PASS | 1/1 |
| PowerShell compatibility | `823046f` | PASS | all seven emulator scripts parse in Windows PowerShell 5.1 |
| GitHub Actions | pending | PENDING | run pending |

The full clean gate ran `testDebugUnitTest`, `lintDebug`, `assembleDebug`, and
`assembleDebugAndroidTest` in one invocation with the installed Android SDK and
JDK. An earlier compile invocation lacked `ANDROID_HOME`/`ANDROID_SDK_ROOT` and
stopped before dependency resolution; rerunning with the installed SDK passed.
That was environment setup, not a source failure.

## KUM-37 emulator matrix

- Emulator: 36.6.11
- Image: API 36 AOSP ATD x86_64
- AVD: `MotoIntercom_API_36`
- Nodes: `emulator-5554`, `emulator-5556`, `emulator-5558`
- Result: `build/emulator-results/20260719-213820-all` - PASS
- Fresh evidence archive: `build/emulator-evidence/20260719-213915.zip`
- Archive SHA-256: `FFAF9EEB777C7844301E2F68762C97C24D9CDA90312D1BDF63024BAB13718DC2`

The matrix passed app smoke/UI hierarchy, all pairwise shared-network probes,
Android NSD and Socket exchange, deterministic synthetic PCM metrics and
network transfer, the new hot-audio lifecycle instrumentation, bounded network
fault/recovery, process restart, evidence collection, and bounded cluster
shutdown. The connected MI 6 physical device was excluded from every command.

The API 36 AOSP ATD framebuffer returned all-black PNGs. Screenshots are
`UNAVAILABLE_ATD_BLACK_FRAME`, not visual PASS. Instrumentation, UI hierarchy,
network, process, service, audio dump, and log evidence carry the development
gate.

## Architecture review

```text
Base SHA: bd35ea69955001dc175f376f58ab4e6b84d9c223
Head SHA: pending
Result: PENDING
P0: pending
P1: pending
KUM-37 complete: NO
KUM-32 allowed: NO
```

## Physical acceptance queue

The following rows remain mandatory Release Candidate work and are not claimed
as passed by the development evidence:

- Bluetooth SCO and OEM communication-device routing;
- real microphone and speaker continuity across recovery;
- no audible local loopback and no remote audio during the recovery gap;
- hardware echo cancellation and actual listening quality;
- OEM background/lock-screen behavior; and
- long-duration power, thermal, privacy-indicator, and background survival.

Current status for every row: `DEFERRED_TO_RELEASE_CANDIDATE`.

## Gate decision

| Gate | Decision |
| --- | --- |
| KUM-37 implementation | PASS at source `823046f` |
| KUM-37 automated/emulator gate | PASS |
| KUM-37 architecture review | PENDING |
| KUM-37 CI | PENDING |
| KUM-37 may move to Done | NO - PR/CI/review/merge/main CI pending |
| KUM-32 may start | NO |
| Sprint 4 may close | NO - KUM-32 through KUM-36 remain Todo |
| Production deployment | NO - final physical Release Candidate gate and explicit authorization required |

## Residual risk

Automated evidence proves object ownership, sequential PeerConnection reuse,
single-session enforcement, stale callback exclusion, and no remote media path
without a session. ATD and synthetic audio cannot prove real SCO, OEM route
prompts, microphone continuity, hardware AEC, acoustic quality, power, thermal,
or background survival. Those accepted hardware-only checks remain explicit
Release Candidate work.
