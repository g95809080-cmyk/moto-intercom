## Context

### Implementation update (2026-08-10)

The current source uses Compose screen surfaces while retaining the same
one-Activity route/controller and callback boundaries. Automatic reconnect is
owned by the serialized signaling coordinator, persisted by the Activity, and
does not interrupt an already active recovery attempt when toggled. Help and
feedback are now truthful real actions; feedback is chooser-mediated and does
not attach diagnostics or media automatically. Any sections below that call
these entries placeholders describe the earlier checkpoint and are superseded
by this addendum.

KUM-46 implements the confirmed 2026-07-17 MotoCom UI PRD and its three-phone design reference. Today `MainActivity` owns permissions, service binding, preferences, dialog forwarding, and UI callbacks, while the programmatic `MainScreen` places product status, discovery, nickname editing, audio, inferred VOX state, and a continuously redrawn log view inside one `ScrollView`.

The backend boundary is already suitable for a UI-only change. `IntercomService.Listener` publishes status, all nine `IntercomState` variants, audio source plus a real Bluetooth boolean, Presence snapshots, audio level, logs, remote-rider identity, incoming-confirmation lifecycle, toasts, and errors. Listener attachment replays status, product state, audio source, Presence, and a non-empty remote rider; it does not replay past logs or audio level. Existing actions cover start/stop/disconnect, current-Presence connection, and nonce-bound incoming accept/reject. `SessionOrchestrator` is the product-state writer; `IntercomService` owns Android lifecycle, resources, and effects.

The Activity currently also stores a rider-name preference, while `IntercomService` persists a nonblank start name through `LocalIdentityStore`. The redesign must give Settings an explicit validated save action without changing identity during an active runtime. The implementation branch starts from `origin/main@9107c86`; the uncommitted Rasen 0.1.5 deployment is unrelated and must remain untouched.

## Goals / Non-Goals

**Goals:**

- Deliver Home, Discover Riders, Settings, and Settings > Logs as independent, naturally navigable pages within one Activity.
- Match the reference hierarchy and fixed light/soft-gray/neon-green visual language using Android Views and XML resources.
- Make route, scroll, and draft-edit state UI-owned while every product, peer, transport, audio, Presence, and incoming-request fact comes from current service/domain data.
- Retain every real callback/action and the existing permission/service lifecycle, while removing heuristic VOX and premature readiness presentation.
- Support 320dp–600dp widths, centered content capped at 520dp, cutouts/rounded corners/system bars, 1.3x font scale, TalkBack, and repeatable verification.

**Non-Goals:**

- No state-machine, connection-attempt, Target Lock, signaling, socket identity, transport, WebRTC, recovery, Room schema, discovery-policy, audio-engine, or service-resource behavior changes.
- No implementation of mute, exact VOX state/settings, manual audio routing, automatic-reconnect settings, manual rescan, or help/feedback.
- No Compose, Fragment, extra Activity, new UI framework/dependency, dark theme, tablet-specific two-column layout, landscape-specific information architecture, or production sample riders.
- No fabricated nickname, device, avatar identity, battery, distance, authentication, verification, transport readiness, VOX state, timestamps, or persisted-log claims.

## Decisions

### 1. Keep one Activity and one listener owner; model routes separately from product state

`MainActivity` remains the only `IntercomService.Listener`, permission coordinator, and service-binding owner. A small UI route model (`HOME`, `DISCOVER`, `SETTINGS`, `LOGS`) and page controller live in the UI layer. Route changes swap/inflate page roots in a single Activity container and never dispatch product events. `savedInstanceState` stores the route, each page’s scroll position, and unsaved nickname draft; process start defaults to Home.

This avoids multiple listener registrations and keeps `SessionOrchestrator` as the only product-state writer. Fragments or additional Activities were rejected because they add lifecycle owners and violate the locked PRD. Deriving route from `IntercomState` was rejected because navigation must not mutate or mirror product state.

Back resolves in this order: active navigation panel, placeholder dialog, Logs to Settings, Discover/Settings to Home, then system default on Home. Incoming confirmation remains non-cancelable by Back and is dismissed only through its real lifecycle.

### 2. Use XML page layouts and resource tokens, with independent page scroll containers

`activity_main.xml` supplies the inset-aware host, overlay/navigation-panel surface, and one page container. `screen_home.xml`, `screen_discover_riders.xml`, `screen_settings.xml`, and `screen_logs.xml` each own one vertical scroll region. Reusable card, button, pill, and vector assets live in `res/drawable`; text, color, style, and dimension tokens live in `values`, with only dimension overrides where useful for `sw600dp`.

Content backgrounds may fill the window, but page content is horizontally centered and capped at 520dp. Layouts use wrap-content heights, flexible text, and at least 48dp touch targets; they do not rely on fixed text heights or horizontal scrolling. System-bar/cutout insets pad the host’s safe edges without double-applying page spacing. The theme is fixed light and does not follow system dark mode.

Programmatic-only rebuilding was rejected because it obscures page hierarchy and responsive inspection. Compose and new UI libraries were rejected by scope. A permanent wide-screen rail was rejected because the navigation semantics must remain identical through 600dp and beyond.

### 3. Render a single immutable UI snapshot from truthful callback data

The UI controller holds only the latest callback facts needed to render: `IntercomState`, supplemental status/error text, audio-source text and Bluetooth boolean, latest `List<RiderPresence>`, remote-rider callback value where applicable, and the in-memory log buffer. Rendering derives page text, enabled state, CTA visibility, current Attempt plan, connected transport, peer label, and animations from that snapshot.

The nine-state Home matrix follows the PRD exactly. `IntercomState` owns the primary semantic status; `onStatusChanged` and `onError` are supplemental and cannot overwrite it. Peer display is recalculated from the current state so stale view text cannot leak into Offline or a different Attempt. Recovering reads its original `state.peer`; Resetting shows generic recovery text rather than decoding a device ID. Connected transport comes from `Connected.transport`; planned LAN/P2P labels come only from the current Attempt channel plan. WebRTC says connected only for `Connected` and establishing only for `Connecting`. Bluetooth activation comes only from `onAudioSourceChanged(..., bluetooth = true)`.

`onAudioLevelChanged` may animate the Home visualizer only while Home is visible and animations are enabled. It never feeds VOX labels or product state. The former `updateVoxDisplay(level)` heuristic and “all active means ready” pills are removed. This keeps the current callbacks without inventing missing backend signals.

### 4. Reuse real actions and distinguish accepted UI dispatch from product success

Home’s central control continues through `primaryIntercomAction`, its existing debounce, permissions, Wi-Fi gate, start intent, `requestDisconnectCurrent`, and `requestStop`. Discover Offline exposes the same real start path but remains on Discover. A Presence button is available only when the latest item is selectable and state is Discovering; it passes that object to `IntercomService.connectToPresence`. The UI records an accepted dispatch, stays on Discover until a real `Connecting`, `Optimizing`, `Connected`, or `Recovering` callback is replayed, and then returns Home; it never sets Connecting or Connected itself. A missing service or stale selection keeps the user on Discover with truthful feedback.

Incoming positive/negative actions continue through `respondToIncomingConfirmation(prompt, accepted)` and remain bound to the current action nonce. Accept navigates Home and waits for callbacks; reject does not invent state. A new incoming prompt first dismisses the placeholder. Cancellation, timeout, replacement, `onStop`, or service disconnect invalidates the old surface.

### 5. Make nickname persistence explicit without changing a live identity

Settings owns a draft field and “保存昵称” action. Validation trims boundaries, rejects empty input, and limits the value to 64 Unicode code points. The save operation writes the same local nickname source consumed by the next runtime start; an active runtime is not restarted and its discovery/socket/remote-visible identity is not mutated. Success while running says “已保存，下次启动对讲时生效”; failure retains the draft and shows the actual error.

The exact adapter from Activity to the existing preference/identity persistence may be kept as a small UI-owned store boundary so tests can exercise validation and failure behavior. Directly modifying current signaling/discovery identity during a session was rejected. Treating edit-on-Start as the only save mechanism was rejected because the confirmed Settings contract requires explicit save and restoration.

### 6. Centralize placeholders and preserve the real incoming dialog’s priority

One Activity-owned placeholder dialog instance serves mute, Home/Settings VOX controls, manual audio choices, auto reconnect, manual rescan, and help/feedback. Its exact title is “功能开发中”, message is “该功能还没做好，暂时无法使用。”, and positive button is “确定”. Back and the positive button only dismiss it. Repeated taps do not stack instances, and a real incoming request dismisses it before showing the nonce-bound incoming dialog.

About uses a separate real static dialog with app name, runtime `versionName`, one-to-one offline riding-intercom positioning, optional “power by kuma”, and close action. It must not claim unprovided services.

### 7. Keep logs buffered independently from Logs rendering

`BoundedLogBuffer(300)` remains the current UI-session source. Every received UI log appends to it, but the monospace view is updated only while Logs is visible. Opening Logs renders one snapshot; new visible entries append. Auto-scroll happens only if the user was already at the bottom. Copy All joins the snapshot in display order with newline, is disabled when empty, writes to the Android clipboard, and emits accessible “日志已复制” feedback. No timestamps, files, uploads, persistence, or earlier service history are invented.

### 8. Verify behavior at pure-logic, build, emulator, device, visual, and architecture gates

Extract deterministic UI-only mappings/policies where needed so local unit tests cover routes/back/restoration, all nine state presentations, CTA/button rules, truthful channel labels, Presence selection/expiry, recovery target retention, placeholder singleton/no-side-effect behavior, nickname validation, incoming precedence/stale nonce handling, and 300-line logs/copy ordering.

Run the complete gate with the project JBR:

`$env:JAVA_HOME='F:\Android\jbr'; .\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug --console=plain`

If the Unicode workspace triggers the known full-test `ClassNotFoundException`, rerun from the existing `M:` mapping and record both results rather than relabeling a code failure. Emulator evidence covers navigation/back, dialogs, all page states/fixtures, 320/360/411/600dp, 1.0x/1.3x font, gesture/three-button navigation, cutout/rounded profile, landscape reachability, TalkBack semantics, and fixed-light visuals. Two real devices cover permissions, Start/Stop, Presence selection, incoming accept/reject, actual LAN/P2P/WebRTC/Bluetooth presentation, foreground/background/lock/recreation, logs/copy, and placeholder no-side-effects. Listening quality is separately marked manual. Not-run checks remain explicitly Not Run.

Before an implementation checkpoint advances, a read-only `motointercom-product-architect` review must report APPROVED/REQUEST CHANGES, P0, P1, Non-blocking, Base SHA, Head SHA, and Next gate allowed; all P0/P1 findings must be resolved.

## Risks / Trade-offs

- [UI snapshot accidentally becomes a second product state machine] → Keep only callback facts and pure presentation mappings; prohibit route actions from writing product state.
- [Remote name or channel labels outlive their valid Attempt] → Recompute them from the current `IntercomState` and clear safe fallbacks on Offline; test every transition.
- [Presence expires between render and tap] → Pass the selected snapshot to the existing resolver and let the service reject stale entries; never cache it as selectable.
- [Nickname storage has two current representations] → Use one explicit UI persistence adapter whose saved value is the value supplied on the next existing start path; do not alter live service identity.
- [Inset or font-scale fixes regress the reference appearance] → Prefer wrap-content and centered max-width constraints, then capture the full device/font/cutout matrix rather than hard-coded heights.
- [Hidden UI work causes churn or TalkBack noise] → Gate visualizer/radar/log-view updates by visible route and animation settings while always retaining source data.
- [Large UI replacement obscures backend regressions] → Preserve no-touch backend files, run full Gradle and two-device gates, and require architecture approval against exact base/head SHAs.

## Migration Plan

1. Copy the authoritative PRD into the branch and add the route/presentation/log/nickname test seams.
2. Add fixed-light tokens, vector assets, Activity host, and four XML pages alongside the current screen.
3. Wire existing callbacks/actions to the new controller, then switch `MainActivity` to the new host.
4. Remove only the superseded long-screen construction, heuristic VOX update, and inferred readiness pills after parity tests pass.
5. Execute automated, emulator, accessibility, visual, two-device, and architecture gates; retain evidence and mark unavailable checks Not Run.

Rollback is a normal revert of the KUM-46 UI commit set. No data/schema migration or backend protocol rollback is required; nickname persistence remains compatible with the existing next-start behavior.

## Open Questions

None. The PRD, reference image, issue scope, architecture boundary, and acceptance matrix are confirmed. Any newly discovered backend requirement is out of KUM-46 and requires separate authorization rather than expansion of this change.
