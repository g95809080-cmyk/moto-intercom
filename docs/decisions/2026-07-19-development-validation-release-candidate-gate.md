# Development Validation and Release Candidate Physical Gate Decision

Date: 2026-07-19

Status: Accepted

## Decision

MotoIntercom feature development, intermediate Issue closure, Sprint progress,
and intermediate PR merge no longer require continuously connected physical
Android devices. Development gates use deterministic JVM tests, fake monotonic
time/transports/callbacks, Android instrumentation, two-to-three emulator
integration, Gradle Managed Devices where practical, CI, and fixed-SHA
architecture review.

Hardware-only and human-perception checks remain mandatory, but execution is
concentrated at the final Release Candidate gate. Until then their status is
`DEFERRED_TO_RELEASE_CANDIDATE`, never PASS.

## Development evidence order

1. Deterministic JVM tests.
2. Fake clock, fake transport, fake callbacks, and resource-ownership fixtures.
3. Single-emulator instrumentation.
4. Two-to-three emulator shared-network integration.
5. Gradle Managed Devices or equivalent CI automation.
6. Fixed-SHA architecture review with P0=0 and P1=0.

Ordinary test, build, Lint, emulator, and review failures must be diagnosed and
remediated without waiting for physical hardware.

## Deferred physical evidence

The following rows cannot be represented as passed by JVM, fake, emulator, or
CI evidence:

- OEM Wi-Fi Direct behavior;
- real RF distance, interference, and radio coexistence;
- OEM background and lock-screen restrictions;
- Bluetooth SCO;
- real microphone, speaker, and hardware echo cancellation;
- human listening quality; and
- long-duration power, thermal, and background survival.

Their complete procedures live in
`docs/verification/release-candidate-physical-plan.md`. Any mandatory failure or
unexecuted required row blocks production release unless the user explicitly
approves a revised Release Gate.

## Intermediate merge boundary

An Issue PR may become Ready and merge with a merge commit after its applicable
automated/emulator gates, CI, architecture review, clean/synchronized Git state,
and Linear/PR evidence pass. Deferred physical rows do not block that merge.

Remote branches are retained. Force push, history rewrite, branch deletion,
production signing, deployment, and store release remain forbidden.

## Release boundary

This decision does not authorize production release. At Ready for Release, work
must pause for the consolidated physical acceptance matrix, release build and
signing checks, privacy/permission review, rollback verification, and explicit
user authorization.

## Scope boundary

This decision changes validation timing and evidence classification only. It
does not change MotoIntercom product behavior, connection ownership, protocol
semantics, target identity, database compatibility, or approved Sprint scope.
