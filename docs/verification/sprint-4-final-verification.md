# Sprint 4 Final Verification

Status: **KUM-35 ARCHITECTURE APPROVED - FINAL DELIVERY HEAD CI PENDING**

Evidence state: 2026-07-20

## Bound revision

- Repository: `g95809080-cmyk/moto-intercom`
- Branch: active KUM-35 branch `feat/kum-35-active-disconnect-stay-online`
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
- KUM-32 initial evidence head: `eae95751f807fde65e33b1c4297dda63bd91c21f`
- KUM-32 review-remediation source head: `b7006befa43ff627f301224f1ebd02d8f81487af`
- KUM-32 second review head: `9bb61ccb15f87479df5170ac80eea42a305993cd`
- KUM-32 HELLO-cleanup remediation source head: `ba649e330175595806d9ee285b6baa302611f66e`
- KUM-32 pull request: [#10](https://github.com/g95809080-cmyk/moto-intercom/pull/10) - merged
- KUM-32 initial GitHub Actions: run `29693572908` - success
- KUM-32 initial evidence GitHub Actions: run `29693863050` - success
- KUM-32 review-remediation GitHub Actions: run `29695549022` - success
- KUM-32 HELLO-cleanup remediation GitHub Actions: run `29697109471` - success
- KUM-32 final PR-head GitHub Actions: run `29697904226` - success
- KUM-32 merge commit: `5c49a53db45ecaa5b9f449af5f04d28250f3f772`
- KUM-32 exact-main GitHub Actions: run `29698415621` - success via
  `workflow_dispatch` after the expected push event did not materialize
- KUM-37 initial fixed-SHA review: REQUEST CHANGES, P0=0, P1=3
- KUM-37 final fixed-SHA review: APPROVED, P0=0, P1=0
- KUM-32 initial fixed-SHA review: REQUEST CHANGES, P0=0, P1=1
- KUM-32 second fixed-SHA review: REQUEST CHANGES, P0=0, P1=1
- KUM-32 third fixed-SHA review: APPROVED, P0=0, P1=0 at `5f1d1d6`
- KUM-33 base: `5c49a53db45ecaa5b9f449af5f04d28250f3f772`
- KUM-33 implementation source: `616c22fcc270e98c276a0fb8e3a3943101334492`
- KUM-33 automated-evidence head: `834dc7e74897784c455b9c0636487b3cbc3590bf`
- KUM-33 initial review/delivery head: `5e1da34882d934e9812aa1100c497c8511388aab`
- KUM-33 initial PR-head GitHub Actions: run `29700055312` - success
- KUM-33 per-transport readiness remediation source:
  `9d773ad77e7927ec86482de614d99e79ac59aaa0`
- KUM-33 second review/evidence head:
  `b9b065055ef596a41cb32bfd17c4a70f47eadb60`
- KUM-33 second-review GitHub Actions: run `29701853485` - success
- KUM-33 production startup-seam remediation source:
  `b0cb6c7eadeaf30eebb400e847a14e1478821c57`
- KUM-33 approved review/evidence head:
  `29fd4efd0d08955511ff65898abed3a6d51d3c2e`
- KUM-33 approved-head GitHub Actions: run `29702981381` - success
- KUM-33 final PR head: `681c775bced59d87a9c18cc84d525411f558c7e8`
- KUM-33 final PR-head GitHub Actions: run `29703230627` - success
- KUM-33 pull request: [#11](https://github.com/g95809080-cmyk/moto-intercom/pull/11) - merged
- KUM-33 merge commit: `34f715d77c80e492ce90ecdd7efc6d1603a74d8e`
- KUM-33 exact-main GitHub Actions: run `29703574642` - success
- KUM-33 Rasen change: `kum-33-three-second-recovery-fallback`
- KUM-34 base: `34f715d77c80e492ce90ecdd7efc6d1603a74d8e`
- KUM-34 implementation source: `5b184a21efafccc72ccf1d71766c5b7e038c5c6b`
- KUM-34 automated-evidence head: `1d4296b22de2aba6cf3503fd4a389f96adfd849a`
- KUM-34 pull request: [#12](https://github.com/g95809080-cmyk/moto-intercom/pull/12) - merged
- KUM-34 initial review/delivery head: `61f4add98366a01b0a629374b47c7eba053c1186`
- KUM-34 initial review-head GitHub Actions: run `29705702507` - success
- KUM-34 initial fixed-SHA review: REQUEST CHANGES, P0=0, P1=4
- KUM-34 review-remediation source: `8157a895a929ec805d47e08a2e6e120fbf952f1c`
- KUM-34 Rasen change: `kum-34-resetting-wireless-reset`
- KUM-34 second review-remediation source: `b78ee8bd20762bdb7f4f84e0526056010624f01a`
- KUM-34 final PR head: `4f737f5516bfe5da65406d7ce2442997907e36f6`
- KUM-34 merge commit: `8dcb3f640e3c5b622da98bc1af68720502427ac8`
- KUM-34 exact-main GitHub Actions: run `29711575580` - success
- KUM-35 base: `8dcb3f640e3c5b622da98bc1af68720502427ac8`
- KUM-35 implementation source: `0dcf63da00adee38911b9bd944d57fc74bdd05cf`
- KUM-35 initial Draft delivery head: `ebaf9a0bda1bc291d0e7f8bcf1abd775a10fd3cc`
- KUM-35 initial exact-Head GitHub Actions: run `29713126973` - success
- KUM-35 initial fixed-SHA review: REQUEST CHANGES, P0=0, P1=1
- KUM-35 review-remediation source: `bb9991fd0bc9ec570718ee17c7e623cea47c2dc2`
- KUM-35 approved review head: `8c3face367ba11524f04a7b49749e110cbba64c9`
- KUM-35 architecture review: APPROVED, P0=0, P1=0
- KUM-35 pull request: [#13](https://github.com/g95809080-cmyk/moto-intercom/pull/13) - open Draft
- KUM-35 Rasen change: `kum-35-active-disconnect-stay-online`
- Linear: KUM-9 In Progress; KUM-37/KUM-32/KUM-33/KUM-34 Done; KUM-35 In Review; KUM-36 Todo

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
| Targeted JVM | `ba649e3` | PASS | 27 tests across the real Wi-Fi Direct HELLO cleanup, logical-node admission seam, and SignalingSession suites |
| Full JVM gate | `ba649e3` | PASS | 258 tests; 39 suites; 0 failures, errors, or skipped |
| Lint | `ba649e3` | PASS | 0 Fatal, 0 Error, 34 warnings |
| Debug APK | `ba649e3` | PASS | `assembleDebug`; SHA-256 `7EFD768FAEC96CF0CF962AF93FB0882036E85C39EF7217FDAE5B9DE3B070A7C7` |
| Android test APK | `ba649e3` | PASS | `assembleDebugAndroidTest`; SHA-256 `AF51A64F28C2A689E4D955EAFB521E994DF2C9268AB82504FC8F8044D91BD404` |
| Single-emulator instrumentation | `ba649e3` | PASS | synthetic PCM 3/3 and actual hot WebRTC lifecycle 1/1 inside the explicit emulator matrix |
| Rasen strict validation | `ba649e3` | PASS | 1/1; 4/4 artifacts complete |
| Initial GitHub Actions | `dba5155` | PASS | run `29693572908` |
| Review-remediation GitHub Actions | `b7006be` | PASS | run `29695549022` |
| HELLO-cleanup remediation GitHub Actions | `ba649e3` | PASS | run `29697109471` |
| Final PR-head GitHub Actions | `467a242` | PASS | run `29697904226` |
| Exact-main GitHub Actions | `5c49a53` | PASS | run `29698415621`; manual workflow dispatch recorded explicitly |

The final clean gate initially encountered a Windows lock on Gradle's generated
`classes.jar`. Stopping the Gradle daemon and rerunning the identical clean gate
passed; this was a local process lock, not a source failure.

## KUM-32 emulator matrix

- Emulator: 36.6.11; API 36 AOSP ATD x86_64
- Nodes: `emulator-5554`, `emulator-5556`, `emulator-5558`
- Result: `build/emulator-results/20260720-013122-all` - PASS
- Evidence archive: `build/emulator-evidence/20260720-013205.zip`
- Archive SHA-256: `DFE7C7EC0A18B8271E7BDA156F07DC5D85E43DE89187C2EBC26D0605F1500E7C`

The matrix passed explicit-emulator app launch/UI hierarchy, pairwise shared
network probes, NSD/Socket exchange, synthetic PCM metrics/transfer, bounded
network fault and recovery, process restart, evidence collection, and shutdown.
The connected MI 6 physical device was excluded. ATD framebuffers remained
black and are `UNAVAILABLE_ATD_BLACK_FRAME`, not visual PASS.

## KUM-32 architecture review

The initial fixed-SHA read-only review at `eae9575` returned P0=0 and P1=1.
The existing A/B/C tests exercised only the pure admission predicate and manual
session closure, so they did not prove the Service/Coordinator seam, rejected
non-target P2P cleanup, the single B media owner, or adapter reuse after a
media-only loss.

The remediation at `b7006be` adds one production admission seam used by
`IntercomService`, an explicit non-target Wi-Fi Direct cleanup outcome, and a
deterministic real-Socket A/B/C regression. The scenario proves C closes and
cleanup runs, B is admitted afterward, the Coordinator emits exactly one
`StartWebRtc`, only B passes the media gate, and media-only recovery reuses the
existing adapters while binding every planned ingress before opening only the
selected transport.

The second fixed-SHA review at `9bb61cc` verified that remediation but returned
P0=0 and P1=1 for a lower Wi-Fi Direct trust-boundary gap: after an accepted
discovery claim forms a group, actual Socket HELLO identity could still be C or
B's stale runtime. Establishment closed the Socket but bypassed the existing
current-context group-removal path.

The remediation at `ba649e3` routes HELLO establishment failures through the
same generation/attempt-scoped Socket failure handler. Real-Socket tests cover
both C and stale-B-runtime HELLO frames and prove failure reaches the cleanup
callback; the production route retains the existing current-context checks that
prevent stale callbacks from removing a newer group.

The third fixed-SHA read-only review at `5f1d1d6` is APPROVED with P0=0 and
P1=0. It verified both remediation paths, the fixed evidence, clean Git state,
and that KUM-33 behavior was absent at that reviewed head. KUM-32 subsequently
merged as `5c49a53`; exact-main CI `29698415621` passed and Linear is Done.

## KUM-33 delivery boundary

KUM-33 changes only recovery ordering and timing inside the existing ownership
model:

- a fresh recovery attempt retains the same TargetLock and transport set but
  makes `Connected.transport` the preferred path;
- the existing Coordinator schedules recovery fallback at monotonic T+3 while
  normal attempts retain T+5 and every attempt retains immutable T+10;
- media-only recovery opens the preferred transport immediately on existing
  adapters;
- signaling-loss recovery schedules T+3 from immutable attempt creation,
  completes mandatory cleanup, rebuilds only planned adapters, and reports
  exact-attempt readiness independently for LAN and Wi-Fi Direct;
- LAN becomes eligible after its synchronous adapter start, while Wi-Fi Direct
  becomes eligible only after old-group cleanup and DNS-SD setup complete;
- if T+3 arrives before the alternate adapter is ready, the Coordinator records
  fallback due and opens it when that adapter later reports ready, without
  resetting either clock; and
- KUM-32 target admission, KUM-31 overlap fallback, KUM-37 hot audio, and the
  single `StartWebRtc` owner remain unchanged.

KUM-34 repeated-failure `RESETTING` is implemented separately at `5b184a2`.
KUM-35 active disconnect, KUM-36 final acceptance, protocol/database/pairing
changes, signing, deployment, and release remain unimplemented.

## KUM-33 automated evidence

| Check | Bound revision | Result | Evidence |
| --- | --- | --- | --- |
| P1 targeted JVM | `b0cb6c7` | PASS | 31 tests across Coordinator ownership, production Service startup routing, logical A/B/C acceptance, exact T+3 races, stale events, and asynchronous adapter startup; 0 failures/errors/skipped |
| Full JVM gate | `b0cb6c7` | PASS | 266 tests; 40 suites; 0 failures, errors, or skipped |
| Lint | `b0cb6c7` | PASS | 0 Fatal, 0 Error, 34 warnings |
| Debug APK | `b0cb6c7` | PASS | `assembleDebug`; SHA-256 `9A1DF7171B4BB017CA50015C9CB24A66A82E8B2A07018B50E2A006649AF4AF14` |
| Android test APK | `b0cb6c7` | PASS | `assembleDebugAndroidTest`; SHA-256 `5A6A9F9A01FAB5AAC98DA7034CAD8779D582F7C870E9492DF817B170F50FFE70` |
| Recovery timing instrumentation | `b0cb6c7` | PASS | 2/2 on each of three API 36 emulators inside the full matrix; 6/6 total |
| Rasen strict validation | `b0cb6c7` | PASS | 1/1; 4/4 artifacts complete |
| Production startup-order regression | `b0cb6c7` | PASS | The Service-used `RecoveryTransportStartup` seam proves adapter fields install before start; `WifiDirectStartupReadiness` stays inert before exact post-DNS-SD readiness, LAN remains independent, and T+3 cannot physically open an unready fallback |

The first emulator run correctly failed because the new test fixture used a
non-canonical runtime ID at the `WireRequestKey` trust boundary. Replacing the
fixture values with canonical UUIDs made the unchanged production scenario pass
on all three nodes. This was a test-data defect, not a production fallback.

The first local P1-remediation Gradle invocation lacked `ANDROID_HOME` and
`ANDROID_SDK_ROOT` in the temporary checkout and stopped before compilation.
Rerunning with the installed SDK passed. A submission self-review then found
that LAN readiness was dispatched from the right-hand side of the field
assignment; the adapter is now stored before synchronous readiness can trigger
`OpenTargetedTransport`. The complete clean gate and emulator matrix were rerun
after that correction.

## KUM-33 emulator matrix

- Emulator: 36.6.11; API 36 AOSP ATD x86_64
- Nodes: `emulator-5554`, `emulator-5556`, `emulator-5558`
- Full matrix: `build/emulator-results/20260720-043350-all` - PASS
- Evidence archive: `build/emulator-evidence/20260720-043437.zip`
- Archive SHA-256: `B0A1D0278F767891BC068BD3725C9B3957B232D37E25B088CB830BC5663D171F`

Every node logged the same immutable attempt/target/deadline evidence:

- at 2999 ms, preferred Wi-Fi Direct became the final transport and the late
  fallback was inert;
- at 3000 ms, alternate LAN became the final transport; and
- the recovery total deadline remained 10000 ms.

The full matrix also passed app smoke/UI hierarchy, pairwise shared networking,
NSD/Socket exchange, deterministic synthetic PCM metrics and transfer, hot
audio lifecycle, network fault/recovery, process restart, evidence collection,
and bounded shutdown. The connected MI 6 was excluded from every command.

ATD screenshots remained all black on visual inspection and are
`UNAVAILABLE_ATD_BLACK_FRAME`, not visual PASS.

## KUM-33 architecture review

The initial fixed-SHA read-only review at `5e1da34` returned REQUEST CHANGES,
P0=0, P1=2. It found that Service sent aggregate recovery readiness immediately
after `WifiDirectTunnel.start()`, before asynchronous startup group cleanup and
DNS-SD setup completed. That premature open could bind `targetAttempt`, make the
startup callback's captured null context stale, and silently prevent Wi-Fi
Direct discovery. Existing instrumentation exercised the Coordinator directly
and did not cover this Service-to-adapter ordering.

The first remediation at `9d773ad` replaces aggregate readiness with exact
per-transport events. T+3 is scheduled at attempt creation; the race tracks
adapter readiness and whether fallback is due. LAN reports only after its
synchronous start succeeds. Wi-Fi Direct reports only after
`serviceDiscoveryReady = true`, with the adapter stored before any synchronous
open effect can run. Focused production-path tests cover both preferred orders,
milestone-before-readiness, readiness-at-T+3, duplicates, stale attempts, and
the asynchronous cleanup boundary.

The second fixed-SHA review at `b9b0650` verified the runtime remediation but
returned REQUEST CHANGES, P0=0, P1=1 because the test still manually connected
the readiness router to the Coordinator instead of exercising the actual
Service startup assembly and post-DNS-SD callback gate.

The second remediation at `b0cb6c7` makes `RecoveryTransportStartup` the
production Service seam for create -> install field -> start ordering and makes
`WifiDirectStartupReadiness` the one-shot post-DNS-SD gate used by
`WifiDirectTunnel`. Deterministic fakes now exercise those exact production
seams through the existing Coordinator.

The third fixed-SHA read-only review at `29fd4ef` is APPROVED with P0=0 and
P1=0. It verified adapter installation before start, the sole post-DNS-SD
Wi-Fi Direct readiness invocation, LAN independence, T+3 readiness gating,
one-shot callback behavior, the unchanged single Coordinator/product-state
ownership, and absence of KUM-34+ scope. Its only non-blocking note was stale
delivery wording, corrected by this documentation-only synchronization.

## KUM-34 delivery boundary

KUM-34 connects the existing repeated-failure/reset skeleton without adding a
second product-state or attempt owner:

- `IntercomState.Recovering` carries an immutable target-scoped final-failure
  count below the reset threshold;
- only complete recovery-attempt terminal outcomes count; transport-local,
  duplicate, stale, wrong-target, canceled, and glare events do not;
- the first and second final failures create fresh recovery attempt IDs and
  immutable T+10 deadlines while preserving the complete KUM-32 TargetLock and
  KUM-33 transport order;
- the existing 1.5-second reconnect backoff and cleanup consume the fresh
  attempt budget rather than rebasing T+3 or T+10;
- the third final failure clears attempt ownership and enters visible
  `RESETTING` with the exhausted attempt ID as exact reset identity;
- Service executes one reset effect through its existing resource owner,
  invalidates old callback generations, closes signaling/WebRTC attempt
  resources and LAN/NSD/UDP/Socket work, then rebuilds discovery;
- the production Wi-Fi Direct close seam orders `cancelConnect`, `removeGroup`,
  `clearServiceRequests`, `clearLocalServices`, and channel `close`, advancing
  once across thrown or duplicate fake callbacks;
- only matching runtime plus exhausted-attempt completion moves
  `RESETTING -> DISCOVERING`; full Stop supersedes a late completion; and
- UI and the foreground notification both expose the product `RESETTING` text.

The KUM-37 runtime audio owner remains hot. KUM-35 active disconnect and KUM-36
final acceptance remain unimplemented.

## KUM-34 automated evidence

| Check | Bound revision | Result | Evidence |
| --- | --- | --- | --- |
| Focused JVM | `5b184a2` | PASS | 14 tests across Coordinator retry/reset ownership, active-channel terminal routing, exact completion, notification visibility, logical-node regression, and production Wi-Fi Direct close sequencing; 0 failures/errors/skipped |
| Full JVM gate | `5b184a2` | PASS | 279 tests; 42 suites; 0 failures, errors, or skipped |
| Lint | `5b184a2` | PASS | 0 Fatal, 0 Error, 34 warnings |
| Debug APK | `5b184a2` | PASS | `assembleDebug`; SHA-256 `055D125FF22BA735CD10D6A086470D588BBDBEE7A2257512A55BD87B3F77B0AA` |
| Android test APK | `5b184a2` | PASS | `assembleDebugAndroidTest`; SHA-256 `90440B77BB52F8F747EA446DC8277594616F981E529B697F63361C4F00E4C9EF` |
| Recovery reset instrumentation | `5b184a2` | PASS | 2/2 on each of three API 36 emulators; 6/6 in the focused run and 6/6 again inside the full matrix |
| Rasen strict validation | `5b184a2` | PASS | 1/1; 4/4 artifacts complete; open findings 0 |
| PowerShell compatibility | `5b184a2` | PASS | all seven emulator scripts parse in Windows PowerShell 5.1 |

The first focused Gradle invocation in the new temporary branch lacked
`ANDROID_HOME`/`ANDROID_SDK_ROOT` and stopped before source compilation.
Rerunning with the installed SDK passed; this was environment setup, not a
source or test failure. One new active-channel fixture initially used a
non-canonical attempt ID at the existing `WireRequestKey` trust boundary; the
fixture was corrected to canonical UUIDs before the focused and full gates.

## KUM-34 emulator matrix

- Emulator: 36.6.11; API 36 AOSP ATD x86_64
- Nodes: `emulator-5554`, `emulator-5556`, `emulator-5558`
- Focused reset run: `build/emulator-results/20260720-055634-recovery-reset` - PASS
- Full matrix: `build/emulator-results/20260720-055703-all` - PASS
- Evidence archive: `build/emulator-evidence/20260720-055748.zip`
- Archive SHA-256: `B365468D5B7E0B953CE043F78B5C462650C0294BF3C6734E0FF2E580150328FA`

Every node logged count three, the exact exhausted attempt ID, and the required
cleanup order. The full matrix also passed smoke/UI hierarchy, pairwise shared
networking, NSD/Socket exchange, synthetic PCM metrics and transfer, KUM-37 hot
audio lifecycle, KUM-33 recovery timing, network fault/recovery, process
restart, and evidence collection. No app crash, ANR, instrumentation failure,
or test-failure marker was found. The connected MI 6 was excluded from every
install, instrumentation, network, screenshot, and collection command.

ATD screenshots are identical black frames on visual inspection and remain
`UNAVAILABLE_ATD_BLACK_FRAME`, not visual PASS.

## KUM-34 architecture review

The initial fixed-SHA read-only review at `61f4add` returned REQUEST CHANGES
with P0=0 and P1=4:

- Android Wi-Fi Direct close actions had no never-callback watchdog;
- a retry deadline expiring during asynchronous cleanup could replace the
  Coordinator attempt while Service had no active session to execute the new
  restart/reset effect;
- an active recovery signaling disconnect cleared Service schedules without
  clearing the old Coordinator channel context or rearming the same immutable
  attempt deadline; and
- tests did not cover the combined production cleanup/deadline/adapter order.

The first remediation source `8157a89` adds cancellable per-step close watchdogs,
coalesces cleanup-time retry/reset requests against the latest Coordinator
state, clears active recovery channel context while rearming the unchanged
deadline/fallback schedule, preserves the failure streak across identity
updates, and adds deterministic production-seam coverage.

The second fixed-SHA read-only review at `62166e8` returned REQUEST CHANGES with
P0=0 and P1=1. It found that `removeGroup` BUSY retries were posted outside the
close-step watchdog lifecycle, so an old delayed retry could call the old
manager/channel after timeout advanced through close and discovery rebuild.
Remediation source `b78ee8b` carries the owning step-active gate through every
initial call, Android callback, and delayed retry. The deterministic timeout/
BUSY race proves a queued retry becomes inert after the step advances. The third
fixed-SHA read-only review at `083585a` is APPROVED with P0=0 and P1=0. It
verified the production retry gate across initial calls, callbacks, posted
runnables, recursive retries, timeout, throw, and late completion while
preserving the prior ownership and cleanup corrections. Exact-Head CI
`29711409891` passed for the final documentation head. PR #12 merged as
`8dcb3f640e3c5b622da98bc1af68720502427ac8`, exact-main CI `29711575580`
passed, and Linear KUM-34 is Done.

## KUM-34 review-remediation evidence

| Check | Bound revision | Result | Evidence |
| --- | --- | --- | --- |
| Review-focused JVM | `8157a89` | PASS | 66 tests across four suites; every Wi-Fi Direct never-callback step, cleanup replacement through three deadlines, exact reset completion, active recovery rearm, and streak preservation; 0 failures/errors/skipped |
| Full JVM gate | `8157a89` | PASS | 284 tests; 0 failures, errors, or skipped |
| Lint | `8157a89` | PASS | 0 Fatal, 0 Error, 34 warnings |
| Debug APK | `8157a89` | PASS | `assembleDebug`; SHA-256 `C5957490F6E015E3F3FE71C1970FBF99BFA5D05FA0650254CC6EB32230599DA4` |
| Android test APK | `8157a89` | PASS | `assembleDebugAndroidTest`; SHA-256 `E56FFAB007E5E7DB4BDAB79BB2624672B51DC41D48D6DA1051F767A4614B6EC6` |
| Rasen strict validation | `8157a89` | PASS | 1/1; 4/4 artifacts complete; open findings 0 |
| Focused reset instrumentation | `8157a89` | PASS | `build/emulator-results/20260720-064827-recovery-reset`; 2/2 on each of three explicit API 36 emulators, 6/6 total |
| Full three-emulator matrix | `8157a89` | PASS | `build/emulator-results/20260720-064846-all` |
| Evidence archive | `8157a89` | PASS | `build/emulator-evidence/20260720-064931.zip`; SHA-256 `ADEABD9D9CF5E110873920EFCC2BE528D302725B17C0406D967FAB67592F93B0` |

No crash, ANR, instrumentation-failure, or test-failure marker was found. The
connected MI 6 was excluded by explicit emulator serials from install,
instrumentation, screenshots, and evidence collection. All three ATD
screenshots are still identical black frames and remain
`UNAVAILABLE_ATD_BLACK_FRAME`, not visual PASS.

## KUM-34 second review-remediation evidence

| Check | Bound revision | Result | Evidence |
| --- | --- | --- | --- |
| Review-focused JVM | `b78ee8b` | PASS | 67 tests across four suites, including the delayed BUSY retry after step-timeout race; 0 failures/errors/skipped |
| Full JVM gate | `b78ee8b` | PASS | 285 tests across 43 suites; 0 failures, errors, or skipped |
| Lint | `b78ee8b` | PASS | 0 Fatal, 0 Error, 34 warnings |
| Debug APK | `b78ee8b` | PASS | `assembleDebug`; SHA-256 `226E29C82276E59927004209FA954F6F4554170D126F7ABB93E19E6BDC94946A` |
| Android test APK | `b78ee8b` | PASS | `assembleDebugAndroidTest`; SHA-256 `86287442D2CCC007A515A0C71C4132EDB02402A3BB4A8551511D370BFDC66CD3` |
| Rasen strict validation | `b78ee8b` | PASS | 1/1; 4/4 artifacts complete; open findings 0 |
| Focused reset instrumentation | `b78ee8b` | PASS | `build/emulator-results/20260720-092931-recovery-reset`; 2/2 on each of three explicit API 36 emulators, 6/6 total |
| Full three-emulator matrix | `b78ee8b` | PASS | `build/emulator-results/20260720-092943-all` |
| Evidence archive | `b78ee8b` | PASS | `build/emulator-evidence/20260720-093027.zip`; SHA-256 `C1C1EF7C3E516D6619236EDF0B38C0F35D541790E37D46B5680313B4B450EB35` |
| Exact-Head CI | `083585a` | PASS | Android CI run `29711081197` |

No crash, ANR, instrumentation-failure, or test-failure marker was found. The
connected MI 6 and 2211133C physical devices were excluded by explicit emulator
serials from install, instrumentation, screenshots, and evidence collection.
All three ATD screenshots are identical black frames and remain
`UNAVAILABLE_ATD_BLACK_FRAME`, not visual PASS.

## KUM-35 delivery boundary

KUM-35 separates intentional current-session disconnect from full runtime Stop
without introducing another product-state, attempt, target, deadline, or winner
owner:

- `DisconnectRequested` and a valid owner-channel peer `DISCONNECT` converge in
  the existing Coordinator on one immutable exact-attempt release effect;
- local send success, send failure, owner close, and queued media-loss races end
  once as intentional cancellation and cannot create recovery;
- an explicit peer disconnect during recovery returns to `DISCOVERING` without
  incrementing the KUM-34 failure streak, retrying, or entering `RESETTING`;
- unexpected signaling/channel/WebRTC loss retains the approved KUM-33/KUM-34
  target-locked recovery and reset behavior;
- Service always drains the Coordinator-authorized old attempt's exact schedules,
  signaling/WebRTC ownership, and matching LAN/Wi-Fi Direct targeted leases
  before later FIFO transport-open effects, even if logical replacement ownership
  already exists; only idle searching-state finalization is replacement-gated;
- Service, runtime/session generation, discovery adapters, presence, foreground
  notification, and the KUM-37 runtime audio owner remain online; and
- the primary UI action disconnects the current rider only in attempt-bearing
  states, while online idle/reset keeps full Stop and only Stop reaches
  `STOPPING -> OFFLINE`.

No protocol, TargetLock, deadline, winner, database, pairing, identity,
dependency, permission, signing, deployment, or release behavior changed.
KUM-36 remains unstarted.

## KUM-35 automated evidence

| Check | Bound revision | Result | Evidence |
| --- | --- | --- | --- |
| Focused JVM | `0dcf63d` | PASS | 56 tests across the Coordinator and KUM-35 suites; 0 failures/errors/skipped |
| Full JVM gate | `0dcf63d` | PASS | 290 tests across 44 suites; 0 failures/errors/skipped |
| Lint | `0dcf63d` | PASS | 0 Fatal, 0 Error, 34 warnings |
| Debug APK | `0dcf63d` | PASS | `assembleDebug`; SHA-256 `7BF2EFD747FEBB8530803D277DF5CDBCDC5E6AEFAE4CC7491630C6F043ADA951` |
| Android test APK | `0dcf63d` | PASS | `assembleDebugAndroidTest`; SHA-256 `F3C375EBE4AD17E9AE793AEE8C90CD1DF4B9EC2AA90F8EEDE5395E8EFD81D699` |
| Active-disconnect instrumentation | `0dcf63d` | PASS | 2/2 on each of three API 36 emulators; 6/6 focused and 6/6 again in the full matrix |
| Rasen strict validation | `0dcf63d` | PASS | 1/1; 4/4 artifacts complete; open findings 0 |
| PowerShell compatibility | `0dcf63d` | PASS | all seven emulator scripts parse in Windows PowerShell 5.1 |

The first fixed-SHA architecture review at Draft Head `ebaf9a0` found one P1:
the exact old-attempt release and the idle searching-state finalization shared
one gate, so a rapid logical replacement could suppress physical cleanup. Commit
`bb9991f` splits those responsibilities. Same-runtime immutable cleanup now
always drains in effect-channel FIFO order, while connection flags and searching
status change only if Coordinator ownership is still idle. Replacement media,
transport leases, and status remain identity-gated and untouched.

| Review-remediation check | Bound revision | Result | Evidence |
| --- | --- | --- | --- |
| Focused JVM | `bb9991f` | PASS | 57 tests across the Coordinator and KUM-35 suites; 0 failures/errors/skipped |
| Full JVM gate | `bb9991f` | PASS | 291 tests across 44 suites; 0 failures/errors/skipped |
| Lint | `bb9991f` | PASS | 0 Fatal, 0 Error, 34 warnings |
| Debug APK | `bb9991f` | PASS | `assembleDebug`; SHA-256 `7F5AB277289F5CC2356B8A12B1E6E125CD33AC555816E996DD7E93AF4C130076` |
| Android test APK | `bb9991f` | PASS | `assembleDebugAndroidTest`; SHA-256 `E9BC26E85C66D5340751E537B6B815DA1950770C53555DF4854A1AD4557D14FB` |
| Active-disconnect instrumentation | `bb9991f` | PASS | 2/2 on each of three API 36 emulators; 6/6 focused and 6/6 again in the full matrix |
| Rasen strict validation | `bb9991f` | PASS | 1/1; 4/4 artifacts complete |

The first Gradle invocation in this checkout lacked `ANDROID_HOME` and stopped
before dependency resolution. The first compiled focused run then exposed one
non-canonical UUID in the new test fixture and three old assertions that still
expected broad abort for explicit local cancellation. Correcting test data and
the intended expectations made the unchanged production implementation pass;
the final focused, full, lint, build, and emulator gates above are green.

## KUM-35 emulator matrix

- Emulator: 36.6.11; API 36 AOSP ATD x86_64
- Nodes: `emulator-5554`, `emulator-5556`, `emulator-5558`
- Focused active-disconnect run:
  `build/emulator-results/20260720-105809-active-disconnect` - PASS
- Full matrix: `build/emulator-results/20260720-105813-all` - PASS
- Evidence archive: `build/emulator-evidence/20260720-105850.zip`
- Archive SHA-256:
  `A19A1A75E4142390BD4258CA8E5B3796D954A2A9C7996CD7D99A6103747553A8`

Every node proved exact explicit-attempt release, retained runtime/discovery/
audio owners, full-Stop action after returning to discovery, and unchanged
target-locked recovery for unexpected loss. The full matrix also passed app
smoke/UI hierarchy, pairwise shared networking, NSD/Socket exchange,
deterministic synthetic PCM metrics and transfer, hot audio lifecycle, KUM-33
recovery timing, KUM-34 reset, bounded network fault/recovery, and process
restart. No crash, ANR, instrumentation-failure, or test-failure marker was
found.

The connected MI 6 and 2211133C physical devices were excluded from every
install, instrumentation, network, screenshot, and evidence command. All three
ATD screenshots are black on visual inspection and remain
`UNAVAILABLE_ATD_BLACK_FRAME`, not visual PASS.

## KUM-35 review and delivery

The implementation source is `0dcf63d`; first-review remediation is fixed at
`bb9991f`. Draft PR #13 is open and Linear KUM-35 is In Review with delivery
evidence comment `f0da70b3-2085-4c97-9587-d8746772d1dc`. Initial Draft Head
`ebaf9a0` passed exact-Head CI `29713126973`; its fixed-SHA read-only review was
`REQUEST CHANGES`, P0=0/P1=1. The P1 is remediated, all local automated gates
above are green, and the fixed Base `8dcb3f6` / Head `8c3face` re-review is
`APPROVED`, P0=0/P1=0. Final delivery Head CI, merge commit, exact-main CI, and
Linear completion remain pending. KUM-36 cannot start until those gates close.

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
| KUM-32 implementation/automated gate | PASS at `ba649e3`; CI `29697109471` and emulator matrix `20260720-013122-all` passed |
| KUM-32 architecture review | APPROVED at `5f1d1d6`; P0=0/P1=0 |
| KUM-32 may move to Done | YES - merged as `5c49a53`, exact-main CI `29698415621` passed, Linear Done |
| KUM-33 may start | YES - active on `feat/kum-33-three-second-recovery-fallback` from `5c49a53` |
| KUM-33 implementation/automated gate | PASS at second-remediation source `b0cb6c7`; 266 JVM tests and emulator matrix `20260720-043350-all` passed |
| KUM-33 architecture review | APPROVED at `29fd4ef`; P0=0/P1=0 after initial P1=2 and second P1=1 remediation rounds |
| KUM-33 may move to Done | YES - merged as `34f715d`, exact-main CI `29703574642` passed, Linear Done |
| KUM-34 may start | YES - active on `feat/kum-34-resetting-wireless-reset` from `34f715d` |
| KUM-34 implementation/automated gate | PASS at second-remediation source `b78ee8b`; 285 JVM tests and emulator matrix `20260720-092943-all` passed |
| KUM-34 architecture review | APPROVED at `083585a`, P0=0/P1=0 after initial P1=4 and second P1=1 remediation rounds |
| KUM-34 may move to Done | YES - merged as `8dcb3f6`, exact-main CI `29711575580` passed, Linear Done |
| KUM-35 may start | YES - active on `feat/kum-35-active-disconnect-stay-online` from `8dcb3f6` |
| KUM-35 implementation/automated gate | PASS after review remediation `bb9991f`; 291 JVM tests and emulator matrix `20260720-105813-all` passed |
| KUM-35 architecture review | APPROVED at `8c3face`, P0=0/P1=0 after initial `ebaf9a0` P1=1 remediation |
| KUM-35 may move to Done | NO - Draft PR, exact-Head CI, APPROVED review with P0=0/P1=0, merge, exact-main CI, and Linear completion remain |
| Sprint 4 may close | NO - KUM-35 is In Review; KUM-36 remains Todo |
| Production deployment | NO - final physical Release Candidate gate and explicit authorization required |

## Residual risk

Automated evidence proves object ownership, sequential PeerConnection reuse,
single-session enforcement, stale callback exclusion, and no remote media path
without a session. ATD and synthetic audio cannot prove real SCO, OEM route
prompts, microphone continuity, hardware AEC, acoustic quality, power, thermal,
or background survival. Those accepted hardware-only checks remain explicit
Release Candidate work.
