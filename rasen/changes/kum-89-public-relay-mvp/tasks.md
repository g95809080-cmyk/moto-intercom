## 1. Contracts and service

- [ ] 1.1 Archive the approved ADR and document protocol, constraints and acceptance gates.
- [ ] 1.2 Implement authenticated HTTPS pair signaling and temporary TURN credentials.
- [ ] 1.3 Verify authorization, isolation, expiry, bounded queues and TLS service behavior.

## 2. Native clients

- [ ] 2.1 Add Android immutable relay policy and actual selected-pair evidence with unit coverage.
- [ ] 2.2 Add network-bound HTTPS signaling and a single-owner native two-device probe.
- [ ] 2.3 Add iOS typed relay configuration, injection and stale-callback protection with tests.

## 3. Runnable validation and handoff

- [ ] 3.1 Provide local TLS/coturn setup and two-device run instructions.
- [ ] 3.2 Run Android build/unit/lint gates and local two-emulator relay media verification.
- [ ] 3.3 Record observed evidence and explicitly unverified hardware/public-network/iOS gates.
- [ ] 3.4 Commit and obtain a fixed-SHA architecture review; address all P0/P1 findings.
- [ ] 3.5 Open and attach a Draft PR, update Linear and report scope, running and tests.
