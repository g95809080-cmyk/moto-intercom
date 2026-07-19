## Context

The existing Coordinator creates a fresh recovery attempt with the prior
attempt's complete `ChannelPlan`, then uses the same T+5 fallback delay as a
normal connection. `IntercomState.Connected` already records the actual winning
transport, but recovery ignores it. A signaling-loss restart also waits an
additional 1.5 seconds after mandatory cleanup before recreating adapters.

KUM-33 must make recovery prefer the last successful transport for a monotonic
three-second window while retaining the KUM-32 TargetLock, KUM-27 immutable
attempt deadline, KUM-28/29 winner rules, KUM-31 overlap handling, and KUM-37
hot audio ownership.

## Goals / Non-Goals

**Goals:**

- Create a fresh recovery plan ordered by the actual connected transport.
- Start preferred recovery immediately when existing or rebuilt adapters are ready.
- Open the alternate at the immutable recovery T+3 boundary.
- Preserve the same target, transport set, attempt identity, and T+10 deadline.
- Keep stale readiness, milestones, callbacks, and prior attempts fail-closed.
- Prove the behavior with deterministic clocks/fakes and reusable emulator/CI gates.

**Non-Goals:**

- No repeated-failure counter or `RESETTING` policy (KUM-34).
- No active-disconnect behavior (KUM-35) or final Sprint matrix closure (KUM-36).
- No Signaling v2, identity, pairing, database, UI, notification, WebRTC/audio
  ownership, dependency, signing, deployment, or release change.
- No claim that emulators prove OEM radio/RF timing.

## Decisions

1. **Derive a new recovery order from `Connected.transport`.** The Coordinator
   creates a new immutable `ChannelPlan` whose preferred transport is the actual
   winner and whose optional fallback is the other transport already present in
   the connected attempt's plan. This preserves the available set and target
   while superseding only the old plan order. Presence and Service never choose
   the recovery target or transport order.

2. **Use a recovery-specific T+3 delay inside the existing race owner.** Normal
   attempts retain T+5. `TargetedTransportRace` calculates a recovery milestone
   from the recovery attempt's immutable start (`deadline - attemptTimeout`) and
   never copies or rebases the deadline.

3. **Report rebuilt readiness per transport.** A signaling disconnect must tear
   down physical resources before reuse. Service rebuilds only the adapters in
   the immutable recovery plan. LAN reports ready after its synchronous start;
   Wi-Fi Direct reports ready only after startup group cleanup and DNS-SD service
   discovery setup complete. Each event carries the exact attempt and transport.
   The Coordinator opens the preferred as soon as its adapter is ready. If T+3
   arrives before the alternate is ready, the race records that fallback is due
   and opens it immediately when that adapter later reports ready. This avoids
   an implicit Service timing policy, prevents startup cleanup from being
   invalidated by a premature targeted open, and does not block LAN on Wi-Fi
   Direct initialization.

4. **Keep media-only recovery on the direct path.** When adapters remain valid,
   the Coordinator emits the preferred open and T+3 schedule immediately. No
   second live Coordinator, adapter, deadline owner, or media owner is added.

5. **Remove only the recovery restart backoff.** Mandatory Socket/LAN/P2P
   cleanup still completes first. Recovery then reports readiness with zero
   extra delay; ordinary terminal-attempt cleanup keeps the existing 1.5-second
   discovery backoff. Cleanup elapsed time consumes the same T+3/T+10 clocks.

6. **Reuse existing stale and winner gates.** Per-transport readiness and
   milestones require exact attempt equality, membership in the immutable plan,
   current `RECOVERING` state, a live deadline, no terminal outcome, and one
   race record. Duplicate, stale, terminal, and wrong-transport readiness is
   inert. Existing TargetLock, retired transport, current-winner, and
   `StartWebRtc` checks remain unchanged.

7. **Use layered evidence.** JVM tests cover plan ordering, T+3 boundaries,
   delayed readiness, normal T+5 preservation, stale attempts, early success,
   and restart delay. Existing instrumentation and the reusable emulator matrix
   provide Android/process/network/synthetic-audio evidence. OEM/RF timing and
   real audio remain Release Candidate work.

## Risks / Trade-offs

- **Cleanup consumes the whole fast window.** -> T+3 remains scheduled from
  immutable attempt creation. The Coordinator records fallback due without
  opening an unready adapter, then opens each rebuilt transport as soon as that
  exact adapter reports ready, without resetting T+3 or T+10.
- **A stale readiness event opens transports for a replacement.** -> Require
  exact immutable attempt equality and current `RECOVERING` state.
- **Reordering the recovery plan weakens target lock.** -> Only transport order
  changes; the same complete TargetLock and transport set are retained.
- **The alternate opens after preferred recovery already succeeded.** -> The
  terminal/current-attempt checks reject the late milestone before physical work.
- **OEM overlap differs from emulators.** -> Keep KUM-31 attempt-local BUSY
  sequential fallback and defer physical radio behavior explicitly.

## Migration Plan

1. Strictly validate the KUM-33 proposal, spec, design, and tasks.
2. Add failing deterministic Coordinator/orchestrator and restart-delay tests.
3. Implement recovery plan ordering, T+3 race timing, exact readiness routing,
   and zero additional recovery backoff.
4. Run targeted JVM, full Gradle, instrumentation/emulator matrix, and CI gates.
5. Complete fixed-SHA read-only architecture review, remediate P0/P1, merge with
   a merge commit, verify main CI, and synchronize Linear evidence.

Rollback is one revert of the KUM-33 merge commit. No protocol or data migration
is required.

## Open Questions

None.
