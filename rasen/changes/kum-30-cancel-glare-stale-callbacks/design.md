## Context

The merged Coordinator already serializes terminal and media-owner decisions in
the `SessionOrchestrator` mailbox. `SignalingSessionV2` pins one wire attempt to
each Socket before its reader starts, and `IntercomService` admits media only
through an exact `ConnectionCandidateContext`. KUM-30 should certify these shared
boundaries and repair only a deterministic failure, not add another owner.

## Goals / Non-Goals

**Goals:**

- Bind every KUM-30 Exit Criterion to production code and a deterministic test.
- Prove cancellation closes all candidates and invalidates queued work.
- Prove simultaneous requests choose one direction independent of Socket role.
- Prove duplicate and concurrent callbacks create one media owner and no leaks.
- Prove old OFFER, ANSWER, and ICE frames fail before current media dispatch.

**Non-Goals:**

- No new Coordinator, product-state writer, deadline owner, signaling schema, or
  WebRTC engine.
- No KUM-31 physical acceptance, Sprint 4 recovery policy, database migration,
  UI redesign, signing, deployment, or production release.

## Decisions

1. **Use the existing mailbox as the concurrency boundary.** Submit conflicting
   events from multiple JVM coroutines and assert resulting effects and final
   ownership. A separate lock or concurrent Coordinator would create dual-owner
   risk without improving the serialized product decision.

2. **Keep canonical request-key glare arbitration.** Both peers see the same two
   requester-oriented keys and compare canonical UUID bytes. This yields the same
   winner regardless of physical opener/acceptor role; arrival order only affects
   when the already-determined result is processed.

3. **Test stale media at the earliest trust boundary and the current-owner gate.**
   Reader tests inject old-attempt OFFER, ANSWER, and ICE envelopes and require
   pinned identity rejection before callback handoff. Existing exact-candidate
   tests cover replacement, target, role, channel, and owner mismatches.

4. **Prove complete cancellation with multiple candidates.** A deterministic
   Coordinator test cancels before winner claim, expects one close per candidate
   plus one attempt abort, then replays late events and expects no side effects.

5. **Change runtime code only on a failing regression.** If all new tests pass on
   the current implementation, KUM-30 is a certification/test change. If one
   fails, fix the smallest shared owner or trust-boundary root cause and rerun all
   gates.

## Risks / Trade-offs

- **Risk: A concurrency test becomes scheduler-order dependent.** -> Assert
  invariants and counts, not which valid channel happens to enter the mailbox
  first.
- **Risk: A protocol-phase error masks stale identity rejection.** -> Assert the
  pinned-identity failure and zero reader handoffs for each media frame type.
- **Risk: Cleanup evidence observes queued effects too early.** -> Await each
  submitted event and consume the exact bounded effect set before final checks.
- **Risk: Emulator evidence overclaims Wi-Fi Direct hardware behavior.** -> Use
  fakes for ownership/protocol semantics and keep hardware rows deferred.

## Migration Plan

1. Strictly validate the KUM-30 Rasen contract.
2. Add deterministic cancellation, stale-media, and concurrent-mailbox tests.
3. Apply a runtime fix only if a regression exposes a current-scope defect.
4. Run targeted JVM, full JVM, Lint, debug/test APK, and three-emulator gates.
5. Complete fixed-SHA review, CI, merge-commit delivery, and Linear evidence.

Rollback is a single revert of the KUM-30 merge commit.

## Open Questions

None.
