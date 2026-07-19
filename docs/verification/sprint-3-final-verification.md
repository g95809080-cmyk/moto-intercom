# Sprint 3 Final Verification

Status: **KUM-31 REVIEW APPROVED - FINAL EVIDENCE-SYNC CI PENDING**

Evidence state: 2026-07-19

## Bound revision

- Repository: `g95809080-cmyk/moto-intercom`
- Branch: `feat/kum-31-fallback-verification`
- Pull request: [#8](https://github.com/g95809080-cmyk/moto-intercom/pull/8) - Draft
- KUM-31 base and current `main`: `830c5e6974f2c1ddca71f020db137a0d433978bc`
- Verified KUM-31 source head: `5b3e1d1bf7dc8be5e066e95f9547e019d939941c`
- Reviewed KUM-31 head: `1d0e2189ec06b949d1b507a3debc6afde57e2191`
- Reviewed-head GitHub Actions: [run 29686642730](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/29686642730) - success
- Fixed-SHA architecture review: APPROVED, P0=0, P1=0
- KUM-30 final PR head: `bf566bc154de4132bbe1594a08b5dd80a0254e61`
- KUM-30 merge commit: `830c5e6974f2c1ddca71f020db137a0d433978bc`
- KUM-30 final PR CI: [run 29684691731](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/29684691731) - success
- Baseline main GitHub Actions: [run 29684877764](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/29684877764) - success
- Linear: KUM-8 In Progress; KUM-27 Done; KUM-28 Done; KUM-29 Done;
  KUM-30 Done; KUM-31 In Progress

This is the single Sprint 3 evidence index. Report-only commits that update this
file do not change reviewed source heads. KUM-27 through KUM-31 evidence remains
here; issue-scoped reports retain detailed historical review notes only.

## KUM-27 delivery boundary

KUM-27A approved the ownership matrix. KUM-27B then completed B1-B6:

- immutable attempt domain model and deterministic monotonic clock;
- one attempt creation/termination owner and first-terminal ordering;
- one immutable 10-second total deadline owner;
- context-complete callback, candidate, media, and cleanup lifecycle;
- adapter operations bounded by remaining attempt time; and
- full automated regression, three-emulator validation, and the Release
  Candidate physical-test plan.

`SessionOrchestrator` remains the only product-state writer. KUM-27 retains a
single-transport `ChannelPlan`. No T+5 fallback, dual-transport race,
optimization window, `OPTIMIZING`, or other KUM-28 runtime behavior is present.

## KUM-28 delivery boundary

KUM-28 evolves the same Coordinator in place and adds the approved bounded race:

- immutable ordered preferred plus optional distinct fallback `ChannelPlan`;
- LAN preferred at T0 when both Presence transports are available;
- one exact fallback milestone at T+5 without rebasing the T+10 deadline;
- per-transport failure state that cannot move fallback earlier;
- existing optional signaling preference hint for dual intent, with no schema or
  protocol-version change;
- responder-owned fallback-first `OPTIMIZING` for at most one monotonic second;
- exactly one current verified media owner and one `CONNECT_ACCEPT`;
- actual winner transport recorded in product `Connected` state;
- loser Socket/P2P/task cleanup that preserves later recovery capability; and
- fresh recovery attempt identity/deadline with repeated T0/T+5 scheduling.

No second Coordinator, product-state writer, total-deadline owner, or media owner
was added. Identity, TargetLock, pairing, database, notification, permission,
WebRTC engine, Bluetooth, audio-route, Sprint 4, signing, deployment, and release
scope remain unchanged.

## KUM-29 delivery boundary

KUM-29 hardened the existing one-second optimization and cleanup boundaries:

- selection cohort freezes at exact optimization expiry;
- the immutable total deadline is rechecked before owner claim;
- exact current-owner request retransmission is idempotent;
- loser cleanup has an exact-context monotonic one-second watchdog; and
- repeated loser rejects preserve the earliest cleanup deadline.

PR [#6](https://github.com/g95809080-cmyk/moto-intercom/pull/6) merged with merge
commit `6f1839748307cb6b62d25d9fc5d613d679f9ffad`. Main CI run `29681780079`
passed and KUM-29 is Done.

## KUM-30 delivery boundary

KUM-30 certifies the merged runtime rather than adding another owner:

- multi-candidate cancellation records one canceled outcome, closes every
  candidate, aborts once, and cannot be revived by late callbacks;
- the existing canonical `WireRequestKey` rule remains the deterministic glare
  arbiter across physical Socket roles;
- 128 concurrent mailbox submissions commit one media owner, one WebRTC start,
  and one terminal cleanup path with no retained attempt resources; and
- stale-attempt OFFER, ANSWER, and ICE envelopes fail pinned Socket identity
  before reader callback handoff.

No production source changed. The existing `SessionOrchestrator` mailbox,
Coordinator, Socket identity, and exact media-context gates passed the new
regressions, so no runtime remediation was required.

PR [#7](https://github.com/g95809080-cmyk/moto-intercom/pull/7) merged by merge
commit `830c5e6974f2c1ddca71f020db137a0d433978bc`. Final PR CI run
`29684691731` and main CI run `29684877764` passed; KUM-30 is Done.

## KUM-31 delivery boundary

KUM-31 found and closes one runtime evidence gap in the merged race. When the
exact T+5 targeted Wi-Fi Direct fallback returned `WifiP2pManager.BUSY`, the
adapter retried locally while the Coordinator kept LAN targeted work active, so
automatic sequential fallback on hardware without stable overlap was not
provable.

The minimal remediation keeps the existing owners and schedule:

- the P2P adapter emits a typed, immutable-attempt overlap-unavailable event;
- only a LAN-preferred/P2P-fallback targeted BUSY can produce that event;
- the existing Coordinator accepts it once, only after fallback opened, while
  the attempt is current/unexpired and has no preferred requester candidate or
  media owner;
- the Coordinator retires LAN then retries P2P for the same attempt, target,
  plan, and immutable T+10 deadline;
- retired LAN is excluded from viability and late channel adoption; and
- Service executes ordered exact-attempt effects while adapters remain bounded
  physical executors.

No product-state writer, Coordinator, total-deadline owner, target selection,
protocol, identity, database, UI, notification, WebRTC, Sprint 4, signing,
deployment, or release behavior was added or changed.

## KUM-27 automated evidence

| Check | Bound revision | Result | Evidence |
| --- | --- | --- | --- |
| JVM unit gate | `4b9f6bd` | PASS | 213 tests; 33 suites; 0 failures, errors, or skipped |
| Lint | `4b9f6bd` | PASS | 0 Fatal, 0 Error, 34 existing warnings |
| Debug APK | `4b9f6bd` | PASS | `assembleDebug` |
| Android test APK | `4b9f6bd` | PASS | `assembleDebugAndroidTest` |
| Rasen strict validation | `4b9f6bd` | PASS | 1/1 |
| PowerShell compatibility | `4b9f6bd` | PASS | all seven emulator scripts parse in Windows PowerShell 5.1 |
| GitHub Actions | `4b9f6bd` | PASS | run `29674202727` |

The first local Gradle invocation did not enter compilation because the shell
lacked an Android SDK environment variable. The same gate passed after binding
`ANDROID_HOME` and `ANDROID_SDK_ROOT` to the already installed SDK. This was an
environment setup failure, not a source failure.

## KUM-28 automated evidence

| Check | Bound revision | Result | Evidence |
| --- | --- | --- | --- |
| JVM unit gate | `f96ba4d` | PASS | 230 tests; 34 suites; 0 failures, errors, or skipped |
| Lint | `f96ba4d` | PASS | 0 Fatal, 0 Error, 34 existing warnings |
| Debug APK | `f96ba4d` | PASS | `assembleDebug` |
| Android test APK | `f96ba4d` | PASS | `assembleDebugAndroidTest` |
| Rasen strict validation | `f96ba4d` | PASS | 1/1 |
| PowerShell compatibility | `f96ba4d` | PASS | all seven emulator scripts parse in Windows PowerShell 5.1 |
| GitHub Actions | `f96ba4d` | PASS | run `29677921267` |

Deterministic tests cover immutable plan ordering, T+5-1/T+5/T+5+1,
duplicate/replacement/runtime milestone invalidation, preferred/fallback failure
ordering, exact total deadline, single/dual signaling hints, preferred-first,
fallback-first, preferred arrival inside the optimization window, fallback at
window expiry, cancel, stale callbacks, simultaneous request/glare, one media
owner, loser cleanup, actual winner transport, and recovery scheduling.

## KUM-29 automated evidence

| Check | Bound revision | Result | Evidence |
| --- | --- | --- | --- |
| Targeted JVM | `678c89e` | PASS | 68 tests; 4 suites; 0 failures, errors, or skipped |
| Full JVM gate | `678c89e` | PASS | 235 tests; 35 suites; 0 failures, errors, or skipped |
| Lint | `678c89e` | PASS | 0 Fatal, 0 Error, 34 existing warnings |
| Debug APK | `678c89e` | PASS | `assembleDebug` |
| Android test APK | `678c89e` | PASS | `assembleDebugAndroidTest` |
| Rasen strict validation | `678c89e` | PASS | 1/1 |
| Reviewed-source CI | `678c89e` | PASS | run `29681237026` |
| Final PR CI | `687b9d8` | PASS | run `29681601138` |
| Main CI | `6f18397` | PASS | run `29681780079` |

## KUM-30 automated evidence

| Check | Bound revision | Result | Evidence |
| --- | --- | --- | --- |
| Targeted JVM | `ebcf3f1` | PASS | 102 tests; 8 suites; 0 failures, errors, or skipped |
| Full JVM gate | `ebcf3f1` | PASS | 238 tests; 35 suites; 0 failures, errors, or skipped |
| Lint | `ebcf3f1` | PASS | 0 Fatal, 0 Error, 34 existing warnings |
| Debug APK | `ebcf3f1` | PASS | `assembleDebug` |
| Android test APK | `ebcf3f1` | PASS | `assembleDebugAndroidTest` |
| Rasen strict validation | `ebcf3f1` | PASS | 1/1 |
| Final PR CI | `bf566bc` | PASS | run `29684691731` |
| Main CI | `830c5e6` | PASS | run `29684877764` |

## KUM-31 automated evidence

| Check | Bound revision | Result | Evidence |
| --- | --- | --- | --- |
| Targeted JVM | `5b3e1d1` | PASS | 25 tests; 2 suites; 0 failures, errors, or skipped |
| Full JVM gate | `5b3e1d1` | PASS | 244 tests; 35 suites; 0 failures, errors, or skipped |
| Lint | `5b3e1d1` | PASS | 0 Fatal, 0 Error, 34 existing warnings |
| Debug APK | `5b3e1d1` | PASS | `assembleDebug` |
| Android test APK | `5b3e1d1` | PASS | `assembleDebugAndroidTest` |
| Rasen strict validation | `5b3e1d1` | PASS | 1/1 |
| Reviewed-head GitHub Actions | `1d0e218` | PASS | run `29686642730` |

Deterministic coverage binds exact T+5 opening, ordered retire-LAN/retry-P2P,
same attempt/target/plan/deadline, duplicate BUSY idempotency, pre-fallback and
post-candidate rejection, wrong/replaced/canceled/expired attempts, retired LAN
callback exclusion, fallback terminal failure, pure BUSY classification, and
exact physical retire routing. Existing one-media-owner and concurrent mailbox
regressions also remained green.

## KUM-27 emulator matrix

- Emulator: 36.6.11
- Image: API 36 AOSP ATD x86_64
- AVD: `MotoIntercom_API_36`
- Nodes: `emulator-5554`, `emulator-5556`, `emulator-5558`
- Shared addresses: `10.0.2.16`, `10.0.2.17`, `10.0.2.18`
- Result: `build/emulator-results/20260719-125710-all` - PASS
- Evidence archive: `build/emulator-evidence/20260719-125749.zip`
- Archive SHA-256: `37DBC43B64DB5F8418793EEC2DCD58F5EB9382F5242FE97A9B1F2161F9C8D3D6`

The matrix covered app launch/UI capture, pairwise LAN reachability, Android
NSD plus Socket exchange, deterministic synthetic PCM transfer, bounded Wi-Fi
disable/enable recovery, process restart, evidence collection, and bounded
cluster shutdown. Scripts used explicit emulator serials; the connected MI 6
physical device was never selected.

## Failed-client cleanup regression

The initial architecture review found two P1 instances of the same cleanup
defect: a synthetic-audio or NSD client failure before server `WaitForExit`
could leave background server instrumentation or its host ADB process alive.

At the fixed head, each client test APK was deliberately removed to force an
`INSTRUMENTATION_FAILED` result. Both scenarios then showed:

- expected client failure;
- no remaining server app PID;
- no remaining server test PID; and
- no matching host ADB child process.

After reinstalling the test APK, the normal three-node matrix passed. A final
scan found no test process, host instrumentation child, instrumentation failure,
fatal exception, or app ANR marker.

## KUM-27 architecture review

```text
Base SHA: 932c81c5ce30ae5087134db1591f48c6fd3d6ae1
Head SHA: 4b9f6bd79255891d88e35f1d88699432c4ff5e65
Result: APPROVED
P0: 0
P1: 0
B1-B6 complete: YES
```

The reviewer confirmed that both prior P1s are closed, success behavior is
preserved, cleanup targets only the exact server emulator/package and spawned
ADB process, test audio remains outside release source, and KUM-28 behavior is
absent.

Non-blocking hardening: callers should continue passing explicit cluster
serials when unrelated emulators are present. The current accepted runs did so.

## KUM-28 emulator matrix

- Emulator: 36.6.11
- Image: API 36 AOSP ATD x86_64
- AVD: `MotoIntercom_API_36`
- Nodes: `emulator-5554`, `emulator-5556`, `emulator-5558`
- Result: `build/emulator-results/20260719-151413-all` - PASS
- Evidence archive: `build/emulator-evidence/20260719-151445.zip`
- Archive SHA-256: `BD386B39226DCBBB97F7E067BE9C4E8C966A9194F18E5967F413C3843A545F2A`

The final matrix used the APKs built from the reviewed source and covered app
launch, real UI hierarchy capture on all three nodes, pairwise shared-network
reachability, Android NSD and Socket exchange, synthetic PCM transfer, bounded
network fault/recovery, process restart, evidence collection, and cluster
shutdown. A transient ATD `null root node` result in an earlier run exposed a
false-positive smoke check; the script now retries three times and fails closed.
The accepted run contains a hierarchy on all nodes and no app crash or ANR.

## KUM-28 architecture review

```text
Base SHA: a31140c08b1eb36f53abc19b09eca45727a785d1
Head SHA: f96ba4d0a536b6bfc226d111c5a843cf622f1d75
Result: APPROVED
P0: 0
P1: 0
KUM-28 complete: YES
Sprint 3 closure allowed: YES
```

The independent read-only reviewer confirmed the immutable LAN-preferred plan,
guarded exact fallback milestone, stale-event rejection, one media owner,
actual winner transport, and Service current-attempt validation. The only
non-blocking note was trailing blank lines in Markdown; `git diff --check`
passed and no source gate depends on that formatting.

## KUM-29 emulator matrix

- Emulator: 36.6.11
- Image: API 36 AOSP ATD x86_64
- AVD: `MotoIntercom_API_36`
- Nodes: `emulator-5554`, `emulator-5556`, `emulator-5558`
- Result: `build/emulator-results/20260719-171106-all` - PASS
- Evidence archive: `build/emulator-evidence/20260719-171153.zip`
- Archive SHA-256: `11FBAB8D600B1D065C12FC0BCEA62787113A2F745C675E60609BDB6AD7AA916F`

## KUM-29 architecture review

```text
Base SHA: 657d5264d0967259000359ccbc6a22bceb133ed4
Head SHA: 678c89ee7688b7b74110efc325da133cdb6c0f63
Result: APPROVED
P0: 0
P1: 0
KUM-29 complete: YES
KUM-30 allowed: YES
```

## KUM-30 emulator matrix

- Emulator: 36.6.11
- Image: API 36 AOSP ATD x86_64
- AVD: `MotoIntercom_API_36`
- Nodes: `emulator-5554`, `emulator-5556`, `emulator-5558`
- Shared addresses: `10.0.2.16`, `10.0.2.17`, `10.0.2.18`
- Result: `build/emulator-results/20260719-181923-all` - PASS
- Fresh evidence archive: `build/emulator-evidence/20260719-182623.zip`
- Archive SHA-256: `8839E1E64EF44F8AAAB8940F2B568C6DBC1F58A5992E9E25842739C007605E7A`

The accepted matrix covered pairwise shared-network reachability, Android NSD
and Socket exchange, synthetic PCM metrics and network transfer, bounded network
fault/recovery, process restart, complete UI hierarchy capture, log collection,
and bounded cluster shutdown. Five instrumentation invocations reported `OK`;
no app FATAL, ANR, instrumentation failure, or assertion marker was found.

All three UI hierarchies contain `摩声 MotoCom` and `启动摩声`, and WindowManager
reported a visible, drawn `MainActivity` surface. The API 36 AOSP ATD framebuffer
returned an all-black PNG for every app node and for the system Home screen, all
with SHA-256 `C35BACDB98B522206335AFA5B9BAFFD2E4E3352A40749BB747E469CD403AF514`.
Those screenshots are marked `UNAVAILABLE_ATD_BLACK_FRAME` and are not accepted
as visual evidence; functional emulator evidence remains the UI tree,
instrumentation, network, process, and log results.

## KUM-30 architecture review

```text
Base SHA: 6f1839748307cb6b62d25d9fc5d613d679f9ffad
Head SHA: ea0c72fff7e6882b0a7f25836e926cb747757edb
Result: APPROVED
P0: 0
P1: 0
KUM-30 complete: YES
KUM-31 allowed after merge and green main CI: YES
```

The reviewer confirmed all six Exit Criteria, no production-source change, one
product-state writer, one Coordinator, one media owner, fail-closed stale media,
deterministic mailbox concurrency, and honest exclusion of black ATD screenshots.
The only non-blocking finding was this report/tasks synchronization.

## KUM-31 emulator matrix

- Emulator: 36.6.11
- Image: API 36 AOSP ATD x86_64
- AVD: `MotoIntercom_API_36`
- Nodes: `emulator-5554`, `emulator-5556`, `emulator-5558`
- Shared addresses: `10.0.2.16`, `10.0.2.17`, `10.0.2.18`
- Result: `build/emulator-results/20260719-200116-all` - PASS
- Fresh evidence archive: `build/emulator-evidence/20260719-200157.zip`
- Archive SHA-256: `FA9609A72916000C786F8FEAEC3C13C0BA38F71269B101715461B974DA2007B5`

The matrix passed all six pairwise shared-network probes, Android NSD and Socket
exchange, three single-node synthetic PCM metric tests, both endpoints of the
synthetic network audio exchange, bounded offline/recovery, process restart on
all three nodes, UI hierarchy capture, and log/resource evidence collection.
No app FATAL, ANR, instrumentation failure, or assertion marker was found. The
connected MI 6 was excluded from every command and remained untouched.

All three UI hierarchies contained the app package and rendered hierarchy. The
API 36 AOSP ATD framebuffer again returned identical all-black PNGs with
SHA-256 `C35BACDB98B522206335AFA5B9BAFFD2E4E3352A40749BB747E469CD403AF514`.
They are `UNAVAILABLE_ATD_BLACK_FRAME`, not visual PASS; instrumentation, UI
hierarchy, network, process, service, and log evidence carry this gate.

## KUM-31 architecture review

```text
Base SHA: 830c5e6974f2c1ddca71f020db137a0d433978bc
Head SHA: 1d0e2189ec06b949d1b507a3debc6afde57e2191
Result: APPROVED
P0: 0
P1: 0
KUM-31 complete: YES
Sprint 3 close allowed: YES
```

The independent read-only reviewer traced the BUSY producer, immutable-attempt
event, Coordinator acceptance guards, retired-transport viability and channel
adoption, ordered Service effects, adapter generation/remaining-time checks,
and fallback terminal accounting. It found no second product-state writer,
Coordinator, deadline owner, target selector, or media owner. No development
evidence is missing; real OEM overlap behavior remains RC-only residual risk.

## Physical acceptance queue

The validation timing is governed by the accepted
[development/Release Candidate gate decision](../decisions/2026-07-19-development-validation-release-candidate-gate.md).
The authoritative procedure is
[`release-candidate-physical-plan.md`](release-candidate-physical-plan.md).
Every row below remains mandatory before production release and is not claimed
as passed:

- OEM Wi-Fi Direct differences;
- real RF distance and interference;
- OEM background restrictions;
- Bluetooth SCO;
- real microphone and speaker;
- hardware echo cancellation;
- human listening; and
- long-duration power, thermal, and background survival.

Current status for every row: `DEFERRED_TO_RELEASE_CANDIDATE`.

## Gate decision

| Gate | Decision |
| --- | --- |
| KUM-27 Exit Criteria | PASS |
| KUM-28 Exit Criteria | PASS |
| KUM-29 Exit Criteria | PASS |
| KUM-30 Exit Criteria | PASS - merged, main CI green, Done |
| KUM-31 local automated gate | PASS |
| KUM-31 architecture review | APPROVED - P0=0, P1=0 |
| KUM-31 reviewed-head CI | PASS - run `29686642730` |
| KUM-31 Draft PR | OPEN - #8 |
| KUM-31 PR may become Ready | YES after final evidence-only Head CI succeeds |
| KUM-31 PR may merge | YES by merge commit after the final unchanged gate |
| KUM-31 may move to Done | NO - merge and green `main` CI pending |
| Sprint 3 may close | NO - KUM-31 delivery remains open |
| Production deployment | NO - final physical Release Candidate gate and explicit authorization required |

## Efficiency

- KUM-27 B6 elapsed: 1h15m from the recorded B6 start to fixed-SHA approval.
- KUM-28 elapsed: approximately 2 hours from Linear start to fixed-SHA approval.
- KUM-29 elapsed: approximately 1h26m from Linear start to green main CI.
- KUM-30 elapsed to fixed-SHA approval: approximately 1h22m.
- KUM-31 elapsed to fixed-SHA approval: approximately 1h08m.
- KUM-30 workers: one write worker; two sequential read-only reviewer instances. The
  first was stopped after failing to return a conclusion; the second completed.
- KUM-31 workers: one write worker and one independent read-only reviewer.
- Completed KUM-30 architecture review rounds: 1.
- Completed KUM-31 architecture review rounds: 1.
- Runtime handoffs inherited during KUM-30: 1.
- Runtime handoffs inherited during KUM-31: 1.
- Goal-runtime cumulative token usage at KUM-30 approval: 6,289,417; a
  KUM-30-only split was not exposed.
- Goal-runtime cumulative token usage at KUM-31 approval: 8,638,468; a
  KUM-31-only split was not exposed.
- Repeated full-repository rescans: 0.

## Residual risk

Emulator and synthetic evidence cannot validate OEM, RF, Bluetooth, acoustic,
thermal, or human-perception behavior. Those checks remain explicit Release
Candidate work. If a server emulator becomes unreachable during failure
cleanup, device instrumentation relies on its bounded socket timeout while the
captured host ADB process is still terminated. Emulator Wi-Fi Direct cannot
prove OEM concurrent-radio behavior; fake transport/callback tests carry the
development gate and physical T0/T+5 race behavior remains deferred. API 36
AOSP ATD framebuffer screenshots are unavailable in this environment and are
not used as visual pass evidence; complete UI hierarchy and drawn-surface checks
remain available.
