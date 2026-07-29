## Context

KUM-35 correctly gives `SessionOrchestrator` and
`SignalingControlCoordinator` sole authority over explicit disconnect. The
Service writes `DISCONNECT` on a worker and posts its result to the main thread.
Before KUM-41, main-thread delivery first required that the original
`SignalingSessionV2` still be mapped and open. A fast peer close, or the writer's
own failure close, therefore caused an early return and silently discarded the
only terminal event. The Coordinator remained in `TERMINATING` while product
state and UI remained `CONNECTED`.

## Goals / Non-Goals

**Goals:**

- Deliver exactly one immutable send success or failure event despite a
  concurrent session close.
- Preserve existing Coordinator identity validation and exact-attempt cleanup.
- Restore `CONNECTED -> DISCOVERING -> reconnect` on two devices without full
  runtime restart.

**Non-Goals:**

- No new disconnect state, timeout policy, protocol message, or Service-owned
  terminal flag.
- No change to glare arbitration, recovery fallback, deadline, target,
  discovery identity, nickname, database, or audio ownership.
- No T1, T4, or full T6 remediation.

## Decisions

1. **Freeze the result before posting to main.** Convert `Result<Unit>` into the
   exact immutable `SessionEvent` inside the writer callback. This removes the
   time-of-check race. The alternative—keeping the existing session-open
   prerequisite—cannot distinguish a valid fast peer close from a stale write
   and reproduces the deadlock.
2. **Always dispatch the frozen event.** Service may still close/remove the old
   physical session, but it does not suppress the event. The Coordinator already
   validates runtime, attempt, channel, phase, and ownership, so adding another
   mutable Service gate would duplicate authority.
3. **Reuse existing exact cleanup.** `ReleaseActiveSessionAndContinueDiscovery`
   continues to cancel attempt schedules, close exact signaling/media, release
   targeted LAN/P2P ownership, and preserve runtime discovery/audio owners. No
   adapter rebuild is added.
4. **Test the race at both seams.** Pure mapping tests prove success and failure
   remain representable after closure; existing Coordinator tests prove both
   terminal events converge to one narrow cleanup and reject stale callbacks.
   Current-Head device validation proves disconnect, discovery continuity, and
   reverse reconnect.

## Risks / Trade-offs

- A completion can arrive after a replacement attempt. → Existing immutable
  Coordinator identity gates reject it; tests retain stale-event coverage.
- A socket write could block indefinitely before producing any result. → The
  observed failure and this change concern a produced result discarded during
  main-thread delivery. If physical retest shows a missing writer result rather
  than the closure race, stop and add a separately reviewed bounded-send policy.
- OEM Wi-Fi Direct teardown can remain unreliable. → Validate LAN first, record
  Wi-Fi Direct results separately, and never claim an unpassed OEM row.

## Migration Plan

1. Add failing completion mapping and existing disconnect lifecycle tests.
2. Remove the Service early-return suppression and dispatch the frozen event.
3. Run full Gradle, CI, fixed-SHA architecture review, and two-device LAN
   disconnect/reverse reconnect.
4. Merge by merge commit and retain the remote branch.

Rollback is a revert of the KUM-41 merge commit. There is no protocol or data
migration.

## Open Questions

None for this bounded completion-race fix.
