## 1. Contract

- [x] 1.1 Preserve the approved KUM-44 same-target glare policy and immutable
  attempt ownership.
- [x] 1.2 Record the approved KUM-34 exception: first/second retries use
  attempt-level cleanup and adapter reuse; third failure performs full reset.

## 2. Implementation

- [x] 2.1 Add a shared runtime/TargetLock/transport/deadline reuse gate.
- [x] 2.2 Rebind LAN discovery while closing only its targeted client socket.
- [x] 2.3 Rebind Wi-Fi Direct discovery while preserving eligible group/connect
  state and rejecting stale callbacks.
- [x] 2.4 Keep bounded retry delay and exact current-attempt checks in Service.
- [x] 2.5 Keep `ResetWirelessEnvironment` on the complete KUM-34 cleanup path.

## 3. Verification and Delivery

- [x] 3.1 Run targeted recovery, cleanup, signaling, and reuse JVM tests.
- [x] 3.2 Run full JVM, lint, debug APK, and AndroidTest APK gates.
- [ ] 3.3 Complete fixed Base/Head read-only architecture review with P0=0 and
  P1=0.
  - [x] Validate the first adapter-reuse review findings: discovery could
    bypass the retry delay, an old LAN worker could clear the fresh lease,
    reuse did not require a fresh ID, and production seam coverage was
    incomplete.
  - [x] Add a shared prepared-attempt pause, attempt-owned LAN connect lease,
    synchronized targeted Socket installation, fresh-ID admission, partial
    preparation fallback coverage, and reuse-to-third-reset coverage.
  - [ ] Complete fixed-SHA re-review after remediation.
- [ ] 3.4 Push the exact Head, pass CI, and repeat the physical Wi-Fi Direct
  fault-recovery scenario without peer clicks.
- [ ] 3.5 Synchronize PR and Linear evidence, merge with a merge commit, verify
  main CI, and mark KUM-44 Done.
