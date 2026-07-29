## 1. Contract and regression

- [x] 1.1 Bind KUM-41 to `main@d6bc6fdb8dc3e390f4686fe36a5fd66cc5db4ac8` and freeze the completion-race scope.
- [x] 1.2 Add deterministic success/failure completion tests for a session that closes before main-thread delivery.
- [x] 1.3 Retain Coordinator local-disconnect, send-failure, queued-close, stale-callback, and exact-release coverage.

## 2. Bounded implementation

- [x] 2.1 Freeze each signaling send result into an immutable `SessionEvent` before posting to main.
- [x] 2.2 Remove the mutable session-open early return while preserving physical close and Coordinator identity gates.
- [x] 2.3 Confirm the change does not modify protocol, attempt/deadline/target, discovery policy, identity, database, or audio ownership.

## 3. Delivery gates

- [x] 3.1 Run targeted JVM tests and full `testDebugUnitTest`, `lintDebug`, `assembleDebug`, and `assembleDebugAndroidTest`.
- [x] 3.2 Validate the Rasen change strictly and record exact APK hashes.
- [x] 3.3 Commit, push, and create one Draft PR linked to KUM-41.
- [ ] 3.4 Obtain fixed Base/Head read-only architecture approval with P0=0 and P1=0.
- [ ] 3.5 Install the exact Head APK on both devices and pass LAN disconnect→rediscovery→reconnect from an available selection entry without full Stop.
- [ ] 3.6 Pass PR CI, merge by merge commit, pass exact-main CI, and synchronize KUM-41 evidence/state.
