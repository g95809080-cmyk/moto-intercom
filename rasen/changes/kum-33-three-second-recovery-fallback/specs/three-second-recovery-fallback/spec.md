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
When signaling loss requires physical adapter teardown, Service SHALL report
each transport ready independently for the exact recovery attempt without
adding the normal failed-attempt discovery backoff. LAN MAY report ready after
its synchronous startup. Wi-Fi Direct SHALL report ready only after startup
group cleanup and DNS-SD service discovery setup complete. The Coordinator
SHALL remain the authority that opens the preferred and due alternate
transports.

#### Scenario: Preferred adapter becomes ready before T+3
- **WHEN** the exact current preferred adapter reports ready before the fast-window boundary
- **THEN** the Coordinator opens that preferred transport immediately while the original T+3 milestone remains scheduled from attempt creation

#### Scenario: Alternate adapter is not ready at T+3
- **WHEN** the original fallback milestone arrives before the exact alternate adapter is ready
- **THEN** the Coordinator records fallback due without issuing a physical open

#### Scenario: Due alternate becomes ready later
- **WHEN** the exact alternate adapter reports ready after fallback is due
- **THEN** the Coordinator opens it immediately without resetting T+3 or T+10

#### Scenario: LAN startup does not wait for Wi-Fi Direct
- **WHEN** LAN is ready while Wi-Fi Direct startup cleanup is still running
- **THEN** LAN eligibility is processed independently and Wi-Fi Direct is not opened before its own readiness callback

#### Scenario: Ordinary failed attempt resumes discovery
- **WHEN** no recovery attempt is being resumed
- **THEN** the existing bounded discovery backoff remains in effect

### Requirement: Recovery timing events fail closed
Recovery transport-ready events SHALL affect only the exact current unexpired
recovery attempt; the same gate applies to fallback milestones, transport
callbacks, and media. A
successful recovery SHALL leave exactly one media owner and SHALL make its late
fallback and prior-attempt callbacks inert.

#### Scenario: Old attempt callback arrives
- **WHEN** a callback or milestone carries the prior connected attempt or a replaced recovery attempt
- **THEN** it cannot open a transport, change target, claim media, or extend the deadline

#### Scenario: Duplicate or wrong-transport readiness arrives
- **WHEN** readiness repeats for the same adapter or names a transport outside the immutable plan
- **THEN** it cannot open another adapter or change race state

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
