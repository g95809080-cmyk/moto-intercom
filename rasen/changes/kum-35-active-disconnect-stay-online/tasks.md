## 1. Contract And Baseline

- [x] 1.1 Bind KUM-35 to main `8dcb3f640e3c5b622da98bc1af68720502427ac8`, exact-main CI `29711575580`, branch `feat/kum-35-active-disconnect-stay-online`, and Linear In Progress.
- [x] 1.2 Freeze local/remote explicit disconnect, unexpected-loss recovery, full-stop separation, exact cleanup ownership, KUM-36 exclusion, and RC physical deferrals.
- [x] 1.3 Strictly validate all four Rasen artifacts before implementation.

## 2. Deterministic Behavior Tests

- [x] 2.1 Add Coordinator tests for connected local disconnect ordering, send failure, duplicate/queued loss, and exact return to `DISCOVERING` without recovery.
- [x] 2.2 Add Coordinator tests for connected and recovering peer `DISCONNECT`, wrong-owner/stale rejection, no recovery failure increment, and unchanged unexpected-loss recovery.
- [x] 2.3 Add pure Service gate/seam tests for exact media/transport release, delayed old cleanup with replacement-state protection, retained runtime/discovery/audio owners, and full-stop-only teardown.
- [x] 2.4 Add UI action/label and state-machine tests distinguishing disconnect, Stop, remote disconnect, and unexpected loss.

## 3. Coordinator, Service, And UI Implementation

- [x] 3.1 Add the exact active-session release effect and converge every local disconnect terminal race plus accepted peer `DISCONNECT` through one Coordinator helper.
- [x] 3.2 Execute exact attempt release in Service by closing media/control ownership and releasing matching LAN/Wi-Fi Direct leases while retaining runtime, discovery, notification, presence, and audio owners.
- [x] 3.3 Wire state-sensitive primary action and labels so current attempts disconnect while online idle/reset performs full Stop.
- [x] 3.4 Preserve target/deadline/winner ownership, stale callback gates, unexpected recovery/reset behavior, and full `STOPPING -> OFFLINE` teardown.

## 4. Automated Verification

- [x] 4.1 Run focused KUM-35 JVM tests and strict Rasen validation.
- [x] 4.2 Run full `testDebugUnitTest`, `lintDebug`, `assembleDebug`, and `assembleDebugAndroidTest`; record totals and APK hashes.
- [x] 4.3 Run applicable KUM-35 instrumentation plus the reusable three-emulator matrix; preserve logs/evidence and do not treat ATD black frames as visual PASS.
- [x] 4.4 Update Sprint 4 verification and RC physical plan with every hardware/OEM row `DEFERRED_TO_RELEASE_CANDIDATE`.

## 5. Review And Delivery

- [x] 5.1 Commit atomically, push, open one KUM-35 Draft PR, and synchronize Linear while KUM-9 remains In Progress and KUM-36 remains Todo.
- [x] 5.2 Complete fixed Base/Head read-only architecture review and remediate all in-scope P0/P1 findings until APPROVED with P0=0 and P1=0.
- [x] 5.3 Mark the PR Ready, merge with a merge commit, retain the remote branch, verify exact-main CI, set KUM-35 Done, and only then start KUM-36. Completed as merge `178e076` with exact-main CI `29714210007` success.
