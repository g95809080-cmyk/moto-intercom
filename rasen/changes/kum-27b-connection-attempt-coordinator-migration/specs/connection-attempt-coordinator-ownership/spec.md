## ADDED Requirements

### Requirement: Existing Coordinator creates production attempts
The existing `SignalingControlCoordinator` SHALL evolve in place as the only
production creator and logical owner of outbound and recovery
`ConnectionAttempt` instances in B2. It SHALL use an injected ID factory,
validate target intent, choose exactly one transport, preserve target and plan
for recovery, and expose one current attempt. No second Coordinator SHALL be
created.

#### Scenario: Outbound Presence intent
- **WHEN** `SessionOrchestrator` accepts a current Presence selection while
  discovering
- **THEN** the existing Coordinator creates exactly one fresh attempt and
  returns the connecting state plus one targeted-open effect

#### Scenario: Duplicate outbound intent
- **WHEN** another Presence intent arrives after an attempt already became
  current
- **THEN** it creates no second attempt and consumes no additional ID

#### Scenario: Recovery creation
- **WHEN** a current connected attempt loses its media/signaling path and the
  event carries the existing recovery deadline input
- **THEN** the Coordinator creates one fresh recovery attempt preserving the
  target lock and single-transport plan

### Requirement: Service and reducer do not create production attempts
`IntercomService` SHALL provide intent data and execute effects but SHALL NOT
mint outbound/recovery attempt IDs or construct their `ConnectionAttempt`.
The generic reducer SHALL NOT construct outbound/recovery attempts or decide
their terminal transitions on the live orchestrated path.

#### Scenario: Service submits outbound intent
- **WHEN** Service dispatches a selected Presence
- **THEN** the event contains no pre-created attempt ID and no attempt object

#### Scenario: Recovery callback
- **WHEN** Service reports a recovery-eligible disconnect
- **THEN** it supplies no recovery attempt ID or prebuilt recovery attempt

### Requirement: First logical terminal outcome wins
The Coordinator SHALL record at most one logical terminal outcome for each
attempt. A later duplicate or contradictory timeout, cancellation, failure,
disconnect, success, stop, or recovery-exhausted event MUST NOT overwrite the
first outcome or mutate product state for that terminal attempt.

#### Scenario: Timeout precedes failure
- **WHEN** a current attempt times out and a delayed transport failure follows
- **THEN** the outcome remains `TIMED_OUT` and the late failure has no state or
  effect authority

#### Scenario: Cancellation precedes timeout
- **WHEN** local cancellation terminates an attempt before its timer callback
- **THEN** the outcome remains `CANCELED` and the timer callback is stale

#### Scenario: Success precedes a queued timeout
- **WHEN** WebRTC success is accepted before a queued timeout callback
- **THEN** the outcome remains `SUCCESS` and the timeout cannot undo the
  connected state

#### Scenario: Local disconnect precedes a recovery callback
- **WHEN** local disconnect has entered terminal cleanup and a queued WebRTC,
  signaling, or owner-channel close callback arrives
- **THEN** the Coordinator completes the existing cleanup without creating a
  recovery attempt or consuming another attempt ID

### Requirement: SessionOrchestrator remains the state writer
The Coordinator SHALL return state/effect decisions, but only
`SessionOrchestrator` SHALL assign product state. Service, timer callbacks,
transport callbacks, and reducers SHALL NOT directly assign product state.

#### Scenario: Current transport fails
- **WHEN** the Coordinator accepts a contextual transport-open failure
- **THEN** `SessionOrchestrator` applies its deterministic discovering decision
  and Service only executes the returned cleanup effect

### Requirement: B2 preserves the B3 deadline boundary
B2 SHALL treat the existing absolute monotonic deadline as an opaque supplied
value. It MUST NOT compute a second deadline, rebase or extend a deadline,
replace the inbound `Long.MAX_VALUE` sentinel, remove
`RescheduleAttemptDeadline`, change the 10-second behavior, or modify adapter
timeouts. Those coupled changes belong to B3.

#### Scenario: Outbound creation during B2
- **WHEN** the Coordinator creates an outbound attempt
- **THEN** it stores the supplied deadline unchanged and never recomputes it

#### Scenario: Inbound confirmation during B2
- **WHEN** an unpaired inbound request awaits human confirmation
- **THEN** B2 preserves the existing representation and behavior for the later
  atomic B3 pending-request/deadline cutover

### Requirement: KUM-28 remains absent
B2 SHALL preserve one transport per `ChannelPlan` and MUST NOT add T+5 fallback,
dual-channel racing, an optimization window, `OPTIMIZING`, or any KUM-28
behavior.

#### Scenario: B2 review
- **WHEN** the fixed B2 diff is reviewed
- **THEN** no second transport scheduler or KUM-28 product behavior exists

### Requirement: Coordinator owns immutable total deadlines
In B3 the existing Coordinator SHALL use `MonotonicClock` as the only
production source of total attempt deadlines. Outbound and recovery event
inputs MUST NOT carry caller-created deadlines. Request delivery, remote
acceptance, glare handling, media selection, and media start MUST NOT replace
or extend an existing attempt deadline. An attempt is expired when monotonic
time is equal to or greater than its deadline.

#### Scenario: Outbound attempt creation
- **WHEN** a valid Presence selection creates an outbound attempt
- **THEN** its deadline is Coordinator clock time plus exactly 10 seconds

#### Scenario: Recovery attempt creation
- **WHEN** a connected attempt loses its owned channel or media path
- **THEN** the Coordinator creates one recovery attempt with a fresh deadline
  and no Service-supplied deadline value

#### Scenario: Request delivery and remote acceptance
- **WHEN** CONNECT_REQUEST delivery and CONNECT_ACCEPT occur
- **THEN** the attempt keeps its originally created deadline

#### Scenario: Glare changes request role
- **WHEN** glare selects the inbound wire request for the current target
- **THEN** the replacement request role preserves the existing total deadline

#### Scenario: Success or glare arrives at the total deadline
- **WHEN** WebRTC success or a glare winner is processed at or after the
  current attempt's monotonic deadline before the queued timer callback
- **THEN** the Coordinator rejects that state advance, Service keeps the
  physical timer active, and timeout remains able to terminate the attempt

### Requirement: One physical deadline schedule per attempt
The Coordinator SHALL emit one explicit `ScheduleAttemptDeadline` effect when
it creates an attempt. Service SHALL execute that effect but MUST NOT schedule
from transport-open or WebRTC-start paths. A duplicate schedule for the same
current attempt MUST NOT move the physical timer.

#### Scenario: Outbound attempt starts
- **WHEN** the Coordinator creates the outbound attempt
- **THEN** it emits one schedule effect before the targeted transport effect

#### Scenario: Attempt progresses
- **WHEN** signaling is delivered, accepted, or starts media
- **THEN** no second schedule effect is emitted

### Requirement: Pending inbound confirmation is not a connection attempt
An unpaired inbound request awaiting human confirmation SHALL be represented by
a Coordinator-owned `PendingInboundRequest`, not `ConnectionAttempt`.
`IntercomState.IncomingConfirmation` SHALL be a projection without an attempt,
and `connectionAttemptOrNull()` SHALL return null in that state.

#### Scenario: Confirmation is published
- **WHEN** an eligible unpaired request has an available confirmation surface
- **THEN** the Coordinator owns one pending request, exposes no current attempt,
  and emits no attempt deadline schedule

#### Scenario: Local accept is current
- **WHEN** runtime, wire attempt ID, target/channel, nonce, and occurrence time
  match before the decision deadline
- **THEN** the Coordinator creates exactly one inbound attempt whose immutable
  deadline is the accepted occurrence time plus 10 seconds

#### Scenario: Local accept at the decision deadline
- **WHEN** acceptance occurs exactly at the decision deadline
- **THEN** the pending request is expired and no attempt is created

#### Scenario: Pending request terminates without acceptance
- **WHEN** the user rejects, confirmation times out, its final channel closes,
  or no confirmation surface is available
- **THEN** no attempt is created and no attempt timer is scheduled or canceled

#### Scenario: Paired inbound request
- **WHEN** a verified paired request is accepted automatically
- **THEN** its attempt deadline is the request occurrence time plus 10 seconds

### Requirement: B4 and B5 remain deferred
B3 SHALL NOT claim or implement the complete callback/candidate cleanup
migration reserved for B4 or adapter remaining-time contracts reserved for B5.
It MUST preserve the single-transport plan and MUST NOT add KUM-28 fallback or
race behavior.

#### Scenario: B3 review
- **WHEN** the fixed B3 diff is reviewed
- **THEN** it contains only deadline and pending-inbound ownership changes plus
  their direct tests and artifacts

### Requirement: Coordinator owns complete logical candidate context
In B4 each current candidate SHALL be identified by immutable runtime, attempt,
channel, wire request, TargetLock, transport, request role, and verified peer
context. The existing Coordinator SHALL remain the candidate and media-winner
authority. Service maps and physical handle presence MUST NOT select, replace,
or authorize a candidate.

#### Scenario: Candidate context is created
- **WHEN** a verified control channel belongs to the current attempt
- **THEN** all candidate identity fields agree with that attempt, its one
  planned transport, and its verified target

#### Scenario: Candidate context is stale
- **WHEN** runtime, attempt, channel, wire request, target, transport, role, or
  verified peer differs from the current Coordinator candidate
- **THEN** it has no signaling, media, winner, or cleanup authority

### Requirement: Service cleanup targets exact physical handles
Service SHALL execute close effects only against a physical session matching
the effect runtime, attempt, and channel. A stale close, send completion, or
reader callback MUST NOT remove, close, or mutate a replacement session.

#### Scenario: Old close effect follows replacement
- **WHEN** an old attempt's close effect arrives after the channel map slot
  contains a replacement session
- **THEN** the replacement remains open and owned by its current context

#### Scenario: Old send completion follows replacement
- **WHEN** an old session completes a send after it is no longer the exact map
  object for that candidate
- **THEN** no sent/failed event is applied to the current candidate

### Requirement: Media signaling and callbacks require current winner context
Service SHALL carry immutable candidate context through pending and delivered
SDP/ICE, queued-message flush, WebRTC state, disconnect, audio, and error
callbacks and re-check the current Coordinator attempt, owner channel, phase,
and target immediately before use. Pending media SHALL be keyed by full
candidate context, not bare channel ID.

#### Scenario: Current winner receives media signaling
- **WHEN** the exact current Coordinator winner receives SDP/ICE
- **THEN** Service queues or delivers it only for that matching media context

#### Scenario: Late media callback follows replacement or terminal outcome
- **WHEN** an old candidate emits SDP/ICE, PeerConnection state, disconnect,
  audio, or error after replacement or terminal cleanup
- **THEN** it cannot mutate product state, feed the newer manager, cancel its
  timer, or alter current UI/media state

### Requirement: Service media state is a physical locator only
B4 SHALL remove `tunnelChosen` and channel-only media-owner checks as policy
gates. Service MAY retain one contextual physical media locator for exact
execution and idempotent cleanup, but the Coordinator SHALL authorize every
winner and product-state transition.

#### Scenario: Duplicate or stale StartWebRtc effect
- **WHEN** a duplicate matching start or a stale different start is executed
- **THEN** the matching physical manager remains single and stale physical
  state cannot block or replace the Coordinator winner

### Requirement: B5 and KUM-28 remain deferred
B4 MUST NOT change adapter-internal remaining-time, watchdog, retry, group, or
Socket-loop contracts. It MUST keep one transport per attempt and MUST NOT add
T+5 fallback, dual-channel racing, an optimization window, or `OPTIMIZING`.

#### Scenario: B4 review
- **WHEN** the fixed B4 diff is reviewed
- **THEN** it contains upper-layer candidate/callback/exact-cleanup migration
  only, with no B5 timing or KUM-28 behavior

### Requirement: Targeted adapter work consumes remaining total budget
B5 SHALL calculate remaining time only from the immutable attempt deadline and
a monotonic clock. Each targeted local timeout or delayed retry SHALL use the
smaller of its existing cap and current remaining time. Zero remaining time
MUST start no work, and no adapter may reset or extend the attempt deadline.

#### Scenario: Local cap and remaining-budget boundaries
- **WHEN** remaining time is 0, 1, cap-1, cap, or greater than cap
- **THEN** the operation receives respectively no budget, 1, cap-1, cap, or cap
  milliseconds without changing the attempt deadline

#### Scenario: Passive discovery has no attempt
- **WHEN** LAN or P2P performs passive discovery without a targeted attempt
- **THEN** its discovery cadence remains local and cannot claim attempt,
  terminal, target, channel, or media authority

### Requirement: LAN connect and HELLO are attempt-bounded
Targeted LAN Socket connect SHALL capture the exact attempt lease and clamp its
2-second cap to remaining budget. HELLO exchange SHALL retain its 1-second cap
and clamp it when an originating attempt exists. Completion and handoff MUST
re-check the same attempt and an unexpired deadline.

#### Scenario: LAN budget expires before connect or HELLO
- **WHEN** no remaining time exists before LAN connect or HELLO begins
- **THEN** no current channel is handed off and no replacement attempt is
  released, failed, or otherwise mutated

### Requirement: P2P targeted tasks carry immutable context
The P2P targeted adapter SHALL capture the exact attempt, target, and generation
context for connect, watchdog, group validation, group-info retry, Socket
transport, and targeted cleanup/recovery callbacks. A callback that is expired
or no longer current MUST NOT retry, rediscover, report a current failure, hand
off a Socket, or mutate replacement resources.

#### Scenario: Attempt changes during P2P group validation
- **WHEN** an old group-info, watchdog, retry, ready, or failure callback arrives
  after replacement
- **THEN** it has no effect on the replacement attempt or its physical handles

#### Scenario: Old cleanup finishes late
- **WHEN** physical group or Socket cleanup for a terminal attempt finishes
  after its logical budget
- **THEN** closing the exact old resource is allowed, but no retry or discovery
  action is scheduled with old attempt authority

### Requirement: P2P Socket loops use monotonic remaining time
`WifiDirectSignalingSocket` SHALL use monotonic time for its ready deadline and
SHALL clamp accept polling, client connect timeout, and retry sleep to current
remaining time. It SHALL close stale Sockets without a ready callback.

#### Scenario: Socket retry reaches the exact deadline
- **WHEN** a connect attempt or retry delay consumes the final remaining budget
- **THEN** no subsequent connect iteration or ready callback is allowed

### Requirement: Delayed recovery restart revalidates current attempt
Service SHALL re-check the exact current recovery attempt and positive remaining
budget immediately before delayed adapter restart.

#### Scenario: Recovery is replaced during cleanup backoff
- **WHEN** the delayed restart for recovery attempt A runs after attempt B or a
  terminal state became current
- **THEN** no adapter is reopened for attempt A

### Requirement: B6 and KUM-28 remain deferred
B5 MUST keep one transport per attempt and MUST NOT add T+5 fallback,
dual-transport racing, a 1-second optimization window, or `OPTIMIZING`. Full
regression and physical verification remain B6 work.

#### Scenario: B5 review
- **WHEN** the fixed B5 diff is reviewed
- **THEN** it contains only remaining-budget and stale adapter-task migration
  plus direct tests and artifacts
