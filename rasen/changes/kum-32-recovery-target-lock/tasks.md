## 1. Contract And Baseline

- [x] 1.1 Freeze the KUM-32 proposal, recovery-target-lock spec, design, allowed scope, forbidden scope, and Release Candidate deferrals.
- [x] 1.2 Reconcile the Sprint 4 verification index with the merged KUM-37 head, merge commit, and green main CI before recording KUM-32 evidence.
- [x] 1.3 Strictly validate the complete Rasen change and deterministic full-feature-equivalent execution contract.

## 2. Deterministic Regression Tests

- [x] 2.1 Add recovery tests proving a fresh attempt retains B while Presence selection/order for C cannot replace the target.
- [x] 2.2 Add verified-Socket tests proving responder B is eligible and responder C is rejected during recovery.
- [x] 2.3 Add adapter-lease and real-HELLO tests proving recovery ingress is target-bound before open work, an accepted discovery claim cannot preserve a group after wrong/stale actual identity, active connect remains gated, and only the matching attempt releases it.
- [x] 2.4 Add shared UI/notification text tests for named and fallback recovery peers.
- [x] 2.5 Add a deterministic A/B/C race through the production admission seam proving C closes with P2P cleanup, B is admitted, exactly one `StartWebRtc` is emitted, and only B passes the media gate.

## 3. Recovery Target Enforcement

- [x] 3.1 Seed separate LAN and Wi-Fi Direct ingress-validation leases with the immutable recovery attempt without opening fallback transport early.
- [x] 3.2 Bind active adapter connect work only from the existing `OpenTargetedTransport` effect and release both matching leases on winner/cleanup.
- [x] 3.3 Add the current-recovery `TargetLock` check to Service verified-control-channel installation, close rejected sessions, and remove a non-target P2P group after either Service admission rejection or current-context HELLO establishment failure.
- [x] 3.4 Preserve existing winner, deadline, retry, cleanup, product-state, and WebRTC ownership behavior.

## 4. Recovery Presentation

- [x] 4.1 Add one pure shared recovery status formatter based on `IntercomState.Recovering.peer`.
- [x] 4.2 Use the shared text in MainScreen and the foreground notification, refreshing the notification on active product-state changes.

## 5. Verification And Delivery

- [x] 5.1 Run KUM-32 targeted JVM tests and strict Rasen validation.
- [x] 5.2 Run full JVM, `lintDebug`, `assembleDebug`, and `assembleDebugAndroidTest` and record exact results.
- [x] 5.3 Run the applicable reusable three-emulator matrix and preserve fresh evidence without representing hardware-only rows as PASS.
- [x] 5.4 Update the Sprint 4 verification index and Release Candidate physical queue with KUM-32 evidence/deferrals.
- [x] 5.5 Commit atomically, push, open the KUM-32 Draft PR, and synchronize Linear while KUM-33 remains Todo.
- [x] 5.6 Complete fixed-SHA read-only architecture review and remediate in-scope P0/P1 until APPROVED with P0=0 and P1=0.
- [x] 5.7 Mark the PR Ready, merge with a merge commit, verify main CI, close KUM-32, and keep the remote branch before starting KUM-33.
