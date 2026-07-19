## Context

KUM-27 merged at `a31140c08b1eb36f53abc19b09eca45727a785d1` and
established one Coordinator, one current attempt, one immutable monotonic
10-second deadline, context-complete callbacks, bounded adapters, and one media
owner. The remaining single-transport assumptions are concentrated in
`ChannelPlan`, targeted-open effects, candidate admission, inbound plan
construction, and product `Connected.transport` derivation.

The current requester opens one transport and accepts the first current remote
`CONNECT_ACCEPT`. The responder selects immediately from the currently visible
same-transport cohort. `OPTIMIZING` exists as product vocabulary but is not
owned by the Coordinator. Service hosts only the total-deadline timer.

## Goals / Non-Goals

**Goals:**

- Add one ordered preferred/fallback plan without changing attempt identity,
  target, total deadline, or ownership.
- Open preferred at T0 and fallback exactly at T+5 when no winner exists.
- Wait at most one second when a responder sees fallback before preferred.
- Award one current verified channel and release every loser.
- Preserve deterministic cancel, deadline, replacement, recovery, and stale
  callback behavior.
- Pass JVM, emulator, CI, and fixed-SHA architecture gates while deferring
  physical-only evidence accurately.

**Non-Goals:**

- No second Coordinator, product-state writer, deadline owner, or media owner.
- No arbitrary N-transport scheduler or configurable race policy UI.
- No Signaling v2 version/envelope change.
- No target selection, pairing, identity, database, notification, permission,
  WebRTC engine, Bluetooth, audio-route, Sprint 4 recovery-policy, deployment,
  or production release change.

## Decisions

### 1. ChannelPlan becomes an ordered one-or-two value object

`ChannelPlan` stores `preferredTransport` and an optional distinct
`fallbackTransport`. `plannedTransports` is an immutable snapshot and candidate
admission uses membership rather than equality with the preferred transport.
The attempt remains immutable; opened/failed candidates and winner are
Coordinator runtime state, not fields copied into the attempt.

Alternative rejected: a mutable set on `ConnectionAttempt`. It would allow
deadline/target-equivalent copies with different authority and recreate the
KUM-27 dual-owner risk.

### 2. The existing Coordinator remains the sole race owner

`SignalingControlCoordinator` evolves in place. A small exact-attempt transport
race record tracks opened and failed transports for the current outbound or
recovery attempt. No Service Boolean decides whether fallback may open.

### 3. Attempt milestones are explicit immutable tasks

Add immutable fallback and media-optimization milestone values containing the
attempt, exact monotonic timestamp, and required transport or wire request. A
single Service-side scheduler may host multiple milestone kinds concurrently,
but every callback is returned to the Coordinator as an event and revalidated.
The Service cannot infer, move, or extend a milestone.

### 4. T0/T+5 timing is fixed and total-deadline bounded

For a dual outbound/recovery attempt, creation emits preferred open plus one
fallback milestone at start+5 seconds. The immutable total deadline stays at
start+10 seconds. A definitive preferred open failure is recorded but does not
move fallback earlier; single-plan failure still terminates immediately. When
all planned paths are failed and no live candidate remains, the attempt fails.

### 5. Existing preferredTransportHint carries dual intent

The message schema already contains an optional preference hint. KUM-28 sends
it only for a dual plan; single-plan requests send null. With only LAN and
Wi-Fi Direct supported, a responder receiving a hint constructs a dual plan
with the hinted transport first and the other transport second. This avoids a
Signaling v2 version or field change while preserving strict parsing.

### 6. Responder selection owns the one-second optimization window

The responder is the side that sends the one authoritative `CONNECT_ACCEPT`.
If preferred is selection-ready, it selects immediately. If fallback is the
only ready candidate, the Coordinator moves product state to `OPTIMIZING` and
schedules a decision at `min(now+1s, totalDeadline)`. A preferred candidate
arriving during the window is selected immediately; otherwise fallback is
selected at the milestone. The requester accepts that one current response and
does not add a second independent optimization policy.

### 7. Winner transport is product evidence, not attempt mutation

`IntercomState.Connected` records the actual selected candidate transport.
`ConnectionAttempt.channelPlan` remains the original immutable plan and is
preserved by recovery. Service media startup uses the selected candidate
transport to release the losing physical path.

### 8. Effects specify the exact transport

`OpenTargetedTransport` carries both attempt and transport. LAN/P2P adapters
accept the call only when that transport belongs to the immutable plan and the
remaining budget is positive. Existing adapter attempt/generation context is
preserved. Service starts recovery preferred transport only after the new
runtime resources are current; the fallback milestone remains Coordinator
owned.

### 9. Winner and terminal paths invalidate race work

On winner, Service cancels exact attempt milestones before media startup. On
abort, recovery restart, stop, or runtime rollover it cancels the runtime's
milestones. Coordinator validation remains authoritative if a callback was
already queued. Late loser cleanup may close old physical resources but cannot
open, retry, select, start media, or clear replacement state.

### 10. Validation follows the accepted development strategy

Start with domain/scheduler and Coordinator fake-clock tests. Then run all JVM
tests, Lint, debug/test APK assembly, strict Rasen validation, the reusable
three-emulator matrix, CI, and fixed-SHA read-only architecture review.
Emulator Wi-Fi Direct limitations are covered by fake transport/callback tests;
OEM/radio/acoustic checks remain in the Release Candidate queue.

## Risks / Trade-offs

- [A non-null preference hint previously appeared on single plans] -> KUM-28
  changes emission and reception atomically in one app release and adds strict
  protocol regression coverage; the envelope and parser remain unchanged.
- [Fallback failure could terminate a viable preferred path] -> Coordinator
  tracks failure per planned transport and live candidate set.
- [Preferred hard failure could accidentally start fallback early] -> Only the
  exact T+5 milestone may emit fallback open; tests cover T+5-1/T+5/T+5+1.
- [Optimization timer could outlive cancel/replacement] -> Exact milestone
  identity, Service cancellation, and Coordinator current-attempt checks.
- [Fallback winner could be reported as preferred] -> Connected state derives
  transport from the current media-owner channel, never from plan preference.
- [Losing adapter cleanup could remove winner resources] -> Service cleanup is
  transport- and candidate-context-specific; old cleanup cannot mutate the
  Coordinator winner.
- [Emulator cannot prove OEM concurrent Wi-Fi Direct] -> deterministic fake
  adapter/callback coverage plus explicit Release Candidate physical rows.

## Migration Plan

1. Commit and validate proposal/spec/design/tasks.
2. Add ordered ChannelPlan, attempt milestones, scheduler, and deterministic
   domain/timer tests without wiring product behavior.
3. Add Coordinator T0/T+5 transport state and per-transport failure ordering.
4. Add responder fallback-first optimization and unique selection behavior.
5. Wire exact Service effects, adapter membership checks, actual winner
   transport, and milestone cancellation.
6. Run targeted tests, full JVM/Lint/assemble/Rasen gates, and three-emulator
   regression; create Draft PR.
7. Run fixed-SHA architecture review, remediate P0/P1, update the single Sprint
   3 evidence report and Linear, then merge with a merge commit after green CI.

Rollback is the KUM-28 commit range back to merge commit `a31140c`. There is no
schema, protocol-version, persisted-data, identity, pairing, permission, or
dependency migration.

## Open Questions

None. The approved product policy is LAN preferred when both current Presence
transports are available, Wi-Fi Direct fallback at T+5, and at most one second
of fallback-first optimization.

