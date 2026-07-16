# KUM-26 Evidence Gate Decision

Date: 2026-07-16

Status: Accepted

## Decision

KUM-26 is no longer indefinitely blocked by the absence of a third physical Android phone.
The current one-to-one product can complete KUM-26 when all accepted evidence below passes and
the unavailable physical topology is recorded as residual risk.

## Accepted Evidence

- Deterministic three-logical-node tests for A (local), B (locked target), and C (non-target).
- Resource-boundary tests proving that wrong identity, wrong runtime, stale callbacks, and timeout
  orderings close or reject the relevant Socket, attempt, adapter, and tunnel claim.
- Bidirectional LAN, Wi-Fi Direct, WebRTC, restart, disconnect, background, and recovery checks on
  the two available physical Android phones.
- Complete unit-test, Lint, assemble, and GitHub Actions gates.
- PC protocol endpoint evidence for framing, parser, malformed identity, timeout, half-close,
  delayed response, duplicate message, and Socket-disconnect behavior.

The PC endpoint, emulator, test fixture, fake adapter, and JVM node are software evidence only.
They are not a third physical Android phone.

## Known Limitation

The following evidence is not available and must not be reported as tested or passed:

> Three physical Android devices simultaneously sharing one wireless environment while A locks B
> and C participates in real LAN or Wi-Fi Direct discovery and group formation.

## Risk Treatment

Accepted residual risk: deferred physical validation.

- Do not claim three-phone physical validation.
- Preserve deterministic wrong-target and resource-cleanup coverage in CI.
- Add the physical topology check if a third Android phone becomes available later.
- The deferred topology is not a hard dependency for the current one-to-one product release.

## Scope Boundary

This decision changes KUM-26 acceptance evidence only. It does not authorize Transport Race,
Sprint 4 recovery policy, or any KUM-27 implementation.
