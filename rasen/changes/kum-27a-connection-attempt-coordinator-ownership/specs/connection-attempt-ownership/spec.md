## ADDED Requirements

### Requirement: One logical attempt owner
The system SHALL have exactly one `ConnectionAttemptCoordinator` responsible for owning a verified pending inbound request and for creating, tracking, and ending the current logical `ConnectionAttempt`. It SHALL own the outbound attempt ID factory, target lock, trigger, single-transport channel plan, immutable total deadline, candidate set, terminal outcome, and winner selection. An accepted inbound attempt SHALL adopt the verified remote `WireRequestKey.attemptId`; a recovery attempt SHALL be created by the same Coordinator while preserving the original target and channel plan.

#### Scenario: Outbound attempt creation
- **WHEN** `SessionOrchestrator` accepts a current Presence selection
- **THEN** the Coordinator creates one attempt with a fresh ID, verified target inputs, one planned transport, the requested trigger, and one total deadline

#### Scenario: Inbound request requires confirmation
- **WHEN** a current verified responder channel delivers a valid unpaired `CONNECT_REQUEST` and a confirmation surface is available
- **THEN** the Coordinator creates one `PendingInboundRequest`, not a `ConnectionAttempt`, and neither Service nor an adapter may mint an attempt

#### Scenario: Inbound request cannot be presented
- **WHEN** a valid unpaired `CONNECT_REQUEST` arrives while no confirmation surface is available
- **THEN** the Coordinator immediately records and emits `CONFIRMATION_UNAVAILABLE` and creates neither a `PendingInboundRequest` nor a `ConnectionAttempt`

#### Scenario: Inbound request is busy
- **WHEN** a valid inbound `CONNECT_REQUEST` cannot be admitted in the current product state
- **THEN** the Coordinator immediately records and emits `BUSY` and creates neither a `PendingInboundRequest` nor a `ConnectionAttempt`

#### Scenario: Inbound request is accepted
- **WHEN** a current human-accept event matches the pending runtime, wire request, channel, target, remote runtime, nonce, and decision deadline and at least one eligible verified channel remains
- **THEN** the Coordinator atomically consumes the pending request and creates exactly one inbound attempt using the verified remote ID and one immutable deadline at `event.occurredAtElapsedMs + 10s`

#### Scenario: Recovery attempt creation
- **WHEN** a connected attempt enters recovery
- **THEN** the Coordinator creates the recovery attempt with a fresh local ID, the original `TargetLock`, the original single-transport `ChannelPlan`, and a new immutable total deadline

### Requirement: Pending inbound confirmation is pre-attempt state
A verified inbound request awaiting the existing human confirmation SHALL be represented by a Coordinator-owned `PendingInboundRequest` outside `ConnectionAttempt`. It SHALL contain the local runtime, verified wire request key and remote attempt ID, pinned target device and remote runtime, single transport, eligible verified channel set, confirmation channel/surface/nonce, and one immutable 15-second decision deadline. `INCOMING_CONFIRMATION` SHALL project this pending request without exposing it as the current attempt, scheduling a total-attempt timer, selecting a winner, or authorizing media.

#### Scenario: Pending request ends without acceptance
- **WHEN** the user rejects, the decision deadline expires, the runtime rolls over, identity no longer matches, or the final eligible verified channel closes
- **THEN** the Coordinator consumes the pending request, cancels the exact confirmation surface, cleans its channels, and creates no local `ConnectionAttempt`

#### Scenario: Late accept after requester timeout
- **WHEN** an accept action arrives after the requester exhausted its original budget and no current eligible verified channel remains
- **THEN** the action is rejected as stale and cannot create an attempt, reopen a channel, or start media

#### Scenario: Paired inbound auto-accept
- **WHEN** a current verified paired request requires no human decision
- **THEN** the Coordinator creates the inbound attempt immediately with one immutable total deadline and no pending-confirmation sentinel

### Requirement: SessionOrchestrator remains the product-state writer
`SessionOrchestrator` SHALL remain the only writer of `OFFLINE`, `DISCOVERING`, `INCOMING_CONFIRMATION`, `CONNECTING`, `OPTIMIZING`, `CONNECTED`, `RECOVERING`, `RESETTING`, and `STOPPING`. The Coordinator MAY return accepted decisions and effects, but `IntercomService`, transport adapters, timer callbacks, Socket callbacks, and WebRTC callbacks MUST NOT write product state directly.

#### Scenario: Adapter failure
- **WHEN** a transport adapter reports a current candidate failure
- **THEN** it emits a contextual event and only `SessionOrchestrator` applies the Coordinator's resulting product-state decision

#### Scenario: Future optimizing state
- **WHEN** KUM-27 is implemented
- **THEN** no code enters `OPTIMIZING` unless a later approved issue explicitly authorizes that transition

### Requirement: Immutable monotonic total deadline
Every logical attempt SHALL receive exactly one total deadline based on a monotonic clock. That deadline MUST NOT be rebased or extended after transport open, `CONNECT_REQUEST` delivery, remote decision, `CONNECT_ACCEPT`, media start, adapter retry, watchdog, or recovery cleanup. `IntercomService` MAY host the timer mechanism only as an effect executor; it MUST NOT define or modify the deadline.

#### Scenario: Connect request is delivered
- **WHEN** `CONNECT_REQUEST` send completion is received
- **THEN** the current attempt retains its original deadline and no reschedule effect carries a later deadline

#### Scenario: Remote accepts
- **WHEN** `CONNECT_ACCEPT` is accepted before the total deadline
- **THEN** WebRTC uses the same attempt and the same original deadline

#### Scenario: Deadline expires
- **WHEN** the monotonic clock reaches the current attempt deadline
- **THEN** the Coordinator records the first terminal outcome, cancels all candidates, emits cleanup effects, and requests a deterministic product-state transition

#### Scenario: Inbound human confirmation
- **WHEN** a responder displays the existing 15-second human confirmation surface
- **THEN** that immutable deadline belongs to `PendingInboundRequest`, no local attempt total timer exists yet, and it MUST NOT mutate, extend, or revive the requester's 10-second total attempt

#### Scenario: Accepted inbound deadline
- **WHEN** a valid current local accept consumes a pending request
- **THEN** the newly created local inbound attempt receives exactly one deadline at the accept event's monotonic timestamp plus 10 seconds, and that value is never copied or rebased

### Requirement: Bounded targeted adapters
LAN, Wi-Fi Direct, Socket, group validation, and watchdog operations SHALL execute only the target and transport supplied by the Coordinator. Every local timeout and retry delay MUST be bounded by the remaining total attempt budget. Adapter cleanup MAY complete after the product deadline, but it MUST be idempotent and MUST NOT revive, replace, or extend the terminal attempt.

#### Scenario: LAN connect budget
- **WHEN** LAN starts a Socket connect with less remaining budget than its local connect cap
- **THEN** it uses the remaining budget and reports failure without changing the attempt deadline

#### Scenario: P2P validation budget
- **WHEN** P2P group validation, connect watchdog, Socket ready retry, or BUSY retry would exceed the remaining attempt budget
- **THEN** the operation stops at the remaining budget and reports a contextual candidate failure

#### Scenario: Cleanup outlives product deadline
- **WHEN** asynchronous P2P group removal finishes after the attempt is already terminal
- **THEN** cleanup completes without changing product state, selecting a channel, or restarting that terminal attempt

### Requirement: Context-complete callback validation
Every attempt-sensitive asynchronous callback SHALL validate the current runtime, attempt, candidate or channel, target device, and expected remote runtime immediately before transferring a resource or emitting a success. A discovery-only callback MAY omit attempt context only while it remains incapable of opening media, selecting a target, or ending an attempt.

#### Scenario: Stale Socket callback
- **WHEN** a LAN or Wi-Fi Direct Socket callback belongs to an old runtime, attempt, channel, target device, or remote runtime
- **THEN** the Socket and related candidate resources are closed and the callback has no product-state or media effect

#### Scenario: Stale P2P callback
- **WHEN** a delayed P2P group, ActionListener, watchdog, or retry callback belongs to a superseded attempt context
- **THEN** it cannot validate the new attempt, claim its group, schedule another targeted retry, or start signaling

#### Scenario: Signaling and media callback
- **WHEN** HELLO, REQUEST, ACCEPT, REJECT, BUSY, SDP, ICE, or PeerConnection callbacks arrive
- **THEN** the callback is accepted only for the current pinned runtime, attempt, channel, target device, remote runtime, and winner where applicable

#### Scenario: Notification callback
- **WHEN** an incoming-confirmation action is delivered
- **THEN** runtime, verified wire request key, channel, target device, remote runtime, nonce, and decision deadline all match the current `PendingInboundRequest`, and acceptance also requires a current eligible verified channel before it can create an attempt

### Requirement: One winner and one media session
The Coordinator SHALL be the sole logical winner selector. Exactly one current verified control channel MAY receive media ownership, and only a Coordinator effect MAY start WebRTC. `activeMediaChannelId` or similar Service fields MAY exist only as physical resource locators after the award; they MUST NOT be independent winner authorities.

#### Scenario: Duplicate channels
- **WHEN** multiple verified channels represent the same single-transport wire request
- **THEN** the Coordinator deterministically awards at most one channel and emits close or reject effects for every non-winner

#### Scenario: Late non-winner
- **WHEN** a non-winner or stale channel reports accept, media, disconnect, or failure after the award
- **THEN** it is closed without replacing the winner or creating a second WebRTC session

### Requirement: Deterministic resource cleanup
Terminal and stale outcomes SHALL produce idempotent cleanup for pending inbound requests, LAN Sockets, Wi-Fi Direct Socket and group resources, pending control channels, signaling sessions, WebRTC and audio resources, total-deadline timers, adapter watchdogs and delayed tasks, confirmation surfaces, and tunnel claims. Logical ownership SHALL remain in the Coordinator while `IntercomService` and adapters execute the physical cleanup effects.

#### Scenario: Attempt terminal cleanup
- **WHEN** an attempt is canceled, rejected, busy, timed out, fails, disconnects, or loses glare
- **THEN** all resources owned by that attempt are canceled or closed without clearing resources belonging to a newer attempt

#### Scenario: Pending inbound cleanup
- **WHEN** a pending inbound request is rejected, times out, loses its final eligible channel, is replaced, or belongs to an old runtime
- **THEN** its prompt and channels are canceled or closed exactly once, no attempt timer is canceled because none was scheduled, and late physical cleanup cannot create an attempt

#### Scenario: Runtime stop
- **WHEN** the Service runtime stops or rolls over
- **THEN** runtime generation invalidation prevents all old attempt and adapter callbacks from claiming resources in the new runtime

### Requirement: Atomic ownership migration
KUM-27B SHALL migrate each responsibility with an atomic target-owner cutover and old-owner removal. It MUST NOT ship two runtime Coordinators, two total-deadline owners, Service and Coordinator both ending attempts, adapter deadline extension, or a legacy callback path that bypasses current-attempt validation.

#### Scenario: Coordinator cutover
- **WHEN** the existing signaling coordinator is evolved or renamed into `ConnectionAttemptCoordinator`
- **THEN** `SessionOrchestrator` owns exactly one Coordinator instance and no compatibility bridge makes both Coordinators authoritative

#### Scenario: Deadline cutover
- **WHEN** total-deadline ownership moves to the Coordinator
- **THEN** deadline rebasing code, the `Long.MAX_VALUE` inbound sentinel, pre-accept current-attempt projection, and their regression tests are removed or replaced in the same reviewable checkpoint as `PendingInboundRequest`

### Requirement: KUM-27 scope remains independent of KUM-28
KUM-27A and KUM-27B MUST preserve the single-transport `ChannelPlan` and MUST NOT implement Transport Race, T+5 fallback, an optimization window, dual-transport arbitration, notification redesign, pairing changes, Signaling v2 semantic changes, or Sprint 4 recovery policy.

#### Scenario: KUM-27 implementation review
- **WHEN** the KUM-27 change is reviewed
- **THEN** no second transport starts, no T+5 task exists, and KUM-28 remains Todo

### Requirement: Deterministic verification gate
KUM-27B SHALL provide virtual-clock and delayed-callback tests for immediate inbound unavailable/busy responses, pending inbound accept/reject/timeout/final-channel loss, requester-first timeout, success, attempt timeout, cancel, replacement, late callback, winner uniqueness, and cleanup ownership, followed by the full Android unit-test, lint, assemble, and CI gates. KUM-27B MUST remain blocked until KUM-27A receives independent architecture approval with P0=0 and P1=0.

#### Scenario: KUM-27A gate
- **WHEN** the ownership artifacts are complete
- **THEN** a read-only `motointercom-product-architect` review against fixed base and head SHAs decides whether KUM-27B may begin
