## Context

The Coordinator already creates a fresh recovery attempt after the first and
second final failures, preserving the complete `TargetLock` and transport plan
with a new ID and immutable monotonic T+10 deadline. KUM-34 originally required
Service to close and recreate both discovery adapters before every retry.
Physical Wi-Fi Direct close/setup latency consumed enough of that budget that
KUM-44 glare convergence often had no signaling channel on which to run.

## Decisions

1. **Reuse adapters only for delayed recovery retries.** The initial connected
   loss still uses the existing full attempt cleanup. A `RestartDiscovery`
   effect with `RECOVERY` and the existing 1.5-second retry delay selects the
   reuse path. Missing or ineligible adapters fail closed to the complete
   cleanup path.

2. **Rebind ownership at attempt granularity.** A shared pure gate requires a
   live deadline, recovery trigger, planned transport, identical runtime, and
   identical complete `TargetLock`. The adapter binds the fresh immutable
   attempt before any new targeted work. Old attempt IDs and generations cannot
   claim signaling or media.

3. **Retain discovery, not attempt resources.** Service drains signaling,
   closes WebRTC/media for the failed attempt, cancels attempt milestones, and
   clears connection flags. LAN closes only its targeted client socket.
   Wi-Fi Direct closes only its signaling socket and validation/watchdog work,
   retaining accepted discovery identity, a matching group, or the exact-target
   platform connect where available.

4. **Honor the bounded retry delay.** A prepared Wi-Fi Direct adapter ignores
   connection broadcasts until the fresh attempt is opened. Resume then checks
   the preserved group/connect/discovery state and starts only work allowed by
   the current attempt. The delay is capped by, and never rebases, the immutable
   attempt deadline.

5. **Keep third failure as the full reset boundary.** KUM-34
   `ResetWirelessEnvironment` continues through the complete LAN close and
   ordered Wi-Fi Direct close/rebuild chain. No adapter reuse is allowed for
   that effect.

## Ownership

- `SessionOrchestrator` remains the only product-state writer.
- The Coordinator remains the only attempt/deadline/TargetLock owner.
- Service selects cleanup execution but cannot create or rewrite attempts.
- Discovery adapters execute one bound attempt and cannot select a target.

## Risks and Mitigations

- A stale platform callback could tear down a retained group. Same-target
  recovery replacement is recognized explicitly; all other stale identities
  still fail closed.
- An adapter could be missing or no longer active. Reuse returns false and
  Service falls back to complete cleanup.
- Retaining a contaminated radio environment could extend failure. Only the
  first two retries reuse adapters; the third final failure performs the
  complete reset.

## Rollback

Revert the KUM-44 adapter-reuse commit. The prior full-rebuild retry behavior
and third-failure reset remain available without schema or protocol rollback.
