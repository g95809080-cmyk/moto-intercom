## Context

KUM-27A approved one future Coordinator owner while preserving
`SessionOrchestrator` as the only product-state writer. B1 completed the
framework-free `ConnectionAttempt` domain model and deterministic clock seam.
B2 moved production attempt creation and first-terminal ownership into the
existing `SignalingControlCoordinator` without creating another Coordinator.

B3 performs the previously reserved atomic cutover: the Coordinator owns every
total attempt deadline, attempts are scheduled once, and an unpaired inbound
request awaiting confirmation is no longer represented by a sentinel attempt.

B4 consumes that approved foundation. The Coordinator remains the logical
candidate and winner authority, while Service owns only contextual physical
session/media handles and executes exact cleanup.

## Goals / Non-Goals

**Goals:**

- Evolve the existing `ConnectionAttempt` model in place; do not introduce a
  parallel attempt representation.
- Give monotonic timestamps a pure value type and a replaceable clock seam.
- Express preferred transport, deadline expiration, attempt/target matching,
  and terminal outcomes in framework-free domain code.
- Provide deterministic JVM tests and a test-only fake clock.
- Make the existing Coordinator create outbound and recovery attempts with a
  deterministic ID factory while retaining the existing supplied deadline.
- Make it own the current logical attempt and the first terminal outcome.
- Remove live reducer and Service authority to mint IDs, construct production
  attempts, or independently end an attempt.
- Wire `MonotonicClock` into the Coordinator and create each total deadline
  exactly once without external deadline inputs or later rebasing.
- Replace the unpaired inbound sentinel with a Coordinator-owned pending-request
  model and create the real inbound attempt only on valid local acceptance.
- Schedule each created attempt once through an explicit Service effect.
- Carry one immutable candidate context through Service signaling, selection,
  media buffering, WebRTC callbacks, and cleanup execution.
- Make every upper-layer callback re-check runtime, attempt, channel, wire
  request, target, and current Coordinator winner before it can affect media or
  product state.
- Ensure stale close/send/media callbacks touch only their exact old physical
  handle and cannot close or claim a replacement.
- Remove Service channel-only/tunnel claims as policy gates; retain only a
  contextual physical media locator.

**Non-Goals:**

- No change to the 10-second attempt budget or 15-second human decision window.
- No adapter remaining-time contract change; that remains B5.
- No LAN/P2P/Socket watchdog, retry, group, or connect-loop migration in B4;
  B5 combines their contextual task tokens with remaining-time contracts.
- No second live Coordinator, transport race, fallback scheduler, optimization
  window, or other KUM-28 behavior.

## Decisions

### 1. Evolve the existing model only

`ConnectionAttempt` remains the only runtime attempt type and retains its
existing constructor and raw `deadlineElapsedRealtimeMs` field for source and
behavior compatibility. B1 adds derived domain views and pure predicates; no
runtime producer or consumer is switched to them.

Alternative considered: add a separate future `ConnectionAttempt` model.
Rejected because two representations would create mapping drift and future
dual-owner risk.

### 2. One monotonic timestamp value, no duplicate storage

`MonotonicTimestamp` wraps elapsed monotonic milliseconds and rejects negative
values. `ConnectionAttempt.deadlineAt` is derived from the existing deadline
field, so B1 stores no second deadline and cannot diverge from current data.
Expiration is defined as `now >= deadlineAt`; validity is strictly before it.

Alternative considered: change the constructor field type immediately.
Rejected because that would force a broad runtime cutover and violate the B1
boundary.

### 3. Framework-free clock seam

`MonotonicClock` returns `MonotonicTimestamp`. B1 provides no Android-backed
production implementation and does not wire the interface into existing
components. A mutable `FakeMonotonicClock` exists only in JVM test sources and
supports exact forward advancement.

Alternative considered: call `SystemClock.elapsedRealtime()` from the domain
layer. Rejected because it would make the model Android-dependent and harder
to test deterministically.

### 4. Event currency is a pure contextual predicate

`ConnectionAttemptEventContext` carries only attempt ID, target device ID, and
monotonic occurrence time. `ConnectionAttempt.accepts(context)` requires exact
attempt and target equality plus occurrence before the fixed deadline.
Adapters and callbacks are not routed through this predicate in B1.

### 5. Terminal outcomes are vocabulary, not a new owner

`ConnectionAttemptTerminalOutcome` defines `SUCCESS`, `CANCELED`, and
`TIMED_OUT`. B1 does not store current terminal state in Service or attempt
instances and does not decide outcomes. The evolved Coordinator will own that
decision in a later authorized checkpoint.

### 6. B2 production creation boundary

The existing Coordinator receives an injected attempt-ID factory. A current
Presence selection carries target/runtime/transport availability plus the
existing absolute deadline value; the Coordinator validates that intent,
chooses exactly one transport, creates the ID and complete attempt, records it
as current, and returns the state/effect decision to `SessionOrchestrator`.
`IntercomService` no longer mints the outbound ID and the reducer no longer
constructs the production attempt.

Recovery events carry only the already-existing absolute recovery deadline.
The Coordinator creates the fresh recovery ID and attempt while preserving the
connected attempt's target and single-transport plan. Service does not create a
`RecoveryAttemptSpec`, and the reducer does not construct a recovery attempt.

This is an explicit compatibility phase: the Coordinator owns attempt identity,
target, trigger, plan, and construction, while the existing caller remains the
single source of the absolute deadline value until B3. The Coordinator stores
that value unchanged and neither computes nor rebases it in B2, so there are
not two deadline decision owners.

### 7. B2 first-terminal mailbox

The Coordinator records at most one logical terminal outcome for each attempt.
Timeout, local cancellation, transport-open failure, signaling failure,
disconnect, recovery exhaustion, stop, and WebRTC success/failure are routed
through it before the generic reducer. Later contradictory or duplicate events
cannot overwrite the first outcome or change product state.

Signaling `AttemptOutcome` remains protocol-response/tombstone state in B2; it
is not a second logical attempt-terminal authority. `SessionOrchestrator`
continues to apply every product-state assignment and emit returned effects.

### 8. B2/B3 atomic boundary

B2 deliberately leaves these coupled paths untouched:

- outbound request-delivery and remote-accept deadline rebases;
- unpaired inbound `Long.MAX_VALUE` sentinel and accept-time rebase;
- `RescheduleAttemptDeadline` and Service timer execution;
- paired inbound deadline creation in the existing Coordinator;
- adapter timeout and remaining-budget contracts.

B3 must replace all of them together with Coordinator-owned monotonic deadline
creation and the approved `PendingInboundRequest` representation. B2 must not
introduce a temporary second deadline, compatibility Coordinator, or alternate
inbound representation.

### 9. Bounded pipeline

This change follows an equivalent deterministic full-feature subset:
proposal, specs/design/tasks, one writer apply, targeted and full verification,
one read-only architecture review loop, Draft PR update, and evidence sync.
Archive, deployment, and KUM-28 remain outside B4.

Execution is fixed to Rasen 0.1.3 with `DO_NOT_TRACK=1` and
`RASEN_TELEMETRY=0`. Maximum concurrent write workers is one; the reviewer is
read-only; leaf workers do not delegate; no browser/chrome-use, Greptile,
auto-decompose, automatic merge, or automatic deployment is permitted.

### 10. B3 has one monotonic deadline source

The existing Coordinator receives the B1 `MonotonicClock`. Outbound and
recovery attempts use `clock.now() + 10 seconds`. A paired inbound request uses
its verified request occurrence time plus 10 seconds. A locally accepted
unpaired request uses the accepted event occurrence time plus 10 seconds.
Glare preserves the already-created attempt deadline. Validity is strict:
`now >= deadlineAt` is expired.

WebRTC success and glare may advance product state only while the Coordinator's
monotonic clock is strictly before that deadline. Service keeps the physical
timer until the Coordinator has accepted success and published the matching
`Connected` state, so a callback queued at the boundary cannot cancel timeout.

`ConnectPresenceRequested`, channel-loss events, signaling-loss events, and
WebRTC terminal events no longer carry caller-created recovery or attempt
deadlines. Service therefore cannot create or reset a logical attempt budget.

### 11. Pending inbound confirmation is not an attempt

`PendingInboundRequest` is owned by the Coordinator and contains the verified
wire request key, target, peer, single transport, eligible channel set,
confirmation surface/nonce, and monotonic human-decision deadline. It is not
stored in `ownedAttempt`, is not exposed as `currentAttempt`, and never causes
an attempt timer to be scheduled.

`IntercomState.IncomingConfirmation` is only a product-state projection of the
pending request identity and peer. Immediate unavailable/busy responses create
neither a pending request nor an attempt. Reject, timeout, confirmation-surface
loss, and final channel loss terminate the pending request without creating or
cancelling an attempt timer. A valid local accept creates exactly one inbound
attempt from the verified remote attempt ID and target.

### 12. One explicit attempt-deadline schedule effect

The Coordinator emits `ScheduleAttemptDeadline` exactly once alongside the
first effects for every newly created attempt. Service executes that physical
timer effect. `beginTargetedTransport()` and `startWebRtc()` do not schedule or
rebase timers. Request delivery, remote acceptance, media selection, and media
start preserve the original deadline. The scheduler ignores a duplicate
schedule for the same current attempt so a late duplicate effect cannot move
its timer.

### 13. B3 remains below callback and adapter migration

B3 validates the events it directly changes by runtime, attempt, target/wire
identity, and strict deadline. It does not claim that all Socket, P2P, SDP/ICE,
or delayed adapter callbacks have completed the B4 migration, and it does not
change adapter retry/remaining-budget APIs reserved for B5.

### 14. B4 candidate identity is immutable and complete

`ConnectionCandidateContext` contains the current immutable attempt plus the
verified channel ID, wire request key, TargetLock, transport, request role, and
verified peer. Construction fails unless all identities and the single planned
transport agree. The context is the callback and physical-handle lease; a bare
channel ID is not candidate authority.

The existing Coordinator remains the logical owner through its current attempt,
verified channel registry, candidate set, deterministic media owner, phase, and
terminal mailbox. No second Coordinator, Service-side candidate registry, or
winner election is introduced.

### 15. B4 exact physical handle lifecycle

Service may locate a `SignalingSessionV2` by channel ID, but a close/send/select
operation may use it only when local runtime, wire attempt ID, channel ID,
TargetLock, transport, request role, and verified peer match the immutable
candidate context. A stale `CloseControlChannel` effect checks its existing
runtime/attempt/channel tuple before removal, so it cannot close a replacement
session that reused the physical map slot.

Pending SDP/ICE is keyed by complete candidate context rather than channel ID.
Signaling send completion re-checks exact session object identity before
dispatching success or failure. Stale completion closes or ignores only its old
session and has no logical effect.

### 16. B4 media authorization and callbacks

`StartWebRtc` is still emitted only by the Coordinator winner. Service creates a
single physical `activeMediaContext` after checking the Coordinator's current
attempt, candidate, owner, phase, terminal protocol outcome, and exact session.
That context is a locator, not policy authority. A different stale locator is
closed before the current authorized winner starts; a duplicate matching start
is idempotent.

Remote SDP/ICE delivery, queued-message flush, WebRTC connection/disconnect,
audio-level, and media-error callbacks all carry the same immutable context and
re-check Coordinator ownership immediately before acting. Late callbacks from a
terminal or replaced candidate are ignored and cannot affect a newer manager.

The legacy `tunnelChosen` and channel-only `activeMediaChannelId` gates are
removed. Presence intent is always submitted to the Coordinator, which alone
accepts or rejects it. Runtime teardown clears the contextual locator and exact
physical handles idempotently.

### 17. B4/B5 boundary

B4 does not change LAN Socket connect caps, P2P group/watchdog/retry timing,
`WifiDirectSignalingSocket` loops, or their use of local constants. B5 will add
remaining monotonic budget and contextual task tokens to those adapter-internal
operations atomically. B4 changes no Signaling v2 messages, TargetLock policy,
WebRTC SDP/ICE semantics, pairing, database, UI, permissions, or KUM-28 policy.

### 18. B5 one remaining-budget contract

Every targeted adapter task captures the immutable `ConnectionAttempt` and
reads remaining time as `max(0, deadlineAt - monotonicNow)` immediately before
blocking, scheduling, retrying, or handing off a resource. A local cap uses
`min(localCap, remaining)`. Zero remaining time starts no work, exact-deadline
callbacks have no attempt authority, and no adapter may create, copy, or reset
the total deadline.

Passive discovery without an attempt remains outside the attempt budget. Once
passive discovery resolves a targeted attempt, every operation that can claim,
fail, clean, or hand off that target must capture and re-check the exact attempt
plus its existing endpoint/generation token.

### 19. B5 LAN and HELLO cutover

`LanDiscoveryCoordinator` keeps its physical `LanAttemptLease`, but client
Socket connect uses the smaller of the existing 2-second cap and current
remaining budget. Connect completion and HELLO handoff re-check the same lease
and deadline. `SignalingSessionV2.establish` keeps the existing 1-second HELLO
cap for passive inbound sessions and clamps it to remaining budget whenever an
originating attempt exists. It uses an injected monotonic clock, never wall
clock time.

### 20. B5 P2P task and Socket cutover

P2P targeted connect, connect watchdog, group validation, group-info retries,
Socket transport, and targeted recovery callbacks capture the exact attempt,
TargetLock/address where applicable, and current generation. Their delays are
bounded by remaining budget and re-check context at callback execution. A stale
cleanup may finish closing its old physical resource, but it cannot rediscover,
retry, report failure for, or remove a newer attempt's resources.

`WifiDirectSignalingSocket` replaces wall-clock ready loops with an injected
monotonic deadline. Server accept polling, client connect timeout, and retry
sleep each clamp to current remaining time. Socket ready/failure callbacks are
accepted only while the Tunnel's captured attempt/generation/target context is
current and unexpired.

The delayed Service recovery restart re-checks the exact Coordinator recovery
attempt and remaining budget immediately before reopening adapters. Passive P2P
discovery retries stay passive and may retain local cadence; they gain no
attempt authority.

### 21. B5/B6 boundary

B5 changes timing/context plumbing only. It preserves the 10-second total
budget, current single-transport behavior, existing local caps when enough
budget remains, Signaling v2 and TargetLock semantics, and all product-state
policy. B6 owns consolidated automated regression, the multi-emulator matrix,
and the release physical-test plan. Development-time physical execution is
marked `DEFERRED_TO_RELEASE_CANDIDATE`; it is neither required for B6 nor
reported as passed. B5 does not add T+5 fallback, dual-transport racing, an
optimization window, or `OPTIMIZING`.

### 22. B6 validation layers

B6 validates the complete KUM-27 ownership migration in this order:

1. deterministic JVM tests with fake clock, fake callbacks, and logical
   transport/resource fixtures;
2. Android instrumentation tests that contain their own synthetic PCM source
   and sink under `app/src/androidTest`;
3. two-to-three emulator orchestration on Emulator 36.5 or newer;
4. Gradle unit, Lint, assemble, connected-test where available, and GitHub CI;
5. a fixed-SHA read-only architecture review.

No emulator or fake result is reclassified as physical-device evidence.

### 23. B6 emulator topology and scripts

`scripts/emulator` owns version checks, AVD provisioning, cluster startup,
installation, scenario execution, network-fault injection, evidence capture,
and cluster shutdown. The default API 36 AOSP ATD x86_64 AVD starts on ports
5554, 5556, and 5558 with distinct `-shared-net-id` values. The harness reads
the actual IPv4 address from each node's shared `wlan0` interface instead of
assuming an emulator-version-specific subnet.

The scripts fail closed when the emulator is older than 36.5, the requested AVD
or APK is absent, a node fails to boot, or instrumentation reports failure. They
never select a physical ADB serial unless explicitly supplied.

### 24. B6 synthetic audio boundary

`SyntheticAudioSource` and `TestAudioSink` exist only in the Android test APK.
They generate and inspect deterministic PCM16 frames without using the real
microphone, speaker, Bluetooth SCO, or release `RiderAudioEngine` capture path.
The tests measure frame count, RMS, dominant frequency, sequence loss, first
frame latency, recovery after a controlled interruption, exactly one active
stream, and no delivery after stop.

This proves the automated harness and network framing used by the emulator
matrix. It does not claim real WebRTC acoustic quality, hardware echo
cancellation, or human listening acceptance.

### 25. B6 emulator limitations

Android Emulator shared networking is suitable for LAN sockets, deterministic
protocol exchange, process lifecycle, and synthetic PCM traffic. Wi-Fi Direct
group formation, OEM background policy, Bluetooth SCO, real RF interference,
and real acoustic behavior are not reliable emulator capabilities. Their
domain and callback ordering remain covered by deterministic fakes, while the
hardware behavior is recorded as `DEFERRED_TO_RELEASE_CANDIDATE`.

### 26. B6 completion and handoff

B6 completes when JVM, instrumentation/multi-emulator scenarios, Lint,
assemble, CI, and architecture review pass with P0=0/P1=0; the working tree and
remote are synchronized; and every physical-only item has an explicit Release
Candidate procedure and deferred status. Completion authorizes KUM-27 issue
closure and PR merge, but not KUM-28 behavior or production release.

## Risks / Trade-offs

- [The legacy data-class `copy` paths can create a later deadline] -> B3 removes
  every production deadline rebase and tests that request delivery, remote
  acceptance, glare, and media start preserve the original value.
- [Raw deadline and typed deadline could diverge] -> `deadlineAt` is derived
  from the existing raw value and introduces no second stored field.
- [A pure event predicate could be mistaken for completed callback migration]
  -> Keep it unused by production call paths in B1 and state that routing is
  deferred.
- [A test fake could leak into production] -> Keep JVM fakes under
  `app/src/test`, synthetic PCM under `app/src/androidTest`, and verify release
  assembly plus source-set imports.
- [A pending inbound request can accidentally become a live attempt] -> Store it
  separately, expose no `currentAttempt`, emit no attempt schedule, and test all
  reject/timeout/channel-loss exits.
- [A delayed duplicate schedule can rebase the physical timer] -> Emit one
  schedule effect at creation and make same-attempt duplicate scheduling a
  no-op.
- [Protocol tombstones can be mistaken for logical terminal ownership] -> Keep
  protocol `AttemptOutcome` separate and prove that only the Coordinator's
  logical mailbox decides first-terminal product behavior.
- [A channel ID can be reused after an old effect was queued] -> Require the
  effect runtime/attempt/channel tuple and exact session/candidate context before
  closing or dispatching any completion.
- [Old SDP/ICE or PeerConnection callbacks can act through Service fields] ->
  Key buffers and callback closures by immutable candidate context and re-check
  the Coordinator winner at point of use.
- [A stale physical manager can block the current winner] -> Treat the Service
  context as a disposable locator, close the stale manager, and let only the
  Coordinator-authorized winner start.
- [A local timeout can exceed the total attempt] -> Clamp every targeted
  operation and delay to monotonic remaining budget at the point of use.
- [Old P2P cleanup can revive or remove replacement work] -> Capture attempt,
  target, and generation; permit old physical close completion but reject stale
  retry, rediscovery, failure, and handoff callbacks.

## Migration Plan

1. B1: add the pure domain foundation and deterministic tests. Complete.
2. B2: move production creation and first-terminal ownership into the existing
   Coordinator. Complete and approved.
3. B3: add failing deadline/pending-inbound boundary tests.
4. B3: atomically remove external deadline inputs, all rebases, the sentinel
   attempt, and implicit Service scheduling; add one explicit schedule effect.
5. Reject WebRTC success and glare at the exact total deadline, and cancel the
   physical timer only after Coordinator-authorized success.
6. Run targeted/full gates and fixed-SHA read-only review, then record evidence.
7. B4: add immutable candidate context, exact Service handle matching, and
   context-keyed media buffering.
8. B4: gate signaling completions, SDP/ICE, WebRTC callbacks, and cleanup on the
   current Coordinator candidate/winner; remove Service policy claims.
9. Run targeted/full gates and fixed-SHA read-only review, then record evidence.
10. B5: add the pure remaining-budget contract, then atomically migrate LAN and
    HELLO, P2P task tokens, Socket loops, and delayed recovery restart.
11. Run B5 targeted/full gates and fixed-SHA read-only review.
12. B6: add the automated validation strategy, emulator cluster scripts,
    synthetic PCM instrumentation, and Release Candidate physical plan.
13. Run the full JVM and emulator matrix, strict validation, CI, and fixed-SHA
    read-only review; remediate P0/P1 before closing KUM-27.
14. Keep KUM-28 absent until KUM-27 merges and main CI succeeds.

Rollback is commit-level to the approved B1 head. B2 changes no schema,
protocol, dependency, identity, pairing, database, permission, or persisted
data, and restoring B1 restores the previous production ownership paths.

## Open Questions

None for B6. Adapter cleanup may complete after logical terminal revocation,
but no old task may retry, rediscover, report failure for, hand off, or remove a
replacement attempt. Physical-only behavior remains explicit Release Candidate
work and is not represented as development-time pass evidence.
