# Sprint 4 Final Verification

Status: **KUM-32 AUTOMATED/CI GATE PASS - ARCHITECTURE REVIEW PENDING**

Evidence state: 2026-07-19

## Bound revision

- Repository: `g95809080-cmyk/moto-intercom`
- Branch: `main`; KUM-32 branch `feat/kum-32-recovery-target-lock`
- Pull request: [#9](https://github.com/g95809080-cmyk/moto-intercom/pull/9) merged
- Sprint 4 base: `bd35ea69955001dc175f376f58ab4e6b84d9c223`
- Verified KUM-37 source head: `1977e7eec466aeb439f4bc3714ba855d6a11d2d9`
- Reviewed KUM-37 head: `b3811243a93fad258702301de22c51d5229fec37`
- Final KUM-37 PR head: `38f1c6ffda738415ee437550c03a01553e621b8c`
- KUM-37 merge commit: `76fa55428306fa9d5d859cb936fede989e92546b`
- Initial GitHub Actions: run `29689536790` at `b31175e` - success
- Reviewed-head GitHub Actions: run `29690610503` - success
- Final PR-head GitHub Actions: run `29691274257` - success
- Post-merge main GitHub Actions: run `29691482967` - success
- KUM-32 base: `76fa55428306fa9d5d859cb936fede989e92546b`
- KUM-32 implementation head: `dba5155e5dac377eb3b8b2486ea4ac608e65e989`
- KUM-32 pull request: [#10](https://github.com/g95809080-cmyk/moto-intercom/pull/10) - Draft
- KUM-32 initial GitHub Actions: run `29693572908` - success
- Initial fixed-SHA review: REQUEST CHANGES, P0=0, P1=3
- Final fixed-SHA review: APPROVED, P0=0, P1=0
- Linear: KUM-9 In Progress; KUM-37 Done; KUM-32 In Review; KUM-33 through KUM-36 Todo

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
| Targeted JVM | `1977e7e` | PASS | 8 tests; lifecycle, failure cleanup, runtime callback gate, and attempt cleanup; 0 failures |
| Full JVM gate | `1977e7e` | PASS | 251 tests; 38 suites; 0 failures, errors, or skipped |
| Lint | `1977e7e` | PASS | 0 Fatal, 0 Error, 34 existing warnings |
| Debug APK | `1977e7e` | PASS | `assembleDebug` |
| Android test APK | `1977e7e` | PASS | `assembleDebugAndroidTest` |
| Actual WebRTC instrumentation | `1977e7e` | PASS | 1 API 36 emulator test; immediate sequential replacement reused the same ADM/factory/source/track, changed PeerConnection, and enforced one active session |
| Rasen strict validation | `1977e7e` | PASS | 1/1 |
| PowerShell compatibility | `1977e7e` | PASS | all seven emulator scripts parse in Windows PowerShell 5.1 |
| Reviewed-head GitHub Actions | `b381124` | PASS | run `29690610503` |
| Final evidence-sync GitHub Actions | `38f1c6f` | PASS | run `29691274257` |
| Post-merge main GitHub Actions | `76fa554` | PASS | run `29691482967` |

The full clean gate ran `testDebugUnitTest`, `lintDebug`, `assembleDebug`, and
`assembleDebugAndroidTest` in one invocation with the installed Android SDK and
JDK. An earlier compile invocation lacked `ANDROID_HOME`/`ANDROID_SDK_ROOT` and
stopped before dependency resolution; rerunning with the installed SDK passed.
That was environment setup, not a source failure. The first remediation clean
was then blocked by a Windows lock on Gradle's compiled classes jar; stopping
the daemon and rerunning the identical clean gate with `--no-daemon` passed.

## KUM-37 emulator matrix

- Emulator: 36.6.11
- Image: API 36 AOSP ATD x86_64
- AVD: `MotoIntercom_API_36`
- Nodes: `emulator-5554`, `emulator-5556`, `emulator-5558`
- Result: `build/emulator-results/20260719-221645-all` - PASS
- Fresh evidence archive: `build/emulator-evidence/20260719-221726.zip`
- Archive SHA-256: `9447F81DEFF92B92A7DD3E63DD5E2DC8C8CFBD15C6C7DA193358D87A73206120`

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

The initial fixed-SHA review at `b31175e` returned P0=0 and P1=3. Remediation
now ensures full Stop runs platform cleanup even when media cleanup throws,
exercises immediate old-close/new-open ordering on the actual WebRTC engine,
and deterministically proves runtime-level audio callbacks survive only
transport-generation rollover, not runtime rollover. The unrelated tracked
global Rasen pipeline override was removed from the final tree.

```text
Base SHA: bd35ea69955001dc175f376f58ab4e6b84d9c223
Head SHA: b3811243a93fad258702301de22c51d5229fec37
Result: APPROVED
P0: 0
P1: 0
KUM-37 source complete: YES
KUM-32 allowed before merge/main CI: NO
```

The reviewer confirmed that all three prior P1s are closed, there is still one
product-state writer and one Coordinator/winner authority, no pre-authorization
remote media or second session exists, recovery retains only the approved hot
resources, full Stop cleans every resource, and KUM-32 through KUM-36 remain
absent. The remaining evidence gap is delivery metadata only.

## KUM-32 delivery boundary

KUM-32 keeps recovery fixed to the rider retained by
`IntercomState.Recovering` without changing recovery timing or ownership:

- the Coordinator remains the only writer of recovery target and product state;
- each recovery adapter receives a separate immutable ingress-validation lease
  before discovery/group/HELLO work can transfer a resource;
- active LAN/P2P work still starts only through `OpenTargetedTransport`, so the
  existing fallback milestone is not advanced;
- Service rejects a verified non-target Socket before registration and asks the
  Wi-Fi Direct adapter to remove a rejected non-target group;
- Presence selection/order cannot replace the current recovery attempt; and
- UI and foreground notification derive `正在恢复与 {车友} 的连接` from the retained
  verified peer.

KUM-33 three-second recovery/fallback, KUM-34 `RESETTING`, KUM-35 active
disconnect, and KUM-36 final acceptance remain unimplemented.

## KUM-32 automated evidence

| Check | Bound revision | Result | Evidence |
| --- | --- | --- | --- |
| Targeted JVM | `dba5155` | PASS | 43 tests across Coordinator, Socket, adapter lease, P2P registry, and presentation suites |
| Full JVM gate | `dba5155` | PASS | 256 tests; 39 suites; 0 failures, errors, or skipped |
| Lint | `dba5155` | PASS | 0 Fatal, 0 Error, 34 existing warnings |
| Debug APK | `dba5155` | PASS | `assembleDebug`; SHA-256 `F5AF04253540E3457C737C052584932EE712F8997F66924097278BC4E8226AAD` |
| Android test APK | `dba5155` | PASS | `assembleDebugAndroidTest`; SHA-256 `AF51A64F28C2A689E4D955EAFB521E994DF2C9268AB82504FC8F8044D91BD404` |
| Single-emulator instrumentation | `dba5155` | PASS | synthetic PCM 3/3 and actual hot WebRTC lifecycle 1/1 |
| Rasen strict validation | `dba5155` | PASS | 1/1; 4/4 artifacts complete |
| Initial GitHub Actions | `dba5155` | PASS | run `29693572908` |

The final clean gate initially encountered a Windows lock on Gradle's generated
`classes.jar`. Stopping the Gradle daemon and rerunning the identical clean gate
passed; this was a local process lock, not a source failure.

## KUM-32 emulator matrix

- Emulator: 36.6.11; API 36 AOSP ATD x86_64
- Nodes: `emulator-5554`, `emulator-5556`, `emulator-5558`
- Result: `build/emulator-results/20260719-234430-all` - PASS
- Evidence archive: `build/emulator-evidence/20260719-234515.zip`
- Archive SHA-256: `D6B881EDB496074E6FA46DDE9C2F823C40FA4A2D15012624F16970D7993F2D6C`

The matrix passed explicit-emulator app launch/UI hierarchy, pairwise shared
network probes, NSD/Socket exchange, synthetic PCM metrics/transfer, bounded
network fault and recovery, process restart, evidence collection, and shutdown.
The connected MI 6 physical device was excluded. ATD framebuffers remained
black and are `UNAVAILABLE_ATD_BLACK_FRAME`, not visual PASS.

## KUM-32 architecture review

Fixed-SHA read-only review is pending. No KUM-32 closure, Ready transition, or
merge is allowed until APPROVED with P0=0 and P1=0.

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
| KUM-37 implementation | PASS at source `1977e7e` |
| KUM-37 automated/emulator gate | PASS |
| KUM-37 architecture review | APPROVED - P0=0, P1=0 at `b381124` |
| KUM-37 reviewed-head CI | PASS - run `29690610503` |
| KUM-37 may move to Done | YES - merged as `76fa554`, main CI `29691482967` passed, Linear Done |
| KUM-32 may start | YES - active on `feat/kum-32-recovery-target-lock` from `76fa554` |
| KUM-32 implementation/automated gate | PASS at `dba5155`; CI `29693572908` passed |
| KUM-32 architecture review | PENDING |
| KUM-32 may move to Done | NO - fixed-SHA architecture review and final delivery gates pending |
| KUM-33 may start | NO |
| Sprint 4 may close | NO - KUM-32 through KUM-36 remain Todo |
| Production deployment | NO - final physical Release Candidate gate and explicit authorization required |

## Residual risk

Automated evidence proves object ownership, sequential PeerConnection reuse,
single-session enforcement, stale callback exclusion, and no remote media path
without a session. ATD and synthetic audio cannot prove real SCO, OEM route
prompts, microphone continuity, hardware AEC, acoustic quality, power, thermal,
or background survival. Those accepted hardware-only checks remain explicit
Release Candidate work.
