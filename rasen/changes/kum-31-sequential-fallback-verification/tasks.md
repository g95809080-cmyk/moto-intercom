## 1. Contract And Traceability

- [x] 1.1 Freeze KUM-31 proposal, sequential-fallback specification, design,
  allowed scope, forbidden scope, and Release Candidate physical deferral.
- [ ] 1.2 Map every KUM-31 development Exit Criterion to an exact owner,
  deterministic test, emulator evidence, and final report row.
- [x] 1.3 Strictly validate the complete Rasen change.

## 2. Coordinator Ownership

- [x] 2.1 Add an exact-attempt overlap-unavailable event and an ordered
  retire-transport effect without changing attempt identity or deadline.
- [x] 2.2 Track retired transports in the existing race owner and exclude them
  from viability, channel adoption, retries, and media ownership.
- [x] 2.3 Accept one sequential switch only after P2P fallback opens and only
  while no preferred requester control candidate exists.
- [x] 2.4 Reject duplicate, stale, replaced, canceled, expired, wrong-plan, and
  post-candidate overlap signals.

## 3. Adapter And Service Routing

- [x] 3.1 Classify only exact targeted P2P-fallback BUSY as an attempt-local
  overlap-unavailable signal; preserve other adapter failures.
- [x] 3.2 Route the immutable attempt to the Coordinator without writing product
  state in `WifiDirectTunnel` or `IntercomService`.
- [x] 3.3 Execute retire-LAN then retry-P2P effects for the exact current attempt
  and leave stale Service effects inert.

## 4. Deterministic Verification

- [x] 4.1 Cover eligible switch ordering, same target/deadline, and exact T+5.
- [x] 4.2 Cover duplicate BUSY, pre-fallback, existing preferred candidate,
  wrong attempt, replacement, cancellation, and T+10 deadline.
- [x] 4.3 Cover retired LAN callback exclusion, fallback terminal failure,
  pure BUSY classification, and physical retire routing.
- [x] 4.4 Run KUM-31 targeted JVM tests and strict Rasen validation.

## 5. Full Gate And Delivery

- [ ] 5.1 Run full JVM, `lintDebug`, `assembleDebug`, and
  `assembleDebugAndroidTest` and record exact totals.
- [ ] 5.2 Run the reusable three-emulator matrix and preserve fresh evidence
  without accepting ATD black screenshots as visual PASS.
- [ ] 5.3 Update the authoritative Sprint 3 report and Release Candidate plan.
- [ ] 5.4 Commit atomically, push, open the KUM-31 Draft PR, and synchronize
  Linear evidence while KUM-8 remains In Progress.
- [ ] 5.5 Complete fixed-SHA read-only architecture review and remediate any
  in-scope P0/P1 until APPROVED with P0=0 and P1=0.
- [ ] 5.6 After final PR CI, turn Ready, merge by merge commit, require green
  main CI, mark KUM-31 and KUM-8 Done, and retain the remote branch.
