# KUM-29 Optimizing Winner Verification

Status: **AUTOMATED GATE PASSED - REVIEW AND CI PENDING**

Evidence state: 2026-07-19

## Bound revisions

- Repository: `g95809080-cmyk/moto-intercom`
- Certification branch: `feat/kum-29-optimizing-winner-commit`
- Certification base: `657d5264d0967259000359ccbc6a22bceb133ed4`
- Certification Head: pending delivery commit
- Runtime implementation PR: [#5](https://github.com/g95809080-cmyk/moto-intercom/pull/5)
- Runtime implementation source Head: `f96ba4d0a536b6bfc226d111c5a843cf622f1d75`
- Runtime implementation final PR Head: `1d2d4b6395d172b2765271dd167b33bc22462ac6`
- Runtime implementation merge: `657d5264d0967259000359ccbc6a22bceb133ed4`
- Main implementation CI: [29679007580](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/29679007580) - success
- Linear: KUM-8 In Progress; KUM-29 In Progress; KUM-30 Todo; KUM-31 Todo

The KUM-29 runtime behavior was already delivered by PR #5. This certification
change adds an issue-scoped Rasen contract and evidence only. The certification
branch has no diff under `app/src/` relative to its base.

## Architecture boundary

- `SessionOrchestrator` remains the only product-state writer.
- `SignalingControlCoordinator` owns the monotonic optimization milestone,
  selection phase, media-owner claim, terminal ordering, and cleanup decisions.
- `IntercomService` inspects exact current candidates, chooses from the supplied
  immutable cohort, executes effects, and never writes product state directly.
- Only the selected current channel can emit `StartWebRtc`.
- The one-second optimization window is capped by the immutable ten-second total
  attempt deadline; neither decision can rebase that deadline.
- No second Coordinator, deadline owner, media owner, protocol, transport,
  database, UI, notification, or release path is introduced.

## Exit Criteria mapping

| KUM-29 criterion | Implementation evidence | Deterministic evidence |
| --- | --- | --- |
| Fallback-first enters `OPTIMIZING` | `beginMediaSelection` creates `MediaOptimization` when no preferred channel is ready | `preferredArrivalDuringOptimizationWinsAndCleansFallback` |
| Preferred wins inside one second | Current same-attempt preferred channel advances selection immediately | `preferredArrivalDuringOptimizationWinsAndCleansFallback` advances 999 ms |
| Fallback wins at exact expiry | Monotonic milestone dispatch selects the bounded cohort once | `fallbackWinsExactlyAtOptimizationExpiryAndRecordsItsTransport` advances 1,000 ms |
| Total deadline remains authoritative | `optimizationAt` uses the minimum of window end and immutable deadline | `totalDeadlineBeatsAnOptimizationDecisionAtTheSameTimestamp` |
| One winner and one media owner | `mediaChannelSelected` rejects a second claim and records one `mediaOwnerChannelId` | `preferredArrivalDuringOptimizationWinsAndCleansFallback`; `duplicateOwnerAcceptIsIdempotent` |
| Only winner starts WebRTC | Responder starts media only after the owner `CONNECT_ACCEPT` send completes | `responderAcceptMustBeSentBeforeWebRtcStarts`; `failedAcceptSendNeverStartsWebRtc` |
| Loser cleanup is bounded | Losers receive reject/close effects and are removed from the active cohort | `preferredArrivalDuringOptimizationWinsAndCleansFallback`; `sentSupersededChannelIsRemovedFromTheActiveAttempt` |
| Single-success cleanup | Winner remains current while queued race milestones become stale | `preferredWinnerSuppressesTheQueuedFallbackMilestone`; `fallbackWinsExactlyAtOptimizationExpiryAndRecordsItsTransport` |
| All-failure cleanup | Final planned-path failure records one terminal outcome and aborts the attempt | `attemptFailsOnlyAfterEveryOpenedPlannedTransportFails` |
| Cancel/deadline cleanup | Terminal ordering clears candidates and invalidates the queued optimization decision | `cancelDuringOptimizationInvalidatesTheQueuedWinnerDecision`; `timeoutWinsOverLateTransportFailure` |

## Automated evidence

| Check | Result | Evidence |
| --- | --- | --- |
| KUM-29 targeted JVM | PASS | 63 tests; 3 suites; 0 failures, errors, or skipped |
| Full JVM gate | PASS | 230 tests; 34 suites; 0 failures, errors, or skipped |
| Lint | PASS | 0 Fatal, 0 Error, 34 existing warnings |
| Debug APK | PASS | `assembleDebug` |
| Android test APK | PASS | `assembleDebugAndroidTest` |
| Rasen strict validation | PASS | 1/1 |
| Three-emulator `all` matrix | PASS | `build/emulator-results/20260719-161937-all` |
| Emulator evidence | CAPTURED | `build/emulator-evidence/20260719-162021.zip` |
| Evidence SHA-256 | RECORDED | `0357E86A099DD4876C96FBF781554CF7CC04BA141F6C9D460139A3FD3E65E559` |
| Fixed-SHA architecture review | PENDING | Certification Base/Head will be bound after commit |
| Pull-request CI | PENDING | Required before Ready/merge |
| Main merge CI | PENDING | Required before KUM-29 Done |

The emulator matrix used only `emulator-5554`, `emulator-5556`, and
`emulator-5558`. The connected MI 6 was explicitly excluded.

## Release Candidate deferral

The following evidence is not claimed as passed and remains
`DEFERRED_TO_RELEASE_CANDIDATE`:

- OEM Wi-Fi Direct overlap and sequential fallback differences;
- real weak-LAN / healthy-P2P timing under RF distance and interference;
- Xiaomi and other OEM background or lock-screen restrictions;
- Bluetooth SCO routing;
- real microphone, speaker, hardware AEC, and human listening;
- long-duration power, thermal, and background-survival behavior.

These physical-only rows do not block KUM-29 development completion or its
intermediate merge. They remain production-release blockers until the final
Release Candidate acceptance is completed.

## Gate

Automated evidence is complete. The remaining gates are the atomic delivery
commit, Draft PR, PR CI, fixed-SHA read-only architecture review with P0=0 and
P1=0, merge commit, and green main CI.
