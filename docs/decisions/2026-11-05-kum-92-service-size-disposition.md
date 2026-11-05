# KUM-92 Intercom Service Size Disposition

Date: 2026-11-05

Status: Accepted (P1 scope reduced)

## Context

The P1 finding claimed that `IntercomService.kt` (3,299 lines) and
`SignalingControlCoordinator.kt` (3,341 lines) form a coupling bottleneck at 28%
of main sources, so that any cross-cutting change — a new transport, for
example — has to touch both files and carries an oversized change radius.

Measurements taken on `f848352` do not support that mechanism:

- **No reference in either direction.** `SignalingControlCoordinator` appears
  zero times in `IntercomService.kt` and `IntercomService` zero times in
  `SignalingControlCoordinator.kt`. SCO is constructed by
  `SessionOrchestrator.kt:39`; the two classes meet only through
  `SessionEvent` / `SessionEffect` / `IntercomState`.
- **Co-change is rare.** Across the last 200 commits, 27 touch IntercomService,
  10 touch SCO, and 6 touch both. Those 6 average 20.8 `.kt` files each: they
  are whole-tree refactors, not routine edits. 78% of IntercomService changes
  and 40% of SCO changes need no edit to the other file.
- **A new transport is the cheapest kind of change, not the most expensive.**
  It touches 9 files and 63 enum sites, two of which are exhaustive `when`
  blocks the compiler forces you to complete
  (`ConnectionAttempt.kt:143`, `ConnectionAttempt.kt:167`,
  `IntercomService.kt:2544`). Change radius is wide but shallow and
  compiler-checked.
- **The 28% figure is stale.** Two files are 6,649 of 26,789 main `.kt` lines,
  i.e. 23.6%.

The actual constraint is intra-class responsibility density. IntercomService
holds 2,860 class-body lines, 112 methods and 66 fields across six roles:
Service lifetime, effect interpreter, transport factory, notification surface,
presence aggregation, and audio callbacks. SCO holds 3,313 class-body lines,
111 methods, 42 fields and seven families of invariant (attempt lifecycle,
glare, inbound confirmation, recovery episode, connected-loss window, media
selection, timeouts) with no explicit representation anywhere in the code.

## Decision

1. Land only the zero-risk extraction: the 312-line tail of pure predicates is
   moved verbatim to `SessionGuards.kt` (`2deace3`, 24 top-level functions).
2. Do not pursue mechanical port extraction of the transport cluster.
   Measured twice, rejected twice.
3. Move the remaining work to a separate issue. Record P1 as **mitigated at file
   level, not at class level**: IntercomService's class body is still 2,860
   lines and untouched; only the file shrank, from 3,299 to 2,986 lines.

## Rejected: mechanical extraction of the transport cluster

Both attempts were implemented and measured against the "reduce, not relocate"
bar:

| Attempt | Result |
| --- | --- |
| 19-member `TransportAdapterHost` port plus anonymous-object implementation | IntercomService **+32 lines** |
| 17-field `TransportAdapterCallbacks` bundle | IntercomService −27 lines, plus a new 128-line file |

`startDiscoveryTransports` reads six service fields, but it is long because it
assembles two adapters that the service still owns. Those adapters reach service
state at 35 points (`wifiTunnel` 17, `lanDiscovery` 15, close-owner 3). A port
that exposes that surface is at least as large as the body it replaces. This is
a hub class; the marginal return of moving code out of a hub is exhausted.

A real reduction requires transferring ownership: a `TransportSession` that owns
both adapters and their close lifecycle, leaving the service with one
collaborator. That is a ~250-line diff rewriting ~15 methods and the teardown
ordering in `abortResourcesAndResumeDiscovery` and `stopIntercom` — a design
change, not a move.

## Precondition for that work

`:app:testDebugUnitTest` fails on the development machine at `f848352`, before
any edit of this issue, including for the pure-JUnit `AdaptiveWindowTest`
("Test process encountered an unexpected problem", empty cause).
`.github/workflows/android-ci.yml` runs the same task successfully, so the task
is alive but the local executor is not. Ownership transfer must not start until
the local executor runs again, or until the work is explicitly gated on CI.

## Follow-up issue (scope draft)

Title: Transfer transport adapter ownership out of IntercomService.

Scope: introduce `TransportSession` owning `WifiDirectTunnel`,
`LanDiscoveryCoordinator` and their close lifecycle; the service keeps a single
reference and forwards session events.

Non-goals: splitting SCO; the notification and intent surface; any behavioural
change to discovery, recovery, or shutdown ordering.

Prerequisites: a runnable local JVM test suite (or an explicit CI-first
workflow), plus a characterization test over start → discovery → transport ready
→ abort → resume discovery.

Acceptance: IntercomService class body below 2,600 lines; the 35 adapter state
touch points and the three close-owner paths collapse to the session; the 27
`Transport.*` references in the service reduce to the adapter factory boundary;
`testDebugUnitTest` green in CI with no change to teardown semantics.
