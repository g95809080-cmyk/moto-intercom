## Context

KUM-89 follows the approved public-network ADR. The existing nearby runtime owns one AudioSessionController, one ADM and at most one media PeerConnection. Its TCP signaling and target-lock authorization cannot safely be relabeled as an Internet transport. This change therefore delivers an isolated, runnable developer validation path before production orchestration or automatic switching.

## Goals / Non-Goals

**Goals:** authenticated HTTPS signaling for a pre-provisioned pair; short-lived coturn REST credentials; immutable per-session relay policy on Android and iOS; actual selected-pair and decoded-media evidence; reproducible two-device validation without a microphone.

**Non-Goals:** production cloud UI, automatic fallback, overlapping media sessions, four-person/SFU, GME, production deployment, release/signing, or claiming cellular/background acceptance from an emulator.

## Decisions

- Use Node.js 24 built-in HTTPS and bounded long polling rather than adding a WebSocket dependency. Provision device token hashes and pair membership out of band. Server-derived sender identity, call IDs and session leases reject cross-pair, stale-runtime and expired messages. A second live runtime cannot take over a device.
- Generate coturn REST HMAC credentials on the server only. Never ship the shared TURN secret to clients. Validate required settings and credential expiry; relay mode fails closed without usable credentials.
- Keep nearby defaults unchanged. Pass a typed ICE policy into the existing media-session open operation, retaining the same factory/ADM. Forced relay uses native RELAY transport policy. Do not bind the whole Android process to a network.
- The Android HTTPS client can use an explicit Android Network for both DNS and sockets. A test CA is trusted only by that client. Signaling network binding is not media-routing evidence.
- The developer instrumentation probe acquires LegacyRuntimeOwnership before creating audio resources and releases it after the RTC executor finishes disposal. Each device has one controller and one PC. Test-only capture injection and decoded PCM observations exercise the existing native media path.
- Record the transport-selected candidate pair by following selectedCandidatePairId. Missing selection is unknown, not an arbitrary nominated-pair substitute. Record local/remote candidate type, transport, network, RTT and counters, scoped to the current session.
- iOS receives typed ICE server credentials and an explicit nearby/relay mode. Preserve the nearby host-only guard. Generation and PeerConnection identity checks prevent close/restart callbacks from affecting a new session. Keep playAndRecord/voiceChat and the documented background-audio contract.

## Risks / Trade-offs

- HTTPS polling adds signaling latency but does not carry audio; bounded waits and independent publishing avoid head-of-line blocking.
- Local coturn proves relay negotiation and media, not public cellular routing, real-world P95 latency or iOS background audio. These remain explicit device gates.
- Windows cannot compile the conditional GoogleWebRTC iOS adapter; Swift package tests and an approved SDK/Xcode build must be reported separately.
- A validation probe is not production public-network integration. Target-lock/coordinator integration and break-before-make switching require a later reviewed change.

## Review

Read-only architecture design review: APPROVED, P0=0, P1=0. Base and reviewed design Head: a72cea7c1ff1879a2b6175b17881d0686c22d9c4. Next gate: implement this developer MVP, validate, then review a fixed source Head. This is not a source or release approval.
