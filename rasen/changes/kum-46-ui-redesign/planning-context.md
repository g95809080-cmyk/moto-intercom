# Planning Context: KUM-46 UI Redesign

## User intent

Implement the already confirmed MotoCom frontend design and complete PRD end to end with Rasen 0.1.5. The current single long scrolling screen must become a natural multi-page Android UI. Features without a real interface remain clickable, but only show the unified "功能开发中" dialog and close on "确定". The UI must adapt to different screen sizes and shapes.

## Authoritative inputs

- Linear issue: KUM-46
- Full PRD source: `C:\Users\kuma\Documents\摩托对讲机\MotoIntercomApp\docs\product\2026-07-17-motocom-ui-redesign-prd.md`
- Design image: `C:\Users\kuma\Desktop\ChatGPT Image 2026年7月8日 01_14_09.png`
- Implementation repository and branch: `MotoIntercomApp-release-v1`, `feat/kum-46-ui-redesign`
- Base: `origin/main@9107c86`

## Confirmed product decisions

- Top-level routes: Home, Discover Riders, Settings.
- Child route: Logs.
- UI route is independent from `IntercomState`.
- Hamburger opens navigation; gear opens Settings; the Home audio settings affordance opens the audio section in Settings.
- Start/Stop, real Presence selection, nickname persistence, incoming accept/reject, logs, real audio source, remote nickname, and product state use existing real paths.
- Selecting a real `RiderPresence` calls the existing connect path and returns Home.
- Home stays visible after Start. If there is no target, it exposes a clear Discover Riders CTA.
- Recovery continues to display the original target and must not select another rider.
- Placeholder controls are clickable and show exactly one dialog:
  - title: `功能开发中`
  - message: `该功能还没做好，暂时无法使用。`
  - positive button: `确定`
  - closing the dialog is the only effect.
- Placeholder scope includes mute, exact VOX controls/sensitivity, manual audio route choice, auto reconnect setting, manual rescan, and help/feedback.
- About is a real static dialog.
- Never invent rider identity, device model, battery, distance, authentication, verification, transport readiness, or VOX state.
- Remove the current audio-level heuristic that guesses VOX OPEN/HANGOVER.
- Remove transport/WebRTC pills that become active before the corresponding state is actually proven.
- Logs retain the latest 300 current UI-session lines and support Copy All. When Logs is hidden, update only the buffer.

## Responsive and visual requirements

- Fixed light theme with the design's white/soft-gray surfaces and neon green accent.
- 320dp–600dp width support, max content width 520dp, centered on wider screens.
- Independent scrolling per page; no app-wide one-page scroll.
- Respect status/navigation bars, display cutouts, rounded corners, and safe insets.
- Remain usable at 1.3x font scale without clipped text, overlap, or unreachable primary actions.
- Verify representative 320dp, 360dp, 411dp, and 600dp layouts, plus a rounded/cutout profile.
- Maintain accessible touch targets, content descriptions, contrast, disabled states, and back navigation.

## Architecture constraints

- `SessionOrchestrator` remains the only product-state writer.
- `IntercomService` remains the Android lifecycle/resource/effect owner.
- Do not change state-machine, Target Lock, signaling, Socket identity, transport adapters, WebRTC, recovery, Room schema, or Gradle dependencies.
- Use one Activity, Android Views and XML resources; no Compose and no Fragment.
- UI consumes existing Listener callbacks and invokes existing public actions only.
- Preserve the existing released v1.0 backend behavior.

## Current code observations

- Current UI is programmatic `MainScreen.kt` inside `MainActivity`.
- It places connection, audio, VOX, discovery, settings, and logs into one `ScrollView`.
- The current `updateVoxDisplay` guesses VOX state from audio level threshold `0.12f`; this is prohibited.
- Current status pills can imply Wi-Fi Direct/WebRTC readiness too early; replace them with truth-preserving state presentation.
- Existing Service Listener callbacks already cover status, `IntercomState`, audio source, Presence, audio level, logs, remote rider, incoming confirmation, toast, and errors.
- Existing Start/Stop and Presence-connect callbacks must be reused.
- Existing Rasen 0.1.5 deployment changes under `.codex/skills` and `rasen/config.yaml` belong to the user and must not be reverted or folded into product commits accidentally.

## Expected planning output

Create a single coherent KUM-46 proposal, design, delta spec, and task list that implement the full PRD without expanding backend scope. Include deterministic unit tests, full Android build gates, emulator navigation/visual checks, responsive/font-scale evidence, and the required independent architecture review. Include copying the full PRD into this branch at `docs/product/2026-07-17-motocom-ui-redesign-prd.md` as a traceable source artifact.

## Durable planning findings (2026-07-30)

- `IntercomService.setListener` currently replays status, `IntercomState`, audio source plus the Bluetooth boolean, the latest Presence snapshot, and a non-null remote rider; it does not replay past log lines or the last audio level. Foreground restoration must respect that distinction.
- The current real UI actions are `requestStart`, `requestStop`, `requestDisconnectCurrent`, `connectToPresence`, and nonce-bound `respondToIncomingConfirmation`; `connectToPresence` re-resolves the selected snapshot and rejects stale/unavailable Presence rather than trusting the View.
- Nickname persistence is currently finalized on runtime start: `IntercomService` selects the requested name, then stored name, then “骑士”, and persists a nonblank requested name through `LocalIdentityStore`. The Settings save adapter must feed the next existing start path without changing a live runtime identity.
