## 1. Contract and regression

- [x] 1.1 Bind KUM-42 to `main@5c6fb0e82dfa3607aab802b7809ac3b03b535688`.
- [x] 1.2 Add deterministic complete, malformed, local, refresh, and expiry tests.
- [x] 1.3 Preserve stale identity, TargetLock, and Socket verification boundaries.

## 2. Bounded implementation

- [x] 2.1 Parse a complete UDP hello into an existing LAN rider device.
- [x] 2.2 Register it under a deterministic endpoint with a monotonic TTL.
- [x] 2.3 Expire absent UDP endpoints on receive timeout or subsequent traffic and republish only on change.
- [x] 2.4 Confirm NSD, attempt/deadline/target, signaling, pairing, and audio behavior are unchanged.

## 3. Delivery gates

- [x] 3.1 Run targeted tests and full `testDebugUnitTest`, `lintDebug`, `assembleDebug`, and `assembleDebugAndroidTest`.
- [x] 3.2 Validate Rasen strictly and record exact APK hashes.
- [ ] 3.3 Commit, push, and create one Draft PR linked to KUM-42.
- [ ] 3.4 Obtain fixed Base/Head architecture approval with P0=0 and P1=0.
- [ ] 3.5 Pass exact-Head A→B and B→A LAN selection/connection without full Stop.
- [ ] 3.6 Pass PR CI, merge by merge commit, pass exact-main CI, and synchronize KUM-42 evidence/state.
