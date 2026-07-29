## Context

`ChannelClosed` already routes loss of the connected media-owner channel into
`Recovering`. `SignalingSendFailed` does not: after removing the same owner
channel it calls ordinary attempt termination, producing
`AbortAttemptAndResumeDiscovery` and losing the target before the close event
arrives.

Physical evidence on main `ee33b4a` captured exactly this ordering on Xiaomi 13:
`Connected -> Discovering` immediately after Wi-Fi was disabled. The peer
correctly entered `Recovering`, retried the stale endpoint, then exhausted its
bounded attempts.

## Decision

When all of the following are true, route `SignalingSendFailed` through the
existing `recoverConnectedAttempt(..., restartConnectedDiscovery = true)` path:

- product state is `Connected`;
- the active context phase is `CONNECTED`;
- the failed channel is the media owner.

After accepting the recovery transition, forget the old active channels exactly
as the existing `ChannelClosed` path does. A `TERMINATING` phase remains on the
active-disconnect cleanup path, so a user disconnect cannot be converted into
automatic recovery.

## Boundaries

- No second recovery owner or network callback.
- No deadline extension or target rewrite.
- No retry when the user explicitly disconnects or stops.
- No T6 simultaneous-request or release work.

## Verification

Add a deterministic regression proving that owner send failure creates a fresh
recovery attempt with the same TargetLock and emits restart/deadline effects,
while retaining the existing local-disconnect send-failure regression. Run full
Gradle, CI, fixed-SHA architecture review, and exact-Head two-device Wi-Fi
disable/enable recovery.
