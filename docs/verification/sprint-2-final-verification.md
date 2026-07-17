# Sprint 2 Final Verification

Status: **KUM-26 ACCEPTANCE PASSED - PR REMAINS DRAFT**

Evidence state: 2026-07-17

## Bound revision

- Repository: `g95809080-cmyk/moto-intercom`
- Branch: `feat/sprint2-signaling-v2`
- Base: `fb01c3c42f0ba294462532be5ac2b12a0252e62c`
- Verified production-code baseline: `be17580219c1584805ee95450f71740e9e80e33c`
- Verified APK SHA-256: `8B2E3F5F41415EBE694BAE150169BDEE5ADC14A5ADCAD4E9531C93974597B16A`
- GitHub Actions: [run 29553954882](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/29553954882) - success
- Pull request: [#2](https://github.com/g95809080-cmyk/moto-intercom/pull/2) - Draft
- Linear: [KUM-26](https://linear.app/kuma999/issue/KUM-26/s26-完成三逻辑节点与两真机-target-lock-协议验收)

This report is the single Sprint 2 evidence index. PR and Linear updates must
link here and must not maintain independent test totals. The report-only commit
that contains this final index does not change the production-code baseline.

## Approved evidence boundary

- Device A: MI 6 (`9688fa60`), Android 9 / SDK 28.
- Device B: 2211133C (`efcb9031`), Android 16 / SDK 36.
- Device A stable identity: `7deb6ceb-323a-473e-9a77-f79e67a4d460`.
- Device B stable identity: `a74a4aa6-9011-4c38-99fe-aaa993d4c7a5`.
- Logical node C: deterministic non-target protocol/state/resource test node.
- PC endpoint: controlled Signaling v2 software endpoint only.
- A PC, emulator, fake adapter, or JVM node is not a third physical phone.

Deferred physical validation / Accepted residual risk:

> Three physical Android devices simultaneously share one wireless environment
> while A locks B and C participates in real LAN or Wi-Fi Direct discovery and
> group formation.

This topology was not executed and is not claimed as passed. The approved KUM-26
gate records it as accepted residual risk for the current one-to-one product. It
does not authorize KUM-27, Transport Race, or Sprint 4 recovery policy.

## Automated evidence

| Check | Revision | Result | Evidence |
| --- | --- | --- | --- |
| Android unit gate | `be17580` | PASS | 169 tests; 0 failures, 0 errors, 0 skipped; 29 suites |
| Lint | `be17580` | PASS | 0 errors, 28 warnings |
| Debug assemble | `be17580` | PASS | APK hash matches both installed devices |
| GitHub Actions | `be17580` | PASS | [run 29553954882](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/29553954882) |
| KUM-26 logical A/B/C acceptance | `be17580` | PASS | `Kum26LogicalNodeAcceptanceTest` retained in the full gate |
| Wi-Fi Direct setup recovery | `be17580` | PASS | `WifiDirectSetupRecoveryGateTest` retained in the full gate |
| Controlled PC Signaling v2 endpoint | `be17580` | PASS | 11 tests in `tools/kum26_peer_test.py` |

The final local gate was rerun from the clean detached `be17580` evidence
worktree with explicit JDK and Android SDK paths:

```text
testDebugUnitTest: 169 tests, 0 failures / 0 errors / 0 skipped
lintDebug: 0 errors / 28 warnings
assembleDebug: PASS
APK SHA-256: 8B2E3F5F41415EBE694BAE150169BDEE5ADC14A5ADCAD4E9531C93974597B16A
PC Signaling v2: 11 tests, OK
```

## Logical A/B/C and resource evidence

The committed automated gate proves all of the following:

- non-target C cannot satisfy A's TargetLock for B;
- wrong device, wrong runtime, missing identity, empty identity, and malformed
  identity fail closed;
- a superseded runtime cannot register a control channel as the current Service
  session;
- stale activation cannot start WebRTC, retain its Socket, or retain a tunnel
  claim after its attempt ends;
- transport-open failure, timeout ordering, and replaced-attempt callbacks
  cannot leave CONNECTING stuck or take over a newer attempt;
- recovery retains the original TargetLock and single-transport ChannelPlan;
- rejected paths do not create pre-accept WebRTC or write PairingRepository;
- a third-party request receives channel-scoped BUSY without replacing the
  active attempt, wire key, or media owner;
- P2P-disabled setup does not retry-loop, disabled-to-enabled starts one fresh
  setup generation, BUSY is deduplicated, and stale setup callbacks cannot
  control the current generation.

Current status: **PASS**.

## PC protocol evidence

`tools/kum26_peer_test.py` passes 11 controlled endpoint tests covering framing,
oversized and truncated frames, identity pinning, exact v2 envelope fields,
malformed identity, delayed/duplicate responses, timeout, half-close, and Socket
disconnect behavior.

This is protocol software evidence only. It is not physical-device acceptance.

Current status: **11/11 PASS**.

## Two-device physical evidence

Raw two-device captures are retained outside Git under:

```text
C:\Users\kuma\AppData\Local\Temp\motointercom-kum26
```

Each accepted harness run binds its source commit, APK hash, two device serials,
installation-stable identities, pre/post database checks, device logs, and
service/audio state. Raw device logs and databases are not committed.

| Scenario | Accepted capture | Revision | Result |
| --- | --- | --- | --- |
| LAN, A requester / B responder | `artifacts\lan-a-requester` | `5825a8f` | PASS |
| LAN, B requester / A responder | `artifacts\lan-b-requester` | `5825a8f` | PASS |
| Wi-Fi Direct, A requester / B responder | `physical-20260717-094757-p2p-a-requester-retry` | `1438cb2` | PASS |
| Wi-Fi Direct, B requester / A responder | `artifacts\20260717-102458-p2p-b-requester` | `1438cb2` | PASS |
| Restart A | `artifacts\restart-a` | `5825a8f` | PASS |
| Restart B | `artifacts\20260717-105651-restart-b` | `1438cb2` | PASS |
| Disconnect initiated by A | LAN A and P2P A accepted captures | mixed | PASS |
| Disconnect initiated by B | LAN B and P2P B accepted captures | mixed | PASS |
| Background/lock-screen delayed Reject | [PR evidence comment 4988211942](https://github.com/g95809080-cmyk/moto-intercom/pull/2#issuecomment-4988211942) and KUM-26 attachments | fixed build | PASS |
| Planned-transport recovery | `artifacts\20260717-121512-network-recovery` | `be17580` | PASS |
| Database, APK, and identity integrity | all accepted captures; final recovery capture | `be17580` final | PASS |

Only the Wi-Fi Direct setup/recovery production path changed after the earlier
LAN/P2P/restart captures: `WifiDirectTunnel.kt` and the new
`WifiDirectSetupRecoveryGate.kt`. The current `be17580` recovery capture
revalidated initial Wi-Fi Direct discovery, verified
current-Socket identity, one accepted attempt, one PeerConnection on each phone,
WebRTC CONNECTED, transport loss cleanup, radio restoration, one fresh setup
generation, successful service request/discovery, and rediscovery of the same
stable peer. The full automated gate then passed at `be17580`.

The recovery capture closed as `Pass` at 2026-07-17T12:44:22+08:00. Its final
checks recorded:

```text
apk_match=true
identity_match=true
Device A database integrity=ok
Device B database integrity=ok
```

The Android 16 test-only setting `wifi_display_certification_on` was restored to
`1` after the final capture.

### Human audio evidence

- The accepted P2P A-requester retry records explicit bidirectional human
  listening confirmation and a call stable for more than 75 seconds.
- During final recovery closure, the user again confirmed the objective gate
  passed and intelligible bidirectional listening was heard.
- ADB recording/playback and VOX state are retained as objective pipeline
  evidence, but are not used as a substitute for the human listening result.

Current status: **PASS**.

## Superseded and non-passing captures

Failure and NotRun artifacts remain preserved and are not counted as passes:

- `physical-20260717-093621-p2p-a-requester`: failed because one peer left
  Connected before the requested stable interval;
- `artifacts\20260717-100358-p2p-b-requester`: NotRun because no attempt started;
- `artifacts\restart-b`: NotRun because the final reconnect was not completed;
- `artifacts\20260717-112221-network-recovery`: failed at `1438cb2` and exposed
  the radio-restore setup defect fixed by `be17580`;
- harness/commit smoke captures marked NotRun executed no product scenario.

Each required scenario has a later accepted capture listed in the passing matrix.

## Review evidence

Independent architecture review was fixed to:

```text
Base SHA: fb01c3c42f0ba294462532be5ac2b12a0252e62c
Head SHA: be17580219c1584805ee95450f71740e9e80e33c
Result: APPROVED
P0: 0
P1: 0
```

Verified boundaries include current-attempt/current-runtime checks, fail-closed
Socket identity handling, single product-state ownership, Accept-gated WebRTC,
P2P setup generation ownership, and cleanup after stale callbacks. This report
update changes no runtime behavior and does not require another architecture
review.

## Gate decision

| Gate | Decision |
| --- | --- |
| KUM-26 Exit Criteria | PASS |
| Known P0 / P1 | 0 / 0 |
| KUM-26 may close | YES after this report-only commit's PR CI succeeds |
| Sprint 2 implementation/evidence gate | PASS |
| Sprint 2 may close | NO - PR merge and post-merge `main` CI are still required |
| PR #2 may become Ready | Eligible after report CI, but explicit user approval is required |
| PR #2 may merge | NO without explicit user approval and final merge gate |
| KUM-27 may start | NO - Sprint 2 PR must merge and `main` CI must pass first |
| Production deployment | NO - independent Release Gate and explicit user approval are required |

PR #2 remains Draft. KUM-27 remains Todo. No merge, release, deployment, branch
deletion, signing, or production action is authorized by this report.

## Residual risk and trust boundary

- Accepted residual risk: the deferred three-simultaneous-physical-phone wireless
  topology described above.
- Current-Socket identity verification proves channel/target consistency but is
  not cryptographic authentication against an active same-network attacker.
- Neither limitation is represented as tested or eliminated.
