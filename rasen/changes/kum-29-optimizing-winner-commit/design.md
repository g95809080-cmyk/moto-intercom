## Context

PR #5 already implemented the KUM-29 behavior while delivering KUM-28. The
current coordinator uses a monotonic `AttemptMilestone.MediaOptimization`,
serializes winner selection through `SessionOrchestrator`, and delegates channel
inspection and resource effects to `IntercomService`. KUM-29 therefore requires
an issue-scoped contract and independent evidence, not a second implementation.

## Goals / Non-Goals

**Goals:**

- Prove the fallback-first responder path enters `OPTIMIZING` for at most one
  second and remains bounded by the immutable attempt deadline.
- Prove preferred arrival during the window wins deterministically, otherwise the
  fallback candidate is selected at expiry.
- Prove the first valid media-owner claim is immutable and starts at most one
  WebRTC session.
- Prove loser and terminal-path cleanup for dual-success, single-success,
  all-failure, cancellation, stale milestone, and deadline ordering.
- Preserve traceability to the implementation merged in PR #5.

**Non-Goals:**

- No runtime, protocol, transport, database, UI, notification, or release code
  changes.
- No KUM-30 glare/cancellation expansion beyond regression evidence already
  required to ensure KUM-29 remains safe.
- No physical OEM, RF, Bluetooth, acoustic, power, or thermal acceptance.

## Decisions

1. **Reuse the existing coordinator implementation.**
   `SignalingControlCoordinator.beginMediaSelection` already creates the bounded
   monotonic milestone, `mediaChannelSelected` records one owner, and
   `signalingMessageSent` is the only responder transition that emits
   `StartWebRtc`. Reimplementation would create duplicate ownership risk.

2. **Treat `SessionOrchestrator` dispatch as the serialization boundary.**
   `IntercomService` computes an eligible channel from an immutable selection
   cohort and sends the result back as `MediaChannelSelected`; it does not write
   product state or independently start media.

3. **Use existing deterministic JVM tests as executable evidence.**
   The tests already exercise 999 ms, exact 1,000 ms, exact-deadline precedence,
   duplicate winner rejection, winner transport persistence, reject-before-close,
   and full attempt cleanup. The KUM-29 delivery adds a verification map rather
   than duplicating those scenarios in another fixture.

4. **Keep physical-only checks deferred.**
   Real OEM concurrency, RF, and acoustic behavior remain
   `DEFERRED_TO_RELEASE_CANDIDATE`; automated semantics are the development gate.

## Risks / Trade-offs

- **Risk: Issue traceability is weaker because implementation predates this PR.**
  → Bind the report to PR #5 source Head, merge SHA, exact test methods, and the
  fixed Base/Head review for this certification PR.
- **Risk: Documentation-only drift could misstate behavior.**
  → Run targeted and full automated gates against the current branch and require
  a read-only architecture review with P0=0 and P1=0.
- **Risk: Physical cleanup timing differs on OEM hardware.**
  → Keep those rows explicitly deferred and preserve them in the Release
  Candidate physical plan.

## Migration Plan

1. Add and strictly validate the KUM-29 Rasen contract.
2. Add the issue-scoped verification map.
3. Run targeted JVM tests, full unit tests, Lint, debug builds, and emulator
   regression where applicable.
4. Review fixed Base/Head, merge with a merge commit after green CI, then require
   green main CI before marking KUM-29 Done.

Rollback is a single revert of the certification merge commit; runtime behavior
is unchanged because this change adds only planning and verification artifacts.

## Open Questions

None.
