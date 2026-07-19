## Context

The current product plan prefers LAN at T0 and opens Wi-Fi Direct at T+5. A
targeted `WifiP2pManager.BUSY` callback is currently logged and retried only
inside `WifiDirectTunnel`; the Coordinator does not know that overlap is
unavailable and the LAN targeted lease remains active. Android exposes no
reliable public API that proves STA/P2P concurrency across OEM implementations.

KUM-31 must add a safe attempt-local fallback without inventing a permanent
device capability, changing target selection, or moving transport ownership out
of the existing Coordinator.

## Goals / Non-Goals

**Goals:**

- Convert an exact current P2P fallback `BUSY` into a typed overlap-unavailable
  event.
- Let the Coordinator make one sequential-switch decision for the same attempt.
- Retire LAN targeted work before retrying P2P while preserving target and T+10.
- Reject stale, duplicate, expired, wrong-plan, and post-candidate signals.
- Keep late retired-LAN callbacks from joining the attempt.
- Certify the behavior with deterministic tests and the reusable emulator gate.

**Non-Goals:**

- No persistent OEM/chipset capability database or unsupported public-API guess.
- No change to the T0/T+5/T+6/T+10 schedule when overlap works.
- No protocol, identity, TargetLock, database, UI, notification, WebRTC, Sprint
  4, signing, deployment, or production-release work.
- No claim that an emulator proves real OEM radio concurrency.

## Decisions

1. **Use an attempt-local BUSY signal, not a permanent capability flag.**
   `WifiDirectTunnel` reports overlap unavailable only when Android returns BUSY
   for the exact targeted P2P fallback in the current LAN-preferred plan.
   Treating BUSY conservatively may reduce optimization for one attempt, but it
   cannot connect to another target or extend the deadline.

2. **Keep the switch decision in the Coordinator.** The adapter emits a typed
   event carrying the immutable `ConnectionAttempt`. The Coordinator requires
   the exact owned/current attempt, an opened fallback, no terminal outcome, a
   live deadline, no prior switch, and no verified preferred control candidate.
   Service and adapters do not infer product state.

3. **Represent sequential mode by retiring the preferred transport in race
   bookkeeping.** The existing `TargetedTransportRace` records retired
   transports. Failure accounting excludes them, and a late requester channel
   on a retired transport is rejected before it can join the active attempt.

4. **Execute an ordered retire then retry.** The accepted decision emits
   `RetireTargetedTransport(LAN)` followed by
   `OpenTargetedTransport(WIFI_DIRECT)`. Service releases the exact LAN targeted
   lease and then retries the already planned fallback. Attempt ID, TargetLock,
   wire identity, plan, and total deadline are unchanged.

5. **Preserve a valid preferred channel.** If LAN already produced a verified
   requester control candidate, the Coordinator ignores the overlap signal and
   continues that path. Sequential retirement is only a recovery for a blocked
   fallback when no preferred control path is available.

6. **Use the existing evidence hierarchy.** JVM tests prove ownership and time;
   emulator instrumentation proves shared-network, NSD, synthetic PCM, fault
   recovery, and restart behavior. OEM/RF rows remain Release Candidate work.

## Risks / Trade-offs

- **A transient BUSY is treated as overlap unavailable.** -> The degradation is
  scoped to one attempt and keeps the same target/deadline; the next attempt may
  race normally again.
- **A retired LAN callback arrives after the switch.** -> Coordinator race state
  rejects the transport before channel adoption and Service closes the session.
- **Duplicate BUSY callbacks schedule repeated switches.** -> The retired set
  makes the decision first-wins and all repeats are rejected.
- **The total deadline expires during routing.** -> Coordinator checks the
  monotonic deadline before effects; adapters retain remaining-time guards.
- **P2P BUSY has another OEM-specific cause.** -> Sequential fallback is a safe
  lower-performance response; final OEM behavior remains deferred and explicit.

## Migration Plan

1. Strictly validate this Rasen contract.
2. Add deterministic Coordinator and pure routing-helper regressions.
3. Add the typed adapter event and ordered Service effects.
4. Run targeted JVM, full JVM, Lint, debug/test APK, and three-emulator gates.
5. Complete fixed-SHA review, CI, merge commit, green main CI, and Linear sync.

Rollback is a single revert of the KUM-31 merge commit. No data migration or
compatibility step is required.

## Open Questions

None.
