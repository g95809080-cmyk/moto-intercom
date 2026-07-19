## Context

The Coordinator already builds recovery from the connected attempt, preserving
its `TargetLock`, peer, and channel plan under a fresh attempt ID. The remaining
gap is below that product-state boundary. `startDiscoveryTransports()` starts
adapters before the preferred transport open binds their target lease, and a
verified responder session without an `originatingAttempt` is accepted by the
generic Service handoff gate. A nearby C can therefore form a group or complete
HELLO before the Coordinator later returns busy. Presentation also uses generic
recovery text even though `IntercomState.Recovering.peer` retains B's name.

KUM-32 must close those gaps without moving target authority into Service or an
adapter. It must also avoid implementing KUM-33 timing/fallback, KUM-34 reset,
KUM-35 disconnect, or KUM-36 final-matrix policy.

## Goals / Non-Goals

**Goals:**

- Make the immutable recovery attempt available to every planned adapter from
  its first discovery/group/HELLO callback.
- Reject non-target Socket, group, and HELLO work before resource ownership can
  reach signaling/media.
- Keep the Service handoff as a defense-in-depth current-state check.
- Derive UI and notification recovery text from the retained peer.
- Prove B-down/C-faster ordering deterministically and preserve all existing
  target, attempt, deadline, winner, and media ownership.

**Non-Goals:**

- No three-second fast window, fallback-policy, retry-backoff, failure counter,
  `RESETTING`, active-disconnect, or full Sprint 4 acceptance implementation.
- No Signaling v2 format, TargetLock semantics, Presence ranking, pairing,
  database, WebRTC, audio ownership, dependency, signing, deployment, or release
  change.
- No claim that emulator evidence proves OEM Wi-Fi Direct, RF, Bluetooth SCO,
  real acoustics, power, thermal behavior, or background survival.

## Decisions

1. **Keep the Coordinator as the only target owner.** The Service passes the
   current immutable recovery attempt into adapters; adapters only validate and
   execute against it. They never inspect Presence to choose or replace a target.

2. **Seed a separate ingress-validation lease at construction.** Each adapter
   receives the recovery attempt for HELLO/group identity validation while its
   active-connect lease remains empty until `OpenTargetedTransport`. This removes
   the startup window without letting discovery open the fallback transport
   early. Reusing the immutable attempt is smaller than adding a second recovery
   owner or reading orchestrator state from adapter threads.

3. **Keep ingress validation separate from active transport work.** LAN uses the
   ingress lease only to validate accepted HELLO and the active lease to choose
   outbound targets. Wi-Fi Direct uses the ingress attempt to reject wrong
   groups, but removes even a matching group until that transport is actively
   opened. Existing selected-channel/passive paths release both matching leases;
   runtime stop/replacement still closes adapters and invalidates callbacks.
   Service also refreshes every planned adapter's ingress lease immediately
   before any preferred/fallback open through one shared production helper. The
   media-only recovery path therefore reuses the existing adapter instances,
   binds every transport in the immutable plan, and opens only the transport
   named by `OpenTargetedTransport`. If a group passes discovery-claim validation
   but the current Socket HELLO later reports a different device or runtime,
   establishment failure is routed through the existing current-context Socket
   failure path so the Socket closes and the group is removed before rediscovery.

4. **Add a Service defense-in-depth target check.** The verified-control-channel
   installation gate receives the current product state. While `RECOVERING`, a
   session must match the recovery attempt's `TargetLock`, including responder
   sessions with no originating attempt. A production admission seam used by
   `IntercomService` returns an explicit non-target Wi-Fi Direct cleanup outcome,
   closes the rejected session before registry/Coordinator ownership, and tells
   the adapter to remove its current group and restart target-bound discovery.
   The adapter applies the same fail-closed cleanup when actual HELLO identity
   contradicts an earlier accepted discovery claim.
   This complements adapter validation; a Socket-only fix was rejected because
   it would leave the wrong P2P group alive.

5. **Share presentation text, not presentation state.** A small pure helper maps
   `IntercomState.Recovering.peer` to `正在恢复与 {车友} 的连接`, with device-name and
   `原车友` fallback. MainScreen and the foreground notification consume the
   same helper. The notification rebuilds on product-state changes while the
   runtime is active, preventing generic transport status from overwriting the
   recovery target text.

6. **Use layered evidence.** JVM tests cover attempt retention, Presence
   rejection, the production Socket admission seam, wrong/stale Wi-Fi Direct
   HELLO cleanup, seeded/reused adapter leases, presentation, and the A/B/C race
   through the Coordinator and media gate. Existing instrumentation and the reusable three-emulator matrix cover
   integration, process restart, networking, and resource evidence. Hardware-only
   observations remain Release Candidate work.

## Risks / Trade-offs

- **A seeded ingress attempt opens the fallback transport early.** -> Keep
  ingress validation and active-connect leases separate; only the existing
  `OpenTargetedTransport` effect binds active connect work.
- **A stale recovery lease survives winner selection.** -> Reuse the existing
  release paths and add regression tests that completion clears only the matching
  attempt.
- **A responder session from B is rejected with C.** -> Compare the complete
  `TargetLock` (device and runtime), not request role or discovery order.
- **Notification timing races generic status updates.** -> Compute foreground
  text from current product state at build time and refresh on state changes.
- **Emulator P2P differs from hardware.** -> Prove group/identity/cleanup
  contracts with deterministic tests and keep OEM/RF behavior explicitly
  deferred.

## Migration Plan

1. Strictly validate proposal, spec, design, and tasks.
2. Add failing deterministic tests for seeded recovery ingress, Socket target
   gating, B-down/C-faster ordering, and shared presentation text.
3. Implement the minimum adapter, Service, and presentation changes.
4. Run targeted JVM, full Gradle, Android/emulator matrix, and CI gates.
5. Complete fixed-SHA read-only architecture review, remediate P0/P1, merge with
   a merge commit, verify main CI, and synchronize Linear evidence.

Rollback is one revert of the KUM-32 merge commit. No protocol or data migration
is required.

## Open Questions

None.
