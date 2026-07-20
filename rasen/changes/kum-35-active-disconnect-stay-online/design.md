## Context

`SessionOrchestrator` is the sole product-state writer and the existing `SignalingControlCoordinator` owns attempt identity, terminal classification, target, deadline, and media winner. The Coordinator already sends an owner-channel `DISCONNECT` before terminal cleanup, but terminal cleanup emits the broad `AbortAttemptAndResumeDiscovery` effect. Service consequently invalidates the whole transport generation, closes LAN/NSD/Wi-Fi Direct discovery, and rebuilds it after backoff. The UI also maps every online press to `requestStop()`, so the attempt-scoped path is not a product feature.

LAN and Wi-Fi Direct already expose `retainPassiveIngress(attempt)`, which releases only matching targeted leases while preserving discovery ownership. `closeControlChannel()` already closes the exact winner-bound `IntercomManager`/WebRTC handle. The KUM-37 `AudioSessionController` belongs to the online runtime and must close only on full Stop/destroy.

## Goals / Non-Goals

**Goals:**

- End an exact current attempt on local user request or an accepted peer `DISCONNECT`, return to `DISCOVERING`, and prevent recovery of that ended session.
- Send local `DISCONNECT` on the exact media-owner channel before physical cleanup when the channel is available.
- Release exact signaling, WebRTC, LAN client lease, and Wi-Fi Direct selected-group ownership without replacing the online runtime or discovery adapters.
- Keep Service, `RuntimeSessionId`, `SessionGeneration`, presence, foreground notification, and `AudioSessionController` online.
- Keep unexpected channel/media loss on the existing recovery/reset policy and full Stop as the only path to `OFFLINE`.

**Non-Goals:**

- No KUM-36 final Sprint matrix expansion or later-Sprint work.
- No Signaling v2 message/schema change, new disconnect reason, deadline/TargetLock/winner change, database/pairing/identity change, dependency, permission, signing, deployment, or release.
- No claim that emulators prove OEM Wi-Fi Direct, RF, Bluetooth SCO, acoustics, power, thermal, or background survival.

## Decisions

1. **Classify explicit disconnect only in the existing Coordinator.** `DisconnectRequested` and a matching owner-channel `RemoteDisconnect` are intentional cancellation, distinct from `SignalingDisconnected`, `ChannelClosed`, or WebRTC failure. A peer `DISCONNECT` received during recovery cancels that recovery episode instead of incrementing the KUM-34 final-failure streak. No Service/UI flag becomes a terminal authority.

2. **Emit one exact attempt-release effect.** A new immutable `ReleaseActiveSessionAndContinueDiscovery(attempt)` effect follows exact control-channel close effects. The effect itself is the Coordinator-issued intentional-disconnect authorization. Service executes it only when product state is the same runtime's `DISCOVERING` and Coordinator attempt/channel/pending ownership is empty. The first-terminal ledger deliberately remains `SUCCESS` for an already connected attempt, while pre-connect/recovery cancellation records `CANCELED`; no second mutable terminal authority is introduced. Exact immutable identity checks prevent an old release from touching replacement media or transport leases.

3. **Preserve physical discovery owners in place.** Service closes the winner-bound media handle and exact signaling channel, cancels only attempt timers, releases matching LAN/Wi-Fi Direct targeted leases through `retainPassiveIngress(attempt)`, clears remote/media locators, and publishes the normal searching state. It does not invalidate `SessionGeneration`, close/recreate LAN/NSD/Wi-Fi Direct adapters, clear presence, stop foreground operation, replace `RuntimeSessionId`, or close the KUM-37 audio controller.

4. **Mark local intent in immutable Coordinator context.** Local disconnect changes the current channel set to `TERMINATING` with `terminalOutcome=DISCONNECTED` after recording logical `CANCELED`. All send-success, send-failure, owner-close, and queued media-loss terminal roots recognize that context and converge on the same narrow release. Remote explicit disconnect invokes the same helper with logical `CANCELED`; unexpected loss continues through recovery.

5. **Use state-sensitive UI policy without adding a second service lifecycle control.** `Connecting`, `Optimizing`, `Connected`, and `Recovering` map the primary action to attempt disconnect. `Discovering` and `Resetting` map it to full Stop; `Offline` starts and `Stopping` is disabled. After disconnect, a second press from `Discovering` remains the explicit full-stop action.

6. **Prove the production seams deterministically.** Coordinator tests cover local send ordering, send failure, remote connected/recovering disconnect, stale/wrong-owner frames, and unexpected-loss recovery. Pure Service gates and a fake physical release seam prove exact lease/media cleanup while runtime/audio/discovery owners remain. UI policy tests prove action and label separation. Emulator evidence confirms process/UI/notification continuity where ATD supports it.

## Risks / Trade-offs

- **A delayed release effect could target a replacement attempt.** -> Require the Coordinator-issued exact immutable effect plus same-runtime `DISCOVERING` and empty current Coordinator ownership; media and adapters release only matching immutable identities.
- **The owner channel can fail while local DISCONNECT is sending.** -> The Coordinator's terminating context makes send-success, send-failure, channel-close, and queued media-loss paths converge once without starting recovery.
- **A peer DISCONNECT during recovery could be counted as another failure.** -> Classify explicit peer intent as `CANCELED`; only unexpected `DISCONNECTED` terminal roots count under KUM-34.
- **Wi-Fi Direct selected-group removal is asynchronous.** -> Reuse the adapter's idempotent group-removal/rediscovery path and generation gates while keeping the adapter instance online.
- **The single primary action is state-sensitive.** -> Labels expose “disconnect current rider” versus “stop intercom”; after a successful disconnect the product visibly returns to searching.

## Migration Plan

1. Freeze and strictly validate this proposal/spec/design/task contract at main `8dcb3f6`.
2. Add failing Coordinator, Service seam/gate, UI policy, and local/remote/unexpected-disconnect tests.
3. Add the narrow Coordinator effect and Service executor, then wire the state-sensitive UI action.
4. Run focused and full JVM, lint, debug/test APK, instrumentation, three-emulator matrix, strict Rasen validation, CI, and fixed-SHA read-only architecture review.
5. Merge with a merge commit after all gates pass, retain the remote branch, verify exact-main CI, and synchronize Linear before KUM-36 starts.

Rollback is a revert of the eventual KUM-35 merge commit. No protocol or database migration must be reversed.

## Open Questions

- None. Explicit disconnect is attempt cancellation; unexpected loss remains recovery; full Stop remains runtime shutdown.
