## 1. Contract And Baseline

- [x] 1.1 Bind KUM-36 to main `178e07652b3672a6834950fdd213dd37f5389284`, exact-main CI `29714210007`, branch `feat/kum-36-sprint-4-final-automation-matrix`, and Linear In Progress.
- [x] 1.2 Freeze KUM-32 through KUM-35 behavior, KUM-36 verification-only scope, production-release prohibition, and RC physical deferrals.
- [x] 1.3 Strictly validate proposal, spec, design, and tasks before implementation.

## 2. Deterministic Final Acceptance

- [x] 2.1 Add a bounded A-B/C composite test proving target lock, visible B recovery, preferred T+3 window, immutable T+10 deadline, C rejection, and one B media owner.
- [x] 2.2 Add repeated-final-failure coverage proving two bounded retries, exact third-failure `RESETTING`, cleanup ownership, stale-event rejection, and exact reset completion.
- [x] 2.3 Add cancellation and resource-lifetime assertions for queued timeout/failure races, active disconnect, full Stop, hot audio ownership, and no duplicate media owner.
- [x] 2.4 Run the focused KUM-36 suite together with the existing socket-level third-node and KUM-32 through KUM-35 focused suites.

## 3. Android And Emulator Matrix

- [x] 3.1 Add androidTest-only KUM-36 acceptance evidence for target-locked third-node recovery, T+3 fallback/only-winner, three-failure reset, stale completion, and cancellation.
- [x] 3.2 Add a `sprint4-final` emulator scenario with separately named results and include it in `all` without weakening existing scenarios.
- [x] 3.3 Verify synthetic PCM metrics/recovery/stop, shared networking, fault/recovery, process restart, and test-only source isolation.
- [x] 3.4 Keep emulator Wi-Fi Direct/RF/OEM limitations explicit and every hardware-only row `DEFERRED_TO_RELEASE_CANDIDATE`.

## 4. Full Verification And Evidence

- [x] 4.1 Run focused JVM and strict Rasen validation.
- [x] 4.2 Run full `testDebugUnitTest`, `lintDebug`, `assembleDebug`, and `assembleDebugAndroidTest`; record totals and APK hashes.
- [x] 4.3 Run focused `sprint4-final` and full three-emulator matrices, scan all evidence, inspect screenshots, and hash the archive.
- [x] 4.4 Update Sprint 4 and RC verification documents with exact source, test, emulator, limitation, and deferred-physical evidence.

## 5. Review And Delivery

- [ ] 5.1 Commit atomically, push, open one KUM-36 Draft PR, and synchronize Linear while KUM-9 remains In Progress.
- [ ] 5.2 Complete fixed Base/Head read-only architecture review and remediate all in-scope P0/P1 findings until APPROVED with P0=0 and P1=0.
- [ ] 5.3 Mark the PR Ready, merge with a merge commit, retain the remote branch, verify exact-main CI, set KUM-36 and KUM-9 Done, and only then continue the next approved work item.
