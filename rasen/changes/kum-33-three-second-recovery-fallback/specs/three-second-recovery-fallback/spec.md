## ADDED Requirements

### Requirement: Recovery starts with the last successful transport
Every recovery attempt SHALL have a fresh `ConnectionAttemptId`, preserve the
connected rider's immutable `TargetLock` and available transport set, and make
the last successful connected transport its preferred transport. Only the
existing Coordinator SHALL create this plan.

#### Scenario: LAN was the connected transport
- **WHEN** a dual-transport session connected on LAN becomes unreachable
- **THEN** the fresh recovery attempt prefers LAN and retains Wi-Fi Direct as its alternate

#### Scenario: Wi-Fi Direct was the connected transport
- **WHEN** a dual-transport session connected on Wi-Fi Direct becomes unreachable
- **THEN** the fresh recovery attempt prefers Wi-Fi Direct and retains LAN as its alternate

#### Scenario: Only one transport was available
- **WHEN** a single-transport session becomes unreachable
- **THEN** recovery preserves that single transport and creates no alternate milestone

### Requirement: Recovery fast window is immutable and monotonic
The Coordinator SHALL start the recovery fast window at recovery-attempt
creation, SHALL schedule the alternate transport at exactly T+3 seconds, and
SHALL preserve the attempt's immutable T+10 total monotonic deadline. Normal
non-recovery attempts SHALL retain their existing T+5 fallback schedule.

#### Scenario: Fast window has not elapsed
- **WHEN** the recovery clock is before T+3
- **THEN** only the last successful transport is eligible to open and the alternate remains scheduled

#### Scenario: Fast window reaches its boundary
- **WHEN** the recovery clock reaches T+3 exactly
- **THEN** the alternate transport becomes eligible for the same attempt and TargetLock

#### Scenario: Total deadline remains unchanged
- **WHEN** recovery opens its preferred or alternate transport
- **THEN** neither action rewrites or extends the original T+10 recovery deadline

#### Scenario: Normal connection attempt runs
- **WHEN** a non-recovery dual-transport attempt starts
- **THEN** its fallback remains scheduled at T+5 and its optimization/deadline behavior is unchanged

### Requirement: Cleanup completion cannot add recovery backoff
When signaling loss requires physical adapter teardown, Service SHALL complete
the mandatory cleanup and then report the exact recovery attempt as transport
ready without adding the normal failed-attempt discovery backoff. The
Coordinator SHALL remain the authority that opens the preferred and due
alternate transports.

#### Scenario: Cleanup completes before T+3
- **WHEN** the exact current recovery environment becomes ready before the fast-window boundary
- **THEN** the Coordinator opens the preferred transport immediately and schedules the original T+3 milestone

#### Scenario: Cleanup completes at or after T+3
- **WHEN** the exact current recovery environment becomes ready after the alternate milestone is already due
- **THEN** the Coordinator opens the preferred transport followed by the alternate without resetting either clock

#### Scenario: Ordinary failed attempt resumes discovery
- **WHEN** no recovery attempt is being resumed
- **THEN** the existing bounded discovery backoff remains in effect

### Requirement: Recovery timing events fail closed
Recovery-ready events, fallback milestones, transport callbacks, and media SHALL
affect only the exact current unexpired recovery attempt. A
successful recovery SHALL leave exactly one media owner and SHALL make its late
fallback and prior-attempt callbacks inert.

#### Scenario: Old attempt callback arrives
- **WHEN** a callback or milestone carries the prior connected attempt or a replaced recovery attempt
- **THEN** it cannot open a transport, change target, claim media, or extend the deadline

#### Scenario: Recovery succeeds before T+3
- **WHEN** the preferred transport reconnects and WebRTC reaches connected before the fast window expires
- **THEN** the attempt becomes connected with one media owner and the late alternate milestone is rejected

#### Scenario: Non-target rider responds first
- **WHEN** rider C responds while recovery remains locked to rider B
- **THEN** C is rejected under the existing KUM-32 target and cleanup gates

### Requirement: Automated evidence and physical deferral remain explicit
KUM-33 SHALL pass deterministic JVM tests, applicable instrumentation and the
multi-emulator matrix, full Gradle gates, CI, and fixed-SHA architecture review
with P0=0 and P1=0. Real OEM/RF recovery timing and transport switching SHALL
remain `DEFERRED_TO_RELEASE_CANDIDATE`.

#### Scenario: Development gate passes
- **WHEN** virtual-clock, fake transport/callback, emulator, CI, review, Git, PR, and Linear evidence agree
- **THEN** KUM-33 may complete and merge without connected physical devices

#### Scenario: Hardware timing has not run
- **WHEN** OEM radio concurrency, RF interference, Bluetooth, acoustics, power, thermal, or background checks are unavailable
- **THEN** those rows remain deferred and are not represented as PASS
