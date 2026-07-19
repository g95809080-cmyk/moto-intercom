# KUM-29 Optimizing Winner Verification

Status: **P1 REMEDIATED - COMMIT, CI, AND RE-REVIEW PENDING**

Evidence state: 2026-07-19

## Bound revisions

- Repository: `g95809080-cmyk/moto-intercom`
- Certification branch: `feat/kum-29-optimizing-winner-commit`
- Certification base: `657d5264d0967259000359ccbc6a22bceb133ed4`
- Initial certification Head: `554ead24b1f53a8fc663ad6a3afbf385725d1b38`
- Remediation Head: pending remediation commit
- Runtime implementation PR: [#5](https://github.com/g95809080-cmyk/moto-intercom/pull/5)
- Runtime implementation source Head: `f96ba4d0a536b6bfc226d111c5a843cf622f1d75`
- Runtime implementation final PR Head: `1d2d4b6395d172b2765271dd167b33bc22462ac6`
- Runtime implementation merge: `657d5264d0967259000359ccbc6a22bceb133ed4`
- Main implementation CI: [29679007580](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/29679007580) - success
- Linear: KUM-8 In Progress; KUM-29 In Progress; KUM-30 Todo; KUM-31 Todo

PR #5 delivered most KUM-29 runtime behavior. The first fixed-SHA certification
review found two P1 gaps, both within KUM-29 scope: exact optimization expiry was
mailbox-order dependent, and loser closure had no deadline independent of a
signaling writer callback. This branch now hardens those boundaries.

## Architecture boundary

- `SessionOrchestrator` remains the only product-state writer.
- `SignalingControlCoordinator` owns the monotonic optimization milestone,
  selection phase, media-owner claim, terminal ordering, and cleanup decisions.
- `IntercomService` inspects exact current candidates, chooses from the supplied
  immutable cohort, executes effects, and never writes product state directly.
- Selection cohorts are frozen once optimization expires or selection starts;
  preferred arrival at exact expiry cannot replace the fallback candidate.
- Every superseded channel has an exact runtime/attempt/channel monotonic close
  deadline independent of reject-send completion.
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
| Fallback wins at exact expiry | The cohort freezes at expiry and late preferred events are rejected regardless of mailbox order | `fallbackWinsExactlyAtOptimizationExpiryAndRecordsItsTransport`; `preferredAtOptimizationExpiryCannotJoinTheFrozenFallbackCohort` |
| Total deadline remains authoritative | Owner selection rechecks the immutable deadline before claim | `totalDeadlineBeatsAnOptimizationDecisionAtTheSameTimestamp`; `mediaSelectionAtTheTotalDeadlineCannotClaimAnOwner` |
| One winner and one media owner | `mediaChannelSelected` rejects a second claim and records one `mediaOwnerChannelId` | `preferredArrivalDuringOptimizationWinsAndCleansFallback`; `duplicateOwnerAcceptIsIdempotent` |
| Only winner starts WebRTC | Responder starts media only after the owner `CONNECT_ACCEPT` send completes | `responderAcceptMustBeSentBeforeWebRtcStarts`; `failedAcceptSendNeverStartsWebRtc` |
| Loser cleanup is bounded | Normal reject completion closes immediately; an independent exact-context watchdog force-closes by one second | `preferredArrivalDuringOptimizationWinsAndCleansFallback`; `ControlChannelCloseDeadlineSchedulerTest` |
| Single-success cleanup | Winner remains current while queued race milestones become stale | `preferredWinnerSuppressesTheQueuedFallbackMilestone`; `fallbackWinsExactlyAtOptimizationExpiryAndRecordsItsTransport` |
| All-failure cleanup | Final planned-path failure records one terminal outcome and aborts the attempt | `attemptFailsOnlyAfterEveryOpenedPlannedTransportFails` |
| Cancel/deadline cleanup | Terminal ordering clears candidates and invalidates the queued optimization decision | `cancelDuringOptimizationInvalidatesTheQueuedWinnerDecision`; `timeoutWinsOverLateTransportFailure` |

## Automated evidence

| Check | Result | Evidence |
| --- | --- | --- |
| KUM-29 targeted JVM | PASS | 67 tests; 4 suites; 0 failures, errors, or skipped |
| Full JVM gate | PASS | 234 tests; 35 suites; 0 failures, errors, or skipped |
| Lint | PASS | 0 Fatal, 0 Error, 34 existing warnings |
| Debug APK | PASS | `assembleDebug` |
| Android test APK | PASS | `assembleDebugAndroidTest` |
| Rasen strict validation | PASS | 1/1 |
| Pre-remediation emulator matrix | HISTORICAL PASS | `build/emulator-results/20260719-161937-all`; not final evidence after runtime edits |
| Post-remediation emulator matrix | PASS | `build/emulator-results/20260719-165130-all` |
| Post-remediation evidence | CAPTURED | `build/emulator-evidence/20260719-165211.zip` |
| Post-remediation SHA-256 | RECORDED | `2F94B25AE09E8E32784B9CAEA7DFD6FA19F65EF2F8DD5CC81B0CF21C0C3280A2` |
| Fixed-SHA architecture review round 1 | REQUEST CHANGES | P0=0, P1=2 at `554ead24` |
| Fixed-SHA architecture re-review | PENDING | Remediation Base/Head will be bound after commit |
| Initial PR-head CI | PASS | run `29679762746` at `554ead24`; final Head CI still required |
| Main merge CI | PENDING | Required before KUM-29 Done |

All emulator matrices use emulator serials only. The connected MI 6 remains
explicitly excluded.

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

The P1 fixes, JVM/build gate, and fresh three-emulator evidence are complete. The
remaining gates are the remediation commit and push, final PR-head CI, fixed-SHA
read-only re-review with P0=0 and P1=0, merge commit, and green main CI.
