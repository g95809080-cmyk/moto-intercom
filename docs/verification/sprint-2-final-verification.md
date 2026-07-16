# Sprint 2 Final Verification

Status: **IN PROGRESS — NOT READY TO MERGE**

## Bound revision

- Repository: `g95809080-cmyk/moto-intercom`
- Branch: `feat/sprint2-signaling-v2`
- Base: `fb01c3c42f0ba294462532be5ac2b12a0252e62c`
- Verified production-code baseline: `5825a8fece4772df5c726314e7789450a299b86c`
- Final report commit: **PENDING**
- Final GitHub Actions run: **PENDING**
- Pull request: [#2](https://github.com/g95809080-cmyk/moto-intercom/pull/2) (Draft)
- Linear: [KUM-26](https://linear.app/kuma999/issue/KUM-26/s26-完成三逻辑节点与两真机-target-lock-协议验收) (In Progress)

This report is the single evidence index for Sprint 2. PR and Linear updates must
summarize and link here instead of maintaining separate test totals.

## Approved evidence boundary

- Device A: MI 6, Android 9.
- Device B: 2211133C, Android 16.
- Logical node C: deterministic non-target protocol/state/resource test node.
- PC endpoint: controlled Signaling v2 software endpoint only.
- A PC, emulator, fake adapter, or JVM node is not a third physical phone.

Deferred physical validation / Accepted residual risk:

> Three physical Android devices simultaneously share one wireless environment
> while A locks B and C participates in real LAN or Wi-Fi Direct discovery and
> group formation.

This deferred topology is not claimed as passed and is not a hard dependency for
the current one-to-one product. It does not authorize KUM-27, Transport Race, or
Sprint 4 recovery policy.

## Automated evidence

| Check | Revision | Result | Evidence |
| --- | --- | --- | --- |
| Existing Android unit gate | `5825a8f` | PASS | 162 tests; 0 failures, 0 errors, 0 skipped |
| Lint | `5825a8f` | PASS | 0 errors, 28 warnings |
| Debug assemble | `5825a8f` | PASS | `assembleDebug` completed |
| GitHub Actions | `5825a8f` | PASS | [run 29477315103](https://github.com/g95809080-cmyk/moto-intercom/actions/runs/29477315103) |
| KUM-26 logical acceptance class | uncommitted candidate | PASS | 6 tests from `M:\` |
| PC endpoint self-test | working tree | PASS | 4 tests |
| Working-tree Android unit gate | acceptance working tree | PASS | 168 tests; 0 failures, 0 errors, 0 skipped |
| Working-tree Lint | acceptance working tree | PASS | 0 errors, 28 warnings |
| Working-tree debug assemble | acceptance working tree | PASS | APK SHA-256 `FEDAD7427C51BC2B33FB1EFE058C49B4CDD38BD8001E82F221157E2BFAD92824` |
| Final committed unit/Lint/assemble gate | final acceptance commit | PENDING | Must bind the committed SHA |
| Final PR CI | final report commit | PENDING | Must bind the final commit and run ID |

The first attempt to run the KUM-26 class from the Unicode checkout failed before
test execution. The same class passed from the existing `M:\` mapping. This is the
known AGP/KSP/Kotlin SourceSets path-portability issue, not a product-test failure.

## Logical A/B/C and resource evidence

The final automated gate must retain coverage for all of the following:

- non-target C cannot satisfy A's TargetLock for B;
- wrong device, wrong runtime, missing identity, empty identity, and malformed
  identity fail closed;
- a superseded runtime cannot register a control channel as the current Service
  session;
- a stale accepted activation cannot start WebRTC, retain its Socket, or retain a
  tunnel claim after the attempt ends;
- transport-open failure, timeout ordering, and replaced-attempt callbacks cannot
  leave CONNECTING stuck or take over a newer attempt;
- recovery retains the original TargetLock and single-transport ChannelPlan;
- rejected paths do not create pre-accept WebRTC or write PairingRepository;
- a third-party request receives channel-scoped BUSY without replacing the active
  attempt, wire key, or media owner.

Current status: **TARGETED AND FULL WORKING-TREE TESTS PASS; COMMIT/CI BINDING PENDING**.

## PC protocol evidence

`tools/kum26_peer_test.py` currently passes four self-tests:

- frame round trip;
- oversized-frame rejection before body read;
- responder runtime identity pinned across frames;
- exact Signaling v2 envelope keys.

Prior controlled-device evidence at `5825a8f` showed a real current-Socket HELLO
and channel-scoped response. Final evidence must continue to label this as a PC
protocol endpoint, not physical-device acceptance.

Current status: **SELF-TEST PASS; COMMIT/CI BINDING PENDING**.

## Two-device physical evidence

Live precheck on 2026-07-16 found both authorized phones online. The evidence
harness successfully produced manifests, device logs, service/audio snapshots,
and database checks while explicitly recording a smoke capture as `NotRun`.
Both databases returned `integrity=ok` during that harness smoke.
Both installed `base.apk` files matched the local debug APK SHA-256
`FEDAD7427C51BC2B33FB1EFE058C49B4CDD38BD8001E82F221157E2BFAD92824`.
The final harness records sanitized database summaries before and after each
scenario and rejects any change to either installation-stable identity.

Previously verified checkpoints retained as historical evidence:

- two-device Wi-Fi Direct discovery and current-Socket HELLO;
- one accepted attempt and one PeerConnection per phone;
- CONNECTED and disconnect cleanup;
- delayed high-importance Android 16 notification Reject action with no
  RiderAudioEngine, WebRTC, or PeerConnection creation;
- controlled BUSY behavior without replacing the active A/B call;
- identity restoration and pairing-database integrity after controlled tests.

These checkpoints do not replace the final commit-bound matrix below.

| Scenario | Required result | Status |
| --- | --- | --- |
| LAN, A requester / B responder | Verified identity, CONNECTED, audio, clean disconnect | PENDING |
| LAN, B requester / A responder | Verified identity, CONNECTED, audio, clean disconnect | PENDING |
| Wi-Fi Direct, A requester / B responder | Verified identity, CONNECTED, audio, clean disconnect | PENDING |
| Wi-Fi Direct, B requester / A responder | Verified identity, CONNECTED, audio, clean disconnect | PENDING |
| Restart A | Runtime rollover; reconnect only to original target | PENDING |
| Restart B | Old runtime rejected; refreshed target reconnects | PENDING |
| Disconnect initiated by A | Both sides reach expected terminal/discovery states | PENDING |
| Disconnect initiated by B | Both sides reach expected terminal/discovery states | PENDING |
| Background/lock-screen | Current notification action only; no stale action takeover | PENDING |
| Planned-transport recovery | Original target and plan retained; deterministic exit | PENDING |
| Database integrity | Both databases `ok`; expected peer records retained | PENDING |

Human listening must be recorded as human evidence. ADB audio-state output alone
must not be represented as proof that both riders heard intelligible audio.

## Review evidence

- Review at `5825a8f`: APPROVED, P0 = 0, P1 = 0.
- Final independent review at the final report commit: **PENDING**.
- Review base must remain `fb01c3c42f0ba294462532be5ac2b12a0252e62c`.

## Gate decision

- KUM-26 may close: **NO**.
- Sprint 2 may close: **NO**.
- PR #2 may become Ready: **NO**.
- PR #2 may merge: **NO**.
- KUM-27 may start: **NO**.

Blocking evidence:

1. Acceptance assets and this report are not committed or pushed.
2. The passing 168-test/Lint/assemble gate is not yet bound to a committed and pushed SHA.
3. The complete two-device matrix has not been captured at the final commit.
4. Final PR CI and independent review have not run at the final commit.
