# KUM-29 Optimizing Winner Verification

Status: **MERGED - MAIN CI GREEN**

Evidence state: 2026-07-19

## Bound revisions

- Repository: `g95809080-cmyk/moto-intercom`
- Certification branch: `feat/kum-29-optimizing-winner-commit`
- Certification base: `657d5264d0967259000359ccbc6a22bceb133ed4`
- Initial certification Head: `554ead24b1f53a8fc663ad6a3afbf385725d1b38`
- Round-1 remediation Head: `e51e696b6162fc7531fc4f0ac63674d6ba9f6993`
- Round-2 remediation Head: `678c89ee7688b7b74110efc325da133cdb6c0f63`
- Reviewed source Head: `678c89ee7688b7b74110efc325da133cdb6c0f63`
- Final evidence commit: `687b9d823f1f715e5495d6625a72cf0c5f662198`
- Pull request: [#6](https://github.com/g95809080-cmyk/moto-intercom/pull/6)
- Merge commit: `6f1839748307cb6b62d25d9fc5d613d679f9ffad`
- Final PR CI: [29681601138](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/29681601138) - success
- Main merge CI: [29681780079](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/29681780079) - success
- Runtime implementation PR: [#5](https://github.com/g95809080-cmyk/moto-intercom/pull/5)
- Runtime implementation source Head: `f96ba4d0a536b6bfc226d111c5a843cf622f1d75`
- Runtime implementation final PR Head: `1d2d4b6395d172b2765271dd167b33bc22462ac6`
- Runtime implementation merge: `657d5264d0967259000359ccbc6a22bceb133ed4`
- Main implementation CI: [29679007580](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/29679007580) - success
- Linear: KUM-8 In Progress; KUM-29 Done; KUM-30 In Progress; KUM-31 Todo

The authoritative ongoing Sprint 3 evidence index is
[`sprint-3-final-verification.md`](sprint-3-final-verification.md). This file
retains KUM-29's detailed fixed-SHA review history.

PR #5 delivered most KUM-29 runtime behavior. The first fixed-SHA certification
review found two P1 gaps, both within KUM-29 scope: exact optimization expiry was
mailbox-order dependent, and loser closure had no deadline independent of a
signaling writer callback. The second review then found that an exact owner
retransmission could be misclassified as a loser and that repeated rejects could
move the close deadline later. This branch now hardens all four boundaries.

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
- A request repeated on an exact channel already in the active attempt is
  idempotent and cannot schedule owner cleanup.
- Repeated loser rejects preserve the earliest exact-key close deadline.
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
| Current owner request is idempotent | Exact channels already in the active attempt bypass loser classification | `duplicateRequestOnTheCurrentOwnerIsIdempotent` |
| Only winner starts WebRTC | Responder starts media only after the owner `CONNECT_ACCEPT` send completes | `responderAcceptMustBeSentBeforeWebRtcStarts`; `failedAcceptSendNeverStartsWebRtc` |
| Loser cleanup is bounded | Normal reject completion closes immediately; an independent exact-context watchdog force-closes by one second and preserves the earliest duplicate deadline | `preferredArrivalDuringOptimizationWinsAndCleansFallback`; `ControlChannelCloseDeadlineSchedulerTest` |
| Single-success cleanup | Winner remains current while queued race milestones become stale | `preferredWinnerSuppressesTheQueuedFallbackMilestone`; `fallbackWinsExactlyAtOptimizationExpiryAndRecordsItsTransport` |
| All-failure cleanup | Final planned-path failure records one terminal outcome and aborts the attempt | `attemptFailsOnlyAfterEveryOpenedPlannedTransportFails` |
| Cancel/deadline cleanup | Terminal ordering clears candidates and invalidates the queued optimization decision | `cancelDuringOptimizationInvalidatesTheQueuedWinnerDecision`; `timeoutWinsOverLateTransportFailure` |

## Automated evidence

| Check | Result | Evidence |
| --- | --- | --- |
| KUM-29 targeted JVM | PASS | 68 tests; 4 suites; 0 failures, errors, or skipped |
| Full JVM gate | PASS | 235 tests; 35 suites; 0 failures, errors, or skipped |
| Lint | PASS | 0 Fatal, 0 Error, 34 existing warnings |
| Debug APK | PASS | `assembleDebug` |
| Android test APK | PASS | `assembleDebugAndroidTest` |
| Rasen strict validation | PASS | 1/1 |
| Pre-remediation emulator matrix | HISTORICAL PASS | `build/emulator-results/20260719-161937-all`; not final evidence after runtime edits |
| Round-1 remediation emulator matrix | HISTORICAL PASS | `build/emulator-results/20260719-165130-all`; superseded by round 2 |
| Round-2 remediation emulator matrix | PASS | `build/emulator-results/20260719-171106-all` |
| Round-2 remediation evidence | CAPTURED | `build/emulator-evidence/20260719-171153.zip` |
| Round-2 remediation SHA-256 | RECORDED | `11FBAB8D600B1D065C12FC0BCEA62787113A2F745C675E60609BDB6AD7AA916F` |
| Fixed-SHA architecture review round 1 | REQUEST CHANGES | P0=0, P1=2 at `554ead24` |
| Fixed-SHA architecture review round 2 | REQUEST CHANGES | P0=1, P1=1 at `e51e696b` |
| Fixed-SHA architecture review round 3 | APPROVED | P0=0, P1=0 at `678c89ee` |
| Initial PR-head CI | PASS | run `29679762746` at `554ead24`; final Head CI still required |
| Round-1 remediation CI | PASS | run `29680625383` at `e51e696b` |
| Current-head CI | PASS | run `29681237026` at `678c89ee` |
| Final PR CI | PASS | run `29681601138` at `687b9d8` |
| Main merge CI | PASS | run `29681780079` at `6f18397` |

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

The round-2 P0/P1 fixes, JVM/build gate, fresh three-emulator evidence, fixed-SHA
round-3 review, final PR CI, merge commit, green main CI, and Linear evidence are
complete. KUM-29 is Done; KUM-30 is the active Sprint 3 issue.
