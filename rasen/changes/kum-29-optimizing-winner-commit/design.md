## Context

PR #5 implemented most KUM-29 behavior while delivering KUM-28. The Coordinator
uses a monotonic `AttemptMilestone.MediaOptimization`, serializes winner selection
through `SessionOrchestrator`, and delegates physical effects to
`IntercomService`. Fixed-SHA review found that exact expiry still admitted a
preferred channel according to mailbox order and that a blocked reject writer
could keep a loser channel open indefinitely.

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

- No protocol schema, transport algorithm, database, UI, notification, or release
  code changes.
- No KUM-30 glare/cancellation expansion beyond regression evidence already
  required to ensure KUM-29 remains safe.
- No physical OEM, RF, Bluetooth, acoustic, power, or thermal acceptance.

## Decisions

1. **Harden the existing Coordinator rather than adding another owner.**
   `handleDuplicateActiveRequest` admits a preferred candidate only while the
   monotonic clock is strictly before the optimization milestone. Once selection
   starts, its cohort is frozen. `mediaChannelSelected` rechecks the immutable
   total deadline immediately before claiming an owner.

2. **Treat `SessionOrchestrator` dispatch as the serialization boundary.**
   `IntercomService` computes an eligible channel from an immutable selection
   cohort and sends the result back as `MediaChannelSelected`; it does not write
   product state or independently start media.

3. **Keep reject-before-close with an independent physical cleanup bound.**
   A superseded-channel reject schedules an exact runtime/attempt/channel close
   deadline at one monotonic second. Normal send completion still closes through
   the existing Coordinator event. If the writer blocks, the Service closes the
   exact Socket and reports send failure so Coordinator state is cleaned. The
   scheduler is separate from race milestones, so starting WebRTC cannot cancel
   loser cleanup.

4. **Add deterministic boundary evidence.**
   JVM tests cover preferred at 999 ms, preferred at exact 1,000 ms, frozen
   selection cohorts, owner claim at the total deadline, close-deadline
   replacement, exact-channel cancellation, and runtime cancellation.

5. **Keep physical-only checks deferred.**
   Real OEM concurrency, RF, and acoustic behavior remain
   `DEFERRED_TO_RELEASE_CANDIDATE`; automated semantics are the development gate.

## Risks / Trade-offs

- **Risk: A close watchdog could affect a replacement channel.**
  → Bind every deadline to exact runtime, attempt, and channel identity; cancel it
  on normal close and invalidate replacement callbacks deterministically.
- **Risk: Reject delivery can still block at the peer.**
  → Preserve a full second for normal delivery, then prefer local resource safety;
  a late writer callback cannot reclaim the closed channel.
- **Risk: Documentation-only drift could misstate behavior.**
  → Run targeted and full automated gates against the current branch and require
  a read-only architecture review with P0=0 and P1=0.
- **Risk: Physical cleanup timing differs on OEM hardware.**
  → Keep those rows explicitly deferred and preserve them in the Release
  Candidate physical plan.

## Migration Plan

1. Add and strictly validate the KUM-29 Rasen contract.
2. Apply strict optimization-expiry guards and the bounded loser-close watchdog.
3. Add deterministic scheduler and Coordinator boundary tests.
4. Run targeted JVM tests, full unit tests, Lint, debug builds, and the emulator
   regression.
5. Re-review fixed Base/Head, merge with a merge commit after green CI, then
   require green main CI before marking KUM-29 Done.

Rollback is a single revert of the KUM-29 merge commit.

## Open Questions

None.
