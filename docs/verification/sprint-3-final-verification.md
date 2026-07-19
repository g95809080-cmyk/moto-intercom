# Sprint 3 Final Verification

Status: **KUM-28 AUTOMATED GATE PASSED - SPRINT 3 MERGE GATE**

Evidence state: 2026-07-19

## Bound revision

- Repository: `g95809080-cmyk/moto-intercom`
- Branch: `feat/kum-28-t0-t5-transport-race`
- Pull request: [#5](https://github.com/g95809080-cmyk/moto-intercom/pull/5)
- PR base and review base: `a31140c08b1eb36f53abc19b09eca45727a785d1`
- Verified KUM-28 source head: `f96ba4d0a536b6bfc226d111c5a843cf622f1d75`
- GitHub Actions: [run 29677921267](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/29677921267) - success
- Linear: KUM-8 In Progress; KUM-27 Done; KUM-28 In Review

This is the single Sprint 3 evidence index. Report-only commits that update this
file do not change either reviewed source head. KUM-27 history remains below;
KUM-28 evidence is appended here rather than maintained in a second report.

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
| Known P0 / P1 | 0 / 0 |
| PR #5 may become Ready | YES after this report/tasks commit CI succeeds |
| PR #5 may merge | YES by merge commit after the final unchanged gate check |
| KUM-28 may move to Done | YES after merge and green `main` CI |
| Sprint 3 may close | YES after PR #5 merge, green `main` CI, and Linear sync |
| Production deployment | NO - final physical Release Candidate gate and explicit authorization required |

## Efficiency

- KUM-27 B6 elapsed: 1h15m from the recorded B6 start to fixed-SHA approval.
- KUM-28 elapsed: approximately 2 hours from Linear start to fixed-SHA approval.
- Workers: one write worker; two sequential read-only reviewer instances, with
  the first executor stopped after failing to return a conclusion.
- Completed architecture review rounds: 1.
- Runtime handoffs inherited during KUM-28: 1.
- Goal-runtime cumulative token usage at KUM-28 approval: 2,271,286; a
  KUM-28-only split was not exposed.
- Repeated full-repository rescans: 0.

## Residual risk

Emulator and synthetic evidence cannot validate OEM, RF, Bluetooth, acoustic,
thermal, or human-perception behavior. Those checks remain explicit Release
Candidate work. If a server emulator becomes unreachable during failure
cleanup, device instrumentation relies on its bounded socket timeout while the
captured host ADB process is still terminated. Emulator Wi-Fi Direct cannot
prove OEM concurrent-radio behavior; fake transport/callback tests carry the
development gate and physical T0/T+5 race behavior remains deferred.
