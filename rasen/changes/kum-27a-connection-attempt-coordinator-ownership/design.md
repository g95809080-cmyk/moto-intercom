## Context

KUM-27A is a documentation-only architecture checkpoint on base `a2345e4baf2767a6bfb312d661ecdbe796e81020`. Sprint 2 is complete. KUM-27B is forbidden until this inventory is independently approved.

The current product has one product-state writer (`SessionOrchestrator`) but more than one attempt policy owner:

- `IntercomStateMachine` creates outbound Presence attempts, terminates attempts for open failure and timeout, and creates recovery attempts from a Service-created spec.
- `SignalingControlCoordinator` owns verified control-channel sets, phases, terminal outcomes, media-owner selection, inbound attempt creation, and several direct terminal state/effect decisions.
- `IntercomService` creates outbound and recovery IDs/deadlines, hosts deadline schedulers, executes effects, tracks `activeMediaChannelId` and `tunnelChosen`, and performs broad runtime teardown/restart.
- LAN and Wi-Fi Direct retain mutable target-attempt state and schedule independent connect, group, Socket, watchdog, retry, and cleanup work.

The current deadline is not one immutable total budget. It is created as 10 seconds, extended to approximately 25 seconds after `CONNECT_REQUEST` delivery, and reset to another 10 seconds after remote accept. P2P also has a 12-second watchdog, a 30-second group identity deadline, a 12-second Socket-ready deadline, fixed Socket connect retries, and cleanup retries that do not read the remaining product budget.

## Goals / Non-Goals

**Goals:**

- Freeze one target owner for attempt identity, target, plan, trigger, total deadline, candidates, terminal outcome, and winner.
- Preserve `SessionOrchestrator` as the only product-state writer.
- Define atomic migration and old-owner removal points for KUM-27B.
- Define complete stale-callback and cleanup contracts.
- Preserve two-phone acceptance boundaries and the accepted three-phone residual risk.
- Make the KUM-27B gate testable and independently reviewable.

**Non-Goals:**

- No Android runtime code or behavior changes in KUM-27A.
- No second Coordinator or compatibility bridge.
- No T+5 fallback, dual-transport race, optimization window, or `OPTIMIZING` transition.
- No TargetLock, Signaling v2, pairing, notification, database, UI, Gradle, or permission changes.
- No Sprint 4 recovery policy.
- No KUM-28 work.

## Decisions

### 1. Target ownership boundary

```text
SessionOrchestrator
    `-- ConnectionAttemptCoordinator
        |-- owns logical attempt
        |-- owns immutable total monotonic deadline
        |-- owns logical transport candidates
        |-- owns terminal outcome and winner selection
        `-- emits effects

IntercomService
    `-- executes effects and owns physical component references only

Transport adapters
    `-- execute bounded targeted attempts and contextual cleanup only
```

`ConnectionAttemptCoordinator` does not write UI, control the Android Service lifecycle, persist pairing data, or change Signaling v2 message semantics. `SessionOrchestrator` serializes events and is the only object that assigns product state.

Alternative considered: add a new Coordinator beside `SignalingControlCoordinator` and gradually mirror state. Rejected because it creates two attempt authorities and makes stale/terminal ordering unverifiable.

Migration choice: evolve the existing coordinator in place, atomically move each responsibility and remove its old decision path, then rename it to `ConnectionAttemptCoordinator`. At no reviewed checkpoint may two runtime coordinator instances exist.

### 2. Definition of Ready for KUM-27B

- [x] Sprint 2 PR merged and post-merge `main` CI passed.
- [x] Base SHA fixed at `a2345e4baf2767a6bfb312d661ecdbe796e81020`.
- [x] KUM-27 branch started from that base.
- [x] Current ownership and duplicate authorities inventoried.
- [x] Target ownership and forbidden scope defined.
- [x] Automated test strategy defined.
- [x] Two-device boundary and three-phone residual risk retained.
- [x] KUM-27A Rasen artifacts validate strictly.
- [x] Full Android test, lint, and assemble gate passes on the artifact head.
- [ ] Draft PR CI passes.
- [ ] Independent architecture review returns APPROVED, P0=0, P1=0.

Only the final four unchecked items may open KUM-27B.

### 3. Ownership migration matrix

| Resource or event | Current owner | Target owner | Migration method | Compatibility phase | Old owner removed when | Test method |
| --- | --- | --- | --- | --- | --- | --- |
| Outbound `ConnectionAttempt` | `ConnectPresenceRequested.toAttemptOrNull()` in reducer; Service supplies ID/deadline | `ConnectionAttemptCoordinator` | Pass Presence intent inputs to Coordinator; inject ID factory and monotonic clock | Atomic owner cutover | Reducer no longer constructs attempts and Service no longer supplies attempt ID/deadline | Virtual-clock creation test; one attempt per accepted intent |
| Inbound `ConnectionAttempt` | `SignalingControlCoordinator.inboundAttempt()` | `ConnectionAttemptCoordinator` | Retain verified `WireRequestKey.attemptId` adoption inside evolved Coordinator | In-place evolution | Old class is renamed after all attempt duties move | Verified inbound, duplicate request, glare tests |
| Recovery attempt | Service `createRecoverySpec()` plus reducer `toRecovery()` | `ConnectionAttemptCoordinator` | Disconnect event carries cause/context, not a prebuilt spec; Coordinator creates ID/deadline and preserves target/plan | Atomic owner cutover | `RecoveryAttemptSpec` creation leaves Service and reducer constructor is removed | Recovery target/plan and virtual-clock tests |
| Attempt ID | Service for outbound/recovery; remote wire key for inbound | Coordinator ID factory; verified remote key for inbound | Inject deterministic factory | Same commit as attempt creation | No `ConnectionAttemptId.create()` in Service attempt paths | Uniqueness and deterministic factory tests |
| `TargetLock` | Reducer for Presence; coordinator for inbound; reducer copies for recovery | Coordinator | Accept target intent inputs, validate once, preserve on recovery | Same commit as attempt creation | Reducer/Service do not construct or replace TargetLock | Wrong device/session and recovery retention tests |
| `ChannelPlan` | Reducer selects LAN-first; coordinator mirrors channel transport inbound | Coordinator | Keep exactly one transport; move policy choice behind Coordinator input | KUM-27 single-transport only | Reducer no longer selects transport | Single planned transport and no KUM-28 tests |
| Trigger | Reducer/Coordinator constructors | Coordinator | Coordinator derives from request kind | Same commit as attempt creation | No external attempt copies change trigger | User/inbound/recovery trigger tests |
| Current attempt | Encoded in `IntercomState`; mirrored by `AttemptChannelSet.active` and adapter fields | Coordinator logical record, projected into state by Orchestrator | One Coordinator record is authoritative; state carries immutable snapshot | In-place coordinator evolution | `active` and state can no longer diverge; adapter fields are leases only | Replacement, terminal mailbox, stale callback tests |
| Total deadline | Service creates; Service scheduler executes; signaling coordinator rebases; reducer matches copied value | Coordinator owns value; Service owns timer executor only | Remove all `copy(deadline=...)` rebases and `RescheduleAttemptDeadline`; schedule once from effect | Atomic deadline cutover | Request-delivery and accept rebasing code/tests are replaced | Virtual clock: request, accept, timeout retain same value |
| Adapter timeout | LAN/P2P/Socket constants and wall-clock loops | Adapter local mechanism bounded by Coordinator deadline | Pass remaining monotonic budget to each operation; use `min(localCap, remaining)` | Per-adapter atomic cutover | Fixed timeout cannot outlive current attempt | Boundary tests at 0, 1, cap-1, cap, and canceled attempt |
| Attempt timeout callback | `AttemptDeadlineScheduler` in Service; reducer and coordinator both observe terminal transition | Coordinator decision via Orchestrator; Service timer posts contextual event | Keep stale-guarded timer executor, route terminal authority only to Coordinator | Same commit as deadline cutover | Reducer no longer independently terminates attempt | Queued timeout versus accept/cancel/replacement tests |
| Product state | `SessionOrchestrator` writes; reducer and signaling coordinator both propose terminal states | `SessionOrchestrator` writes; Coordinator owns attempt decisions | Route all attempt lifecycle events through Coordinator before generic reducer | In-place routing cutover | Generic reducer has no competing attempt terminal path | Every product state transition plus illegal transition tests |
| Control-channel winner | `SignalingControlCoordinator.mediaOwnerChannelId` | Coordinator | Retain deterministic logical selection in evolved Coordinator | In-place evolution | No Service decision can select/replace winner | Duplicate channels, late accept, owner/non-owner disconnect tests |
| Service media/tunnel claims | `activeMediaChannelId`, `tunnelChosen` | Coordinator for authority; Service for physical handle only | Set/clear physical handle only while executing Coordinator effects; remove claim as policy gate | After winner effect contract lands | `tunnelChosen` no longer blocks or selects attempts; media ID cannot authorize by itself | Two StartWebRtc effects cannot create two managers; stale effect closes resource |
| LAN target lease | `LanAttemptLease`, `clientConnecting` | Coordinator owns logical candidate; LAN owns contextual physical lease | Bind immutable attempt context and remaining budget; callbacks return same context | LAN adapter cutover | Lease cannot terminate/replace attempt or outlive cancellation | Existing lease tests plus delayed connect/open/accept tests |
| P2P target/group/watchdog | `targetAttempt`, mutable target address, validation/setup generations, watchdog generations | Coordinator owns candidate; P2P owns scoped task tokens | Capture runtime/attempt/candidate/target in each targeted callback; bound all timers | P2P adapter cutover | Mutable target is not read by old callbacks to validate new work | Old group/action/watchdog/retry callback tests |
| Socket ready/connect loop | `WifiDirectSignalingSocket` owns wall-clock deadline and session-only predicate | Adapter task token bounded by Coordinator deadline | Replace wall clock with monotonic remaining-budget supplier and full attempt predicate | Socket adapter cutover | Ready loop cannot schedule beyond total deadline | Fake-clock ready/connect/retry and stale-close tests |
| Signaling terminal outcomes | Signaling coordinator tombstones plus reducer terminal handlers | Coordinator | Preserve first-terminal mailbox ordering and tombstones in evolved Coordinator | In-place evolution | Reducer/Service cannot end attempt independently | Reject/busy/timeout/disconnect/glare ordering tests |
| Pending control channels | Coordinator metadata map plus Service `signalingSessions` physical map | Coordinator logical ownership; Service physical registry | Effects include runtime/attempt/channel; Service closes exact handle | Same commit as effect contract | Service map presence cannot imply logical ownership | Late channel and exact-handle replacement tests |
| Signaling session | Service physical object; coordinator channel metadata | Coordinator authorizes; Service executes lifecycle | Start/read/send/close only for current Coordinator-owned channel | Same commit as callback contract | Service callback cannot bypass current attempt/winner | HELLO, REQUEST/response, stale reader/send completion tests |
| WebRTC and audio | Service/`IntercomManager`/`RiderAudioEngine`; Service checks Coordinator plus local fields | Coordinator authorizes; Service and media components own physical lifecycle | Start only from winner effect; callbacks carry immutable attempt/winner context | After winner contract | Local fields are resource locators, not authority | No pre-accept or duplicate WebRTC; stale PeerConnection callback tests |
| Confirmation surface | Coordinator owns prompt identity/deadline; Service scheduler/UI executes | Same logical/physical split | Keep 15-second channel/request deadline separate from total attempt deadline | No Signaling v2 semantic change | Any Long.MAX_VALUE attempt sentinel and deadline rebase are removed | Nonce rotation, timeout ordering, late action tests |
| Broad attempt cleanup | Coordinator emits some close/abort effects; reducer emits abort; Service `AttemptResourceController` tears down runtime | Coordinator owns logical cleanup set; Service/adapters execute idempotent physical cleanup | Replace broad policy decisions with explicit cancel/close effects while preserving safe runtime teardown when required | Resource-by-resource cutover | Service cannot decide attempt terminal state or restart target | Exact resource cleanup and newer-attempt preservation tests |
| Service restart/rollover | `SessionGeneration`, Service teardown and delayed discovery restart | Orchestrator/Coordinator logical ownership; Service lifecycle execution | Carry runtime and attempt context through restart effect; invalidate prior task tokens | Recovery cutover | Old runtime callback cannot claim new runtime resources | Runtime rollover and delayed restart tests |

### 4. Deadline inventory and target disposition

| Deadline or delayed task | Current creator | Scheduler | Canceller | Consumer | Current stale validation | Can extend current flow | Target disposition |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Outbound transport total | Service, now + 10s | `AttemptDeadlineScheduler` | Service | `AttemptTimedOut` | Full attempt equality and runtime/id/deadline reducer check | Yes, later rebases replace it | Coordinator creates once; Service schedules exact value |
| Requester remote decision | Signaling coordinator, now + 15s + 10s | Service via `RescheduleAttemptDeadline` | Scheduler replacement | Attempt timeout | Attempt copy/deadline equality | Yes, to about 25s | Remove total-deadline rebase; remote decision remains within original total |
| Responder human decision | Signaling coordinator, now + 15s | confirmation scheduler | nonce-scoped cancel | incoming action/timeout | runtime+attempt+channel+nonce+timestamp | It currently precedes a fresh 10s attempt | Keep as separate channel/request deadline; never mutate total |
| Post-accept media | Signaling coordinator, now + 10s | Service schedules in `startWebRtc` | WebRTC connected/abort/stop | Attempt timeout | full attempt | Yes, resets budget | Remove rebase; media consumes original remaining budget |
| Recovery total | Service, now + 10s | Service after teardown/restart | runtime cancel | reducer/coordinator | runtime/attempt/deadline | New attempt budget, but cleanup/backoff consumes it | Coordinator creates once; cleanup/backoff uses remaining budget |
| LAN Socket connect | LAN constant 2s | blocking Socket | Socket close/thread interruption | LAN failure callback | session token + attempt lease | Local cap can ignore smaller remaining budget | `min(2s, remaining)` with attempt token |
| HELLO read | SignalingSession constant 1s | Socket `soTimeout` | Socket close | establish failure | pinned target after read | Local cap can ignore smaller remaining budget | `min(1s, remaining)` |
| P2P connect watchdog | P2P constant 12s | main Handler | generation increment | group cleanup/retry | running+watchdog generation+address | Yes, exceeds 10s | Bound to remaining and carry attempt/candidate token |
| P2P group identity | P2P constant 30s | validation gate + Handler | gate generation | group reject/start Socket | running+validation generation; reads mutable target | Yes | Bound to remaining and capture attempt/target |
| P2P Socket ready | Socket constant 12s using wall clock | executor loop | close/session predicate | ready/failure | socket generation + session state; handoff checks target attempt | Yes | Use monotonic Coordinator deadline and full context |
| P2P Socket connect/retry | 3s cap + 500ms retry | executor sleep | close/interruption | ready/failure | Socket session predicate | Yes when remaining is smaller | Clamp connect and sleep to remaining |
| P2P BUSY/remove retries | 1s delays, up to 3 removal attempts | main Handler | running/generations vary | setup/group cleanup | mixed generation/running checks | Cleanup may outlive product flow | Cleanup may finish late but cannot revive attempt; targeted retry is budget-bound |
| P2P pending discovery retry | 1.5s x4 | main Handler | pending generation | discovery | running+pending generation | Discovery only unless it resolves mutable target | Keep passive discovery; targeted connect requires current attempt token |
| Service discovery restart | 1.5s | main Handler | recovery generation | restart adapters | running+generation+runtime | Consumes recovery budget | Keep as executor delay, then check Coordinator remaining budget |
| Presence TTL | Presence aggregator | main Handler | expiry generation | discovery list | generation | No attempt effect | Unchanged; explicitly outside attempt budget |

### 5. Callback validation audit

Legend: `Y` is explicit validation; `I` is indirect through a captured object or lower layer; `N` is missing/not applicable. Discovery-only callbacks are safe only while they cannot claim an attempt resource.

| Callback | Runtime | Attempt | Channel/candidate | Target device | Remote runtime | Current stale action | KUM-27B requirement |
| --- | --- | --- | --- | --- | --- | --- | --- |
| LAN NSD resolve/lost | I via session token | N | discovery endpoint only | discovery claim only | discovery claim only | ignore when runtime inactive | Remain discovery-only; resolving target must recheck Coordinator context |
| LAN client connect completion | I via token | Y via lease equality | N before HELLO | Y in HELLO | Y in HELLO | close on changed lease/session | Add candidate ID and remaining-budget context |
| LAN passive accept | I via token | I via captured lease snapshot | N before HELLO | Y in HELLO when outbound; verified inbound otherwise | Y in HELLO | close if lease changed | Report verified channel context; never create target from discovery |
| P2P DNS-SD callbacks | I via running/setup gate | N | address | discovery claim | discovery claim | ignored/reset by setup generation in some paths | Keep discovery-only; no attempt terminal authority |
| P2P connect ActionListener | I via running | N | I via mutable address | I via mutable claim | I via mutable claim | failure mutates shared P2P state | Capture and validate attempt/candidate/target before mutation |
| P2P connection/group info | I via running/gate | N; reads mutable target | group generation | reads mutable target/claim | reads mutable claim | old callback may inspect new target | Capture exact attempt context and reject stale without touching new work |
| P2P watchdog/retries | I via running/generation | N | address/generation | N | N | generation prevents some stale actions | Carry full attempt/candidate context and remaining budget |
| Wi-Fi Direct Socket ready/failure | I via socket generation/session | Y at handoff via target equality | socket generation | Y via HELLO TargetLock | Y via HELLO | close stale session | Make attempt/candidate context part of predicate and event |
| HELLO/IDENTITY establish | Y local runtime | Y wire key/originating attempt | Y new channel | Y pinned device | Y pinned remote runtime | closes Socket on mismatch | Preserve fail-closed behavior |
| REQUEST/ACCEPT/REJECT/BUSY/DISCONNECT | Y | Y | Y | Y pinned envelope | Y pinned envelope | coordinator closes conflicting/stale channel | Preserve; route attempt terminal authority to one Coordinator |
| SDP/ICE | I via pinned session | I via wire key | Y; Service additionally checks media ID | Y pinned | Y pinned | queues by channel before media owner | Require current winner context before queue or delivery |
| PeerConnection | Y via Service token | Y captured attempt | I via active media session | I via attempt TargetLock | I via attempt TargetLock | reducer rejects stale attempt | Coordinator validates winner and current attempt before state decision |
| Total timeout | Y | Y | timer owns full attempt | I via attempt | I via attempt | old deadline ignored after rebase | One immutable token; old/replaced attempt callback is no-op |
| Notification action | Y | Y | Y | I via active prompt peer | I via active prompt peer | nonce/deadline matching rejects stale | Preserve all matching and separate deadline ownership |
| Recovery callback/spec | Y via token | callback captures old; Service mints new | I via media channel | I via old attempt | I via old attempt | current reducer checks old ID | Coordinator creates and owns new attempt; callback supplies cause only |
| Service restart delayed task | Y runtime | I via `nextAttempt` | N | I via attempt | I via attempt | generation/runtime guard | Validate Coordinator current recovery attempt before reopening adapter |

### 6. Product-state authority

| State | Current transition sources | Target decision |
| --- | --- | --- |
| `OFFLINE` | Reducer after `RuntimeStopped` | Unchanged, Orchestrator only |
| `DISCOVERING` | Reducer and signaling coordinator terminal helpers | Orchestrator applies Coordinator terminal decision; no competing reducer attempt terminator |
| `INCOMING_CONFIRMATION` | Signaling coordinator direct decision | Coordinator owns request/confirmation decision; Orchestrator writes state |
| `CONNECTING` | Reducer and signaling coordinator | Coordinator owns attempt transitions; Orchestrator writes state |
| `OPTIMIZING` | Reducer has dormant `TransportOptimizing` path | Forbidden in KUM-27; KUM-28 approval required |
| `CONNECTED` | Reducer on PeerConnection connected | Coordinator validates current winner; Orchestrator writes state |
| `RECOVERING` | Reducer using Service-created recovery spec | Coordinator creates recovery attempt; Orchestrator writes state |
| `RESETTING` | Reducer on recovery exhausted | Unchanged policy unless KUM-27 routing requires contextual validation |
| `STOPPING` | Reducer on Service/user stop | Unchanged, Orchestrator only |

### 7. Resource cleanup ownership

| Resource | Logical owner | Physical executor | Terminal/stale rule |
| --- | --- | --- | --- |
| LAN Socket and attempt lease | Coordinator candidate | LAN adapter/Service | Close exact Socket and release only matching lease |
| P2P Socket and group | Coordinator candidate | P2P adapter | Cancel scoped tasks, close Socket, remove group idempotently; late cleanup cannot revive attempt |
| Pending control channel | Coordinator | Service/SignalingSession | Close exact runtime/attempt/channel handle on reject or stale callback |
| SignalingSession | Coordinator authorizes | Service | Reader/send completion must still match current channel context |
| WebRTC session | Coordinator winner | Service/IntercomManager | Only winner effect starts it; terminal winner cleanup closes it once |
| RiderAudioEngine | Coordinator winner lifecycle | IntercomManager | Cannot exist before accepted winner; closes with media session |
| Total deadline | Coordinator | Service scheduler | Schedule once; cancel exact attempt on terminal/replacement/connected policy |
| Adapter watchdog/delayed task | Coordinator candidate context | Adapter | Token and remaining budget required; cancel on candidate/attempt terminal |
| Confirmation surface | Coordinator prompt/nonce | Service scheduler/UI | Exact runtime/attempt/channel/nonce cancellation; separate deadline |
| Tunnel/media claim | Coordinator winner | Service physical locator | Service locator cannot award or replace winner |

### 8. Allowed and forbidden KUM-27B scope

Allowed only as required by this matrix:

- `ConnectionAttempt`, `SessionOrchestrator`, attempt-related reducer events/effects, and the existing signaling coordinator evolved into the one Coordinator.
- `IntercomService` effect execution and physical resource locators.
- LAN, Wi-Fi Direct, Socket, watchdog, and cleanup context/budget plumbing.
- Deterministic tests that prove the target ownership contract.

Forbidden:

- Two Coordinators or two total-deadline owners.
- Service and Coordinator both ending attempts.
- Adapter deadline extension or stale callbacks bypassing current-attempt checks.
- T+5 fallback, dual transport, optimization window, `OPTIMIZING`, or KUM-28.
- TargetLock, Signaling v2 business semantics, pairing, notification UX, database, permissions, broad UI, Gradle, or Sprint 4 recovery changes.

## Risks / Trade-offs

- [The existing 15-second responder confirmation can outlive the requester's 10-second total attempt] -> Keep it as a separate request/channel deadline; a late response is rejected or closed and never revives the requester.
- [P2P group cleanup can take longer than the product attempt] -> Permit cleanup to finish asynchronously, but revoke all logical authority at the terminal decision and guard every callback.
- [Current tests intentionally assert deadline rebasing] -> Replace those tests in the same atomic deadline cutover; do not keep compatibility branches.
- [Moving a 1,420-line signaling coordinator can create another coordinator accidentally] -> Evolve one instance in place and rename only after ownership is complete.
- [Service still needs resource handles] -> Treat them as executor-owned locators with no decision authority and test duplicate/stale effects.
- [Full Rasen profile includes unused automation and chrome-use assets] -> Project governance forbids chrome-use, browser cookies, auto-merge, and deployment during this pilot.

## Migration Plan

1. KUM-27A: validate these artifacts, run repository health gates, open a Draft PR, and obtain fixed-SHA architecture approval.
2. KUM-27B checkpoint 1: add virtual-clock characterization tests for the immutable deadline and current ownership failures. Do not add a second Coordinator.
3. Evolve the existing signaling coordinator in place to create and own outbound, inbound, and recovery attempts. Remove Service/reducer construction in the same cutovers.
4. Move deadline ownership to the Coordinator, schedule once through a Service effect, and remove all rebasing code/effects/tests atomically.
5. Route all attempt terminal events to the Coordinator; remove competing reducer and Service terminal decisions.
6. Add contextual remaining-budget contracts to LAN, P2P, Socket, group, watchdog, and retry paths one adapter at a time, removing each old callback path in the same commit.
7. Make Coordinator winner effects the only media authorization; demote or remove Service duplicate claims.
8. Run focused tests after each cutover, then the full unit, lint, assemble, CI, and two-device checks required by KUM-27.
9. Rename the evolved class to `ConnectionAttemptCoordinator` only when no old ownership remains; independent review verifies no dual owner.

Rollback is commit-level because KUM-27 changes no database schema, protocol version, pairing format, or production data. Revert the current KUM-27B checkpoint to the last approved commit; physical cleanup remains idempotent and the previous single-transport behavior is restored.

## Open Questions

No product or architecture choice remains open for KUM-27A. Implementation details such as exact candidate-token type and file split are intentionally deferred to KUM-27B, but they MUST satisfy this ownership contract and cannot change its owners or scope.
