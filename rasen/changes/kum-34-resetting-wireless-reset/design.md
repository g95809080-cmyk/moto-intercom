## Context

The current Coordinator owns attempt identity, target, deadline, terminal outcome, transport race, and winner selection. A connected-session loss creates one fresh KUM-33 recovery attempt, but any final attempt termination still follows the ordinary path to `DISCOVERING`. `IntercomState.Resetting`, `SessionEvent.RecoveryExhausted`, and `SessionEvent.ResetCompleted` exist as disconnected skeletons, and Service never executes a reset effect.

The runtime already has most physical cleanup primitives. `AttemptResourceController` invalidates attempt resources without closing the KUM-37 online audio owner. `WifiDirectTunnel.close()` already sequences `cancelConnect`, `removeGroup`, `clearServiceRequests`, `clearLocalServices`, and channel close, while `LanDiscoveryCoordinator.close()` stops UDP/TCP/NSD and its executor. KUM-34 must connect these primitives without adding another Coordinator, product-state writer, deadline owner, or adapter policy owner.

## Goals / Non-Goals

**Goals:**

- Keep one immutable target across up to three complete recovery attempts.
- Count only accepted terminal exhaustion of the whole recovery attempt.
- Create fresh attempt identities and immutable monotonic deadlines for retries.
- Keep `RECOVERING` visible during first/second retry cleanup and backoff.
- Enter exact, stale-safe `RESETTING` on the third final failure.
- Execute complete ordered P2P and LAN teardown, rebuild discovery, and return to `DISCOVERING` only on exact completion.
- Preserve the existing single product-state writer, single Coordinator, KUM-32 TargetLock, KUM-33 T+3/T+10 policy, and KUM-37 hot audio lifecycle.

**Non-Goals:**

- No KUM-35 active-disconnect behavior or KUM-36 final Sprint acceptance expansion.
- No change to Signaling v2, TargetLock semantics, identity verification, winner selection, WebRTC/media ownership, pairing schema, permissions, dependencies, signing, deployment, or release behavior.
- No claim that emulators prove OEM radio, RF, Bluetooth SCO, acoustics, power, thermal, or background survival.

## Decisions

1. **Carry the failure streak in immutable product recovery state.** `IntercomState.Recovering` gains a non-negative consecutive-final-failure count. The initial recovery starts at zero; accepted final exhaustion produces count one or two on the replacement recovery state. `IntercomState.Resetting` carries count three, the target, and the exhausted attempt ID. Success or exact reset completion leaves those states and therefore clears the episode. This is smaller and safer than a global mutable singleton, a Service-owned counter, or a database value driving live product decisions.

2. **Centralize counting at existing Coordinator terminal roots.** Both the active-channel completion path and the no-active-channel termination path route recovery terminal outcomes through one helper. `TIMED_OUT`, `FAILED`, `REJECTED`, `BUSY`, and `DISCONNECTED` count only after the complete attempt is terminal. `SUCCESS`, `CANCELED`, and `GLARE_LOST` do not count. Duplicate terminal records, per-transport open failures, stale attempt IDs, and wrong targets remain inert under existing ownership checks.

3. **Create retry attempts immediately, then let cleanup/backoff consume their immutable budget.** On count one or two, the Coordinator creates a fresh recovery attempt with the same complete `TargetLock` and `ChannelPlan`, a new attempt ID, and `now + T+10`. It emits `RestartDiscovery` first, with the existing 1.5-second bounded reconnect backoff, followed by the new deadline and T+3 fallback schedules. Service cleanup plus backoff therefore consumes the same immutable retry budget; no Service callback creates or rebases the attempt. If another deadline or reset decision arrives while physical cleanup is still running, one Service-owned cleanup coordinator replaces only the pending restart request and reconciles completion with the latest Coordinator state.

   Alternative considered: keep the exhausted attempt current during backoff and add a second retry timer/event that creates the attempt later. Rejected because it adds another scheduling state and leaves product state pointing at a terminal attempt.

4. **Use the exhausted attempt ID as reset identity.** The third failure clears attempt ownership and emits `ResetWirelessEnvironment(runtime, target, failedAttemptId, count)`. `ResetCompleted` carries the same runtime and failed attempt ID. Reducer and Service gates require exact equality with current `Resetting`, preventing a late completion from an older reset in the same runtime from advancing a newer reset.

5. **Reuse production cleanup rather than inventing a second reset stack.** Service handles the reset effect through the same resource-release root used by failed attempts: cancel schedulers, invalidate the old `SessionGeneration`, drain signaling and WebRTC attempt resources, close LAN, and await `WifiDirectTunnel.close()`. Every Wi-Fi Direct close action has a bounded, one-shot watchdog, so a platform API that never invokes its listener cannot block channel close or discovery rebuild. After exact cleanup completion, Service installs fresh LAN/Wi-Fi Direct discovery instances, then dispatches the matching reset completion. `SessionOrchestrator` remains the only object that changes `RESETTING` to `DISCOVERING`.

6. **Keep the KUM-37 audio owner hot.** Reset closes per-attempt signaling/WebRTC and wireless discovery resources but does not close the runtime-owned audio-session controller. Full `StopRequested -> RuntimeStopped` remains the only shutdown path for the online audio owner.

7. **Use layered deterministic evidence.** Coordinator tests cover first/second retries, third-failure reset, target/plan preservation, fresh IDs/deadlines, count exclusions, active-channel restart schedule rearming, success reset, and stale callbacks. Orchestrator/reducer tests cover exact reset completion and stop supersession. Production-seam tests cover every never-callback close step plus deadline replacement during asynchronous cleanup through adapter installation and exact reset completion. Emulator evidence exercises visible `RESETTING`, cleanup/rebuild, and process-safe callback rejection where the platform supports it.

## Risks / Trade-offs

- **Cleanup/backoff can consume most of a retry's T+10 budget.** -> This is explicit and monotonic; readiness and fallback events already handle late adapter readiness without extending T+3 or T+10.
- **A Wi-Fi Direct API action can fail or never callback.** -> Each close step owns a cancellable timeout, advances once on callback, failure, throw, or timeout, and ignores every late duplicate completion.
- **A retry deadline can expire while old physical cleanup is still running.** -> Cleanup remains single-owner, but its pending restart request is replaceable; completion and backoff always re-check the latest immutable attempt or exact reset identity before installing adapters.
- **A stale Service callback can arrive after replacement or stop.** -> Invalidate `SessionGeneration`, clear current attempt ownership, retain terminal outcomes, and require exact reset identity at effect start and completion.
- **Failure count could be incremented by multiple terminal surfaces.** -> One Coordinator helper records the terminal attempt once before incrementing the immutable streak; duplicate records are rejected.
- **Physical radio cleanup differs across OEMs.** -> Verify domain/effect ordering with fakes and emulators, and keep OEM/RF rows `DEFERRED_TO_RELEASE_CANDIDATE`.

## Migration Plan

1. Add failing deterministic tests for recovery failure counting, retry identity/deadlines, exact reset identity, stale callbacks, and cleanup/rebuild completion.
2. Extend the existing state/event/effect contract and Coordinator terminal roots with the minimum KUM-34 policy.
3. Route the reset effect through existing Service cleanup and adapter close implementations; add only the exact gates needed for stale-safe completion.
4. Run targeted JVM tests, full JVM, lint, debug/test APK builds, applicable emulator matrix, strict Rasen validation, and fixed-SHA architecture review.
5. Remediate all in-scope P0/P1 findings, push one KUM-34 branch, merge with a merge commit after gates pass, verify exact-main CI, and synchronize Linear.

Rollback is a revert of the KUM-34 merge commit. There is no protocol or database migration to reverse.

## Open Questions

- None. The 1.5-second retry backoff reuses the existing bounded reconnect delay; physical OEM timing remains an RC acceptance item.
