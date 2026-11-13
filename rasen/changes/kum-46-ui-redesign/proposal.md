## Why

The released MotoCom UI combines connection, discovery, settings, audio, VOX, and logs in one programmatic scrolling screen and presents several inferred states as facts. KUM-46 replaces it with the confirmed multi-page design so riders can navigate naturally while every displayed status and action remains grounded in the existing product-state and service contracts.

## What Changes

### Implementation update (2026-08-10)

The source tree has moved beyond the original placeholder checkpoint. Compose
now renders the four route surfaces inside the existing MainActivity/MainScreen
boundary. Automatic reconnect is a real persisted setting with serialized
state-machine semantics; manual discovery refresh, audio mute/route controls,
VOX settings, and Help/Feedback are real callback paths. Feedback deliberately
uses an explicit system chooser and sends no automatic session logs, audio,
URI, or URI-grant data.

The original bullets below remain the historical KUM-46 design contract. The
non-device verification gate is being extended with API 36 instrumentation;
two-device, hardware, and manual-listening checks remain deferred and are not
inferred from emulator evidence.

- Replace the single long screen with Home, Discover Riders, and Settings top-level routes plus the Settings > Logs child route, natural navigation-panel/gear/audio-entry/back behavior, route restoration, and independent per-page scrolling.
- Recreate the confirmed fixed-light visual language with Android Views, XML layouts/resources, neon-green accent, centered 520dp maximum content width, safe WindowInsets, 320dp–600dp support, 1.3x font-scale usability, and accessible controls.
- Preserve and rewire the existing Start/Stop, real Presence selection, incoming accept/reject, nickname persistence, real audio-source, remote-rider, product-state, audio-level, and log callbacks.
- Render all nine `IntercomState` variants truthfully; keep recovery bound to its original target; remove heuristic VOX status and premature transport/WebRTC readiness claims.
- Keep unimplemented mute, VOX, manual audio-route, auto-reconnect, manual-rescan, and help/feedback entries clickable, with one exact no-side-effect “功能开发中” dialog; provide a real static About dialog.
- Retain the latest 300 lines from the current UI session, update only the buffer while Logs is hidden, and support Copy All with accessible confirmation.
- Add deterministic UI-logic tests, Android build gates, emulator navigation/responsive/accessibility evidence, two-device regression evidence, visual comparisons, and the required independent architecture review.
- Bring the authoritative PRD into this branch at `docs/product/2026-07-17-motocom-ui-redesign-prd.md`.

## Capabilities

### New Capabilities

- `motocom-multipage-ui`: Complete KUM-46 contract for routes, truthful state rendering, real actions, discovery, settings, logs, placeholder behavior, responsive layout, accessibility, lifecycle restoration, and verification evidence.

### Modified Capabilities

None. No existing Rasen capability specs are present.

## Impact

- UI-owned Kotlin: `MainActivity`, `MainScreen`, and small single-purpose UI helpers/tests.
- Android resources: page layouts, values, wide-screen dimensions, drawables, vector icons, and fixed-light styles.
- Existing `IntercomService.Listener` callbacks and public actions are reused; `SessionOrchestrator` remains the sole product-state writer and `IntercomService` remains lifecycle/effect owner.
- No state-machine, Target Lock, signaling, socket identity, transport, WebRTC, recovery, Room schema, Gradle dependency, Compose, Fragment, or new UI-library changes.
