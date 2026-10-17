## 1. Contracts and service

- [x] 1.1 Archive the approved ADR and document protocol, constraints and acceptance gates.
- [x] 1.2 Implement authenticated HTTPS pair signaling and temporary TURN credentials.
- [x] 1.3 Verify authorization, isolation, expiry, bounded queues and TLS service behavior.

## 2. Native clients

- [x] 2.1 Add Android immutable relay policy and actual selected-pair evidence with unit coverage.
- [x] 2.2 Add network-bound HTTPS signaling and a single-owner native two-device probe.
- [x] 2.3 Add iOS typed relay configuration, injection and stale-callback protection with tests.

## 3. Runnable validation and handoff

- [x] 3.1 Provide local TLS/coturn setup and two-device run instructions.
- [x] 3.2 Run Android build/unit/lint gates and local two-emulator relay media verification.
- [x] 3.3 Record observed evidence and explicitly unverified hardware/public-network/iOS gates.
- [x] 3.4 Commit and obtain a fixed-SHA architecture review; address all P0/P1 findings.
- [x] 3.5 Open and attach a Draft PR, update Linear and report scope, running and tests.

## 4. Authorized public phone validation

- [x] 4.1 Confirm the idle window, enable coturn verbose, and stage an authenticated HTTPS service as a bounded transient unit.
- [x] 4.2 Add probe-only failure diagnostics and optional observed cellular-media gate; build, lint and fixed-SHA architecture review.
- [x] 4.3 Record all phone/emulator attempts, repeat the passing conditions, correlate server sessions, and verify cleanup.
- [x] 4.4 Update handoff, Draft PR and Linear with the exact scope and latest remote checks.
