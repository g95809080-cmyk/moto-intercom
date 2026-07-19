## 1. Contract And Ownership

- [x] 1.1 Freeze the KUM-37 proposal, hot-audio specification, design, allowed scope, forbidden scope, and Release Candidate physical deferrals.
- [x] 1.2 Record the Sprint 4 audio ownership ADR and its narrow supersession of the KUM-27A RiderAudioEngine lifetime row.
- [x] 1.3 Strictly validate the complete Rasen change and full-feature Codex runtime configuration.

## 2. Runtime Audio Owner

- [ ] 2.1 Add a Service-owned `AudioSessionController` that owns one route and one retained engine for the online runtime.
- [ ] 2.2 Add an exact single-session lease that rejects concurrent opens, invalidates callbacks before close, and ignores stale release of an older session.
- [ ] 2.3 Close the current media session, retained engine, and route exactly once only on full runtime Stop/destroy.

## 3. Retained Engine And Replaceable WebRTC

- [ ] 3.1 Keep the audio device module, factory, source, local track, executor, and VOX state in `RiderAudioEngine` across sessions.
- [ ] 3.2 Move PeerConnection, sender, SDP/ICE state, remote-track callbacks, and session errors into a replaceable `RiderMediaSession` handle.
- [ ] 3.3 Preserve one authorized WebRTC owner and no remote sender/playout when no media handle is active.

## 4. Service And Manager Wiring

- [ ] 4.1 Make `IntercomManager` borrow/release a media handle without closing retained audio platform resources.
- [ ] 4.2 Keep audio resources and route status across attempt/recovery cleanup while continuing to close signaling, LAN, Wi-Fi Direct, Socket, and current PeerConnection resources.
- [ ] 4.3 Bind route/engine callbacks to the immutable runtime and preserve all current Coordinator winner and media-context checks.

## 5. Deterministic And Android Verification

- [ ] 5.1 Add JVM lifecycle tests for reuse, exact close order, concurrent-open rejection, stale callback lease, stale release, and Stop-only platform teardown.
- [ ] 5.2 Update attempt-resource tests to prove recovery no longer closes or resets the runtime audio owner.
- [ ] 5.3 Add Android instrumentation proving two sequential actual WebRTC sessions reuse retained engine resources and never overlap.
- [ ] 5.4 Run KUM-37 targeted JVM/instrumentation tests and strict Rasen validation.

## 6. Full Gate And Delivery

- [ ] 6.1 Run full JVM, `lintDebug`, `assembleDebug`, and `assembleDebugAndroidTest` and record exact results.
- [ ] 6.2 Run the applicable reusable emulator matrix and preserve fresh evidence without representing hardware-only rows as PASS.
- [ ] 6.3 Update Sprint 4 verification and the Release Candidate physical plan with KUM-37 evidence and deferrals.
- [ ] 6.4 Commit atomically, push, open the KUM-37 Draft PR, and synchronize Linear while KUM-32 remains Todo.
- [ ] 6.5 Complete fixed-SHA read-only architecture review and remediate in-scope P0/P1 until APPROVED with P0=0 and P1=0.
- [ ] 6.6 Turn the PR Ready, merge with a merge commit, verify green main CI, close KUM-37, retain the remote branch, and only then allow KUM-32.
