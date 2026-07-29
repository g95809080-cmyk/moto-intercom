## 1. Regression

- [x] 1.1 Bind the change to `main@ee33b4a08f951d73a5168a3fe9032e7c40a7dd0b`.
- [x] 1.2 Add a deterministic connected-owner send-failure recovery test.
- [x] 1.3 Preserve the existing user-disconnect send-failure test.
- [x] 1.4 Add a socket-pair regression for established reader EOF classification.

## 2. Implementation

- [x] 2.1 Route only established media-owner send failure to existing recovery.
- [x] 2.2 Preserve TargetLock, fresh attempt identity, and monotonic deadlines.
- [x] 2.3 Keep channel-close and active-disconnect behavior unchanged.
- [x] 2.4 Preserve reader I/O failures as transport failures without weakening
  malformed-frame protocol handling.

## 3. Gates

- [x] 3.1 Run targeted and full Gradle gates plus strict Rasen validation.
- [x] 3.2 Commit, push, create one Draft PR, and pass CI.
- [ ] 3.3 Obtain fixed Base/Head architecture approval with P0=0 and P1=0.
- [ ] 3.4 Pass exact-Head Wi-Fi disable/enable automatic recovery on both devices.
- [ ] 3.5 Merge by merge commit, pass exact-main CI, and synchronize Linear.
