## Why

KUM-27 through KUM-29 established one attempt owner, a bounded transport race,
and one media winner, but KUM-30 still needs issue-scoped proof that cancellation,
simultaneous requests, duplicate physical/control events, and stale media frames
cannot create a second WebRTC session or retain old resources.

## What Changes

- Certify immediate current-attempt cancellation and complete candidate cleanup.
- Certify the canonical `WireRequestKey` glare rule across physical Socket roles.
- Stress the serialized Coordinator mailbox with conflicting winner, duplicate
  request, duplicate send-completion, and repeated cancellation events.
- Prove stale-attempt OFFER, ANSWER, and ICE frames fail before reader handoff and
  cannot reach the current media context.
- Add only a root runtime fix if a new deterministic regression first exposes a
  KUM-30 defect; otherwise preserve current Android behavior.
- Reuse the automated and three-emulator development gate. Hardware-only evidence
  remains `DEFERRED_TO_RELEASE_CANDIDATE`.

## Capabilities

### New Capabilities

- `attempt-race-safety`: Defines cancellation, deterministic glare arbitration,
  duplicate idempotency, unique media ownership, stale media-frame exclusion,
  concurrent mailbox safety, and bounded cleanup for KUM-30.

### Modified Capabilities

None. The approved KUM-27 ownership, KUM-28 transport-race, and KUM-29 winner
contracts remain unchanged.

## Impact

- Deterministic tests for `SignalingControlCoordinator`, `SessionOrchestrator`,
  `SignalingSessionV2`, pinned envelope identity, and exact media context.
- Sprint 3 verification evidence and KUM-30 delivery traceability.
- No protocol schema, database, target-selection, transport schedule, UI,
  dependency, production signing, deployment, or release-path change.
