# Sprint 3 Final Verification

Status: **KUM-27 AUTOMATED GATE PASSED - SPRINT 3 IN PROGRESS**

Evidence state: 2026-07-19

## Bound revision

- Repository: `g95809080-cmyk/moto-intercom`
- Branch: `feat/kum-27b-connection-attempt-coordinator`
- Pull request: [#4](https://github.com/g95809080-cmyk/moto-intercom/pull/4)
- PR base: `48e5ac7bd8c949ae1b38d2a36013b498850a31c3`
- B6 review base: `932c81c5ce30ae5087134db1591f48c6fd3d6ae1`
- Verified source head: `4b9f6bd79255891d88e35f1d88699432c4ff5e65`
- GitHub Actions: [run 29674202727](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/29674202727) - success
- Linear: KUM-8 In Progress; KUM-27 In Progress pending merge/main CI; KUM-28 Todo

This is the single Sprint 3 evidence index. The report-only commit that adds or
updates this file does not change the verified source head. KUM-28 evidence will
be appended here rather than maintained in a second Sprint report.

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

## Automated evidence

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

## Emulator matrix

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

## Architecture review

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
| Known P0 / P1 | 0 / 0 |
| PR #4 may become Ready | YES after this report/tasks commit CI succeeds |
| PR #4 may merge | YES by merge commit after the final unchanged gate check |
| KUM-27 may move to Done | YES after merge and green `main` CI |
| Sprint 3 may close | NO - KUM-28 remains Todo |
| KUM-28 may start | NO until PR #4 merges and `main` CI succeeds |
| Production deployment | NO - final physical Release Candidate gate and explicit authorization required |

## Efficiency

- B6 elapsed: 1h15m from the recorded B6 start to fixed-SHA approval.
- Workers: 2 (one write worker and one independent read-only reviewer).
- Architecture review rounds: 2.
- Runtime handoffs: 1.
- Goal-runtime cumulative token usage at approval: 1,114,599; a B6-only split
  was not exposed.
- Repeated full-repository rescans: 0.

## Residual risk

Emulator and synthetic evidence cannot validate OEM, RF, Bluetooth, acoustic,
thermal, or human-perception behavior. Those checks remain explicit Release
Candidate work. If a server emulator becomes unreachable during failure
cleanup, device instrumentation relies on its bounded socket timeout while the
captured host ADB process is still terminated.
