## ADDED Requirements

### Requirement: Route structure and restoration
The application SHALL provide Home, Discover Riders, and Settings as top-level routes and Logs as a Settings child route within one Activity. UI route SHALL remain independent from `IntercomState`; each page SHALL have an independent vertical scroll position; Activity recreation SHALL restore route, page scroll positions, and unsaved nickname draft, while process start SHALL default to Home and product facts SHALL be restored only from current service callbacks.

#### Scenario: Cold start and Activity recreation
- **WHEN** the app cold-starts and is later recreated while Settings contains an unsaved nickname draft
- **THEN** cold start opens Home, recreation returns to Settings with its scroll position and draft, and no saved View text is treated as product state

#### Scenario: Natural Back behavior
- **WHEN** Back is pressed with the navigation panel open, a placeholder open, Logs visible, Discover visible, Settings visible, or Home visible
- **THEN** it respectively closes the panel, closes the placeholder, returns Logs to Settings, returns Discover or Settings to Home, or performs the system default Home exit/background behavior

#### Scenario: Locked incoming confirmation
- **WHEN** an active incoming confirmation is displayed and the user presses Back or taps outside it
- **THEN** the request is not bypassed or canceled and remains governed by its real accept/reject/cancel/timeout lifecycle

### Requirement: XML fixed-light responsive presentation
The application SHALL implement the Activity host and four pages with Android Views and XML resources, without Compose, Fragments, additional Activities, or new UI dependencies. It SHALL use a fixed light theme with white/soft-gray surfaces and neon-green accent, cap primary content at 520dp and center it on wider screens, avoid horizontal scrolling, and remain reachable from 320dp through 600dp widths and in landscape.

#### Scenario: Width matrix
- **WHEN** Home, Discover, Settings, and Logs are rendered at 320dp, 360dp, 411dp, and 600dp widths
- **THEN** no content is horizontally clipped, all functions are reachable by each page’s vertical scroll, and 600dp content is centered at no more than 520dp

#### Scenario: Font and dynamic height
- **WHEN** the system font scale is 1.3x
- **THEN** text does not overlap or truncate, cards and status labels grow or reflow, and primary actions remain reachable without fixed-height clipping

#### Scenario: Insets and shapes
- **WHEN** the app runs with status/navigation bars, gesture or three-button navigation, a display cutout, punch hole, or rounded corners
- **THEN** critical text and controls remain inside safe touchable bounds with no double-applied or missing insets

### Requirement: Accessible interaction
All actionable controls SHALL expose at least a 48dp touch target, meaningful labels/content descriptions, visible enabled/disabled state, sufficient light-theme contrast, and keyboard/TalkBack focus order. Status SHALL not be expressed by color alone; placeholder semantics SHALL include “功能开发中”; dynamic status updates SHALL avoid unnecessarily stealing TalkBack focus.

#### Scenario: TalkBack traversal
- **WHEN** TalkBack traverses a rider card and each page’s primary actions
- **THEN** focus order presents avatar decoration appropriately, then rider identity/device/channel/status/action, and every action and disabled reason is understandable without relying on color

#### Scenario: Disabled primary action
- **WHEN** the app lacks a required permission or the product state is Stopping
- **THEN** the central action is disabled with a textual reason and remains visually distinguishable at accessible contrast

### Requirement: Truthful nine-state Home rendering
Home SHALL render the primary text, supplemental text, primary action, enabled state, and Discover CTA from the current one of nine `IntercomState` variants. `onStatusChanged` and `onError` MAY supply supplemental text or logs but MUST NOT overwrite product-state semantics or cause a UI-written product transition.

#### Scenario: Complete state matrix
- **WHEN** Home receives Offline, Discovering, IncomingConfirmation, Connecting, Optimizing, Connected, Recovering, Resetting, and Stopping in turn
- **THEN** it renders the PRD’s locked state copy and action rules, enables Discover CTA only for Discovering, and disables the “停止中…” action only for Stopping

#### Scenario: Start and stop use real paths
- **WHEN** the rider invokes the Home primary action
- **THEN** Offline uses the existing permission/Wi-Fi/start path, active states use the existing disconnect/stop policy and debounce, Stopping ignores repeated input, and Home never assigns the resulting `IntercomState`

#### Scenario: No target after start
- **WHEN** the real state becomes Discovering without a current target
- **THEN** Home remains visible and presents a clear “查看附近车友” action without forcing navigation

### Requirement: Truthful peer and channel presentation
Home and Settings SHALL display remote rider, Attempt plan, connected transport, WebRTC, and audio-source facts only where supported by current state/domain callbacks. The UI MUST NOT present invented nickname, device model, battery, distance, authentication, verification, transport readiness, or VOX state.

#### Scenario: Peer lifecycle
- **WHEN** Connecting or Optimizing has a real peer, Connected has a verified peer, Recovering retains its peer, Resetting has no nickname, or Offline follows a session
- **THEN** the UI respectively shows the real peer, connected peer, original recovery peer, generic original-rider recovery text, or the safe “等待车友加入” empty state without stale text

#### Scenario: Channel labels
- **WHEN** an Attempt plans LAN/P2P, Connecting is in progress, Connected selects a transport, or the audio callback reports Bluetooth
- **THEN** planned channels come only from the Attempt, WebRTC is “建立中” only for Connecting and “已连接” only for Connected, connected transport comes from `Connected.transport`, and Bluetooth activates only for callback `bluetooth=true`

#### Scenario: VOX inference prohibited
- **WHEN** any audio level is received
- **THEN** it may update only the visible Home waveform and never activates LISTENING, OPEN, HANGOVER, or any exact VOX claim

### Requirement: Discover Riders uses current Presence
Discover Riders SHALL render only the latest service-provided `RiderPresence` snapshot, preserve its preferred/paired/nearby ordering, expose real display name, device name, available LAN/P2P candidates, pairing/preference/selectability, and never calculate its own TTL or retain a removed Presence as selectable.

#### Scenario: Select a current rider
- **WHEN** state is Discovering and the latest Presence is selectable and the bound service accepts dispatch
- **THEN** its Connect action calls the existing `connectToPresence` path, remains on Discover until a real `Connecting`, `Optimizing`, `Connected`, or `Recovering` state is replayed, then returns to Home, and never displays Connected itself

#### Scenario: Stale or unavailable selection
- **WHEN** a Presence expires before selection resolution, is not selectable, the service is unavailable, or state is not Discovering
- **THEN** no replacement target is created, the stale card is removed on the next snapshot, and the UI remains truthful with no Home navigation until an accepted connection state is replayed

#### Scenario: Same display names
- **WHEN** multiple current Presence items share a display name
- **THEN** real device names distinguish them, or the last four characters of a stable device ID are used only if still needed, without exposing the full ID or implying authentication

#### Scenario: Offline Discover flow
- **WHEN** Discover opens while Offline
- **THEN** it shows no connect buttons, explains that MotoCom must start to discover riders, and its real Start action uses the existing permission/Wi-Fi/start path while remaining on Discover

### Requirement: Target lock and recovery are not bypassed
Discover actions SHALL be read-only in IncomingConfirmation, Connecting, Optimizing, Connected, Recovering, Resetting, and Stopping. Recovering SHALL continue presenting the original state peer and MUST NOT replace the original target from ordinary Presence updates.

#### Scenario: Presence changes during recovery
- **WHEN** a new preferred or similarly named Presence appears while state is Recovering
- **THEN** Connect actions remain unavailable and Home continues to show the original recovery peer

#### Scenario: Failure returns to discovery
- **WHEN** the backend returns from an attempt failure to Discovering
- **THEN** Home uses current failure/search supplemental text and exposes “重新选择车友” without selecting a Presence automatically

### Requirement: Real Settings information and nickname persistence
Settings SHALL show current product state, current Attempt/transport facts when present, and actual audio-source/Bluetooth callback data. It SHALL provide explicit nickname save that trims boundaries, rejects empty input, limits the value to 64 Unicode code points, restores the saved value after recreation/start, and supplies it to the next runtime start without mutating an active runtime identity.

#### Scenario: Save nickname offline
- **WHEN** the rider saves a valid nickname of at most 64 Unicode code points while Offline
- **THEN** it is persisted and used by the next existing Start flow

#### Scenario: Save nickname while running
- **WHEN** the rider saves a valid nickname during an active runtime
- **THEN** the UI reports “已保存，下次启动对讲时生效” and does not change current discovery advertisements, socket identity, connected peer display, or runtime state

#### Scenario: Invalid or failed save
- **WHEN** the trimmed nickname is empty, exceeds 64 Unicode code points, or persistence fails
- **THEN** no invalid value is saved, the draft remains editable, and a specific input or real failure message is shown

#### Scenario: Truthful settings status
- **WHEN** no audio device name, Attempt, battery value, or manual route state is available
- **THEN** Settings uses safe audio/product-state text and omits invented model, battery, transport policy, and control values

### Requirement: Placeholder and About dialog contracts

> Implementation addendum 2026-08-10: the old placeholder scope is no longer
> current for automatic reconnect, manual rescan, mute/audio-route, VOX
> settings, or Help/Feedback. Those entries now have real callback behavior.
> Help/Feedback uses an explicit system chooser and sends only a text template
> with app/Android version; it does not attach logs, recordings, URI data, or
> URI grants. The remaining placeholder contract applies only to controls that
> still have no real implementation in the current source tree.
Mute, Home/Settings VOX controls and exact status, manual audio route, auto reconnect, manual rescan, and help/feedback SHALL remain clickable placeholders implemented by one shared dialog instance. Its title MUST be “功能开发中”, message MUST be “该功能还没做好，暂时无法使用。”, and positive button MUST be “确定”; displaying or dismissing it SHALL be its only effect. About SHALL be a separate real static dialog using current app `versionName`.

#### Scenario: Placeholder has no side effects
- **WHEN** any placeholder is clicked and then dismissed by “确定” or Back
- **THEN** exactly one dialog is used and no route, setting, product state, Attempt, service, transport, WebRTC, discovery, or audio action changes

#### Scenario: Repeated placeholder taps
- **WHEN** multiple placeholder controls are tapped while the dialog is already visible
- **THEN** no second dialog is stacked and the original fixed content remains

#### Scenario: About information
- **WHEN** About is opened
- **THEN** it shows 摩声 MotoCom, the real `versionName`, one-to-one offline riding-intercom positioning, optional “power by kuma”, and a close action without fabricated website, support, privacy, or capability claims

### Requirement: Incoming confirmation priority and nonce safety
The real incoming-confirmation dialog SHALL show the current peer’s real nickname/device name, Accept and “拒绝本次”, remain non-cancelable outside its real actions, and bind every action to the current prompt’s action nonce/Attempt. It SHALL supersede a placeholder and close immediately when the request is canceled, expires, or is replaced.

#### Scenario: Incoming prompt supersedes placeholder
- **WHEN** a valid incoming prompt arrives while the placeholder is displayed
- **THEN** the placeholder closes and the current incoming dialog becomes the only visible dialog

#### Scenario: Accept or reject current request
- **WHEN** the rider accepts or rejects the current prompt
- **THEN** only the existing `respondToIncomingConfirmation` path is called with that prompt and decision; Accept navigates Home and both decisions await real state callbacks

#### Scenario: Stale dialog action
- **WHEN** a prompt is canceled, times out, or is replaced before an old button action is delivered
- **THEN** the old dialog closes and its nonce cannot affect the current request

### Requirement: Current-session bounded logs
Logs SHALL display only log lines received in the current UI session through a `BoundedLogBuffer` limited to the newest 300 entries. It SHALL state that scope, use a vertically scrollable monospace region, and SHALL NOT claim persistence, invent timestamps, upload, export files, or diagnose automatically.

#### Scenario: Hidden Logs page
- **WHEN** log callbacks arrive while Logs is not visible
- **THEN** the buffer updates but no hidden log TextView is redrawn, and opening Logs renders one latest snapshot

#### Scenario: Bounded order and visible updates
- **WHEN** more than 300 lines arrive
- **THEN** only the newest 300 remain in original display order; visible Logs appends new lines and auto-scrolls only when the rider was already at the bottom

#### Scenario: Copy All
- **WHEN** Logs contains lines and the rider chooses Copy All
- **THEN** all buffered lines are copied in display order separated by newline and accessible “日志已复制” feedback appears; when empty, Copy All is disabled

### Requirement: Lifecycle-aware rendering and animation
`MainActivity` SHALL retain permission, service bind/unbind, and listener ownership; `IntercomService` SHALL retain background connection/audio lifetime. On foreground return, replayable product facts SHALL be rendered from the rebound listener, while absent prior logs/audio levels SHALL not be fabricated. Page-only animation/redraw SHALL run only when its page is visible and applicable.

#### Scenario: Foreground rebind
- **WHEN** the Activity returns after `onStop`
- **THEN** it reattaches the single listener and renders replayed status, product state, audio source, Presence, and non-empty remote rider without synthesizing missing logs or audio level

#### Scenario: Page animation gating
- **WHEN** Home, Discover, or Logs is hidden or system animations are disabled
- **THEN** waveform, radar, or hidden text refresh respectively stops while source state/log buffering remains correct

### Requirement: UI-only architecture boundary
The implementation MUST preserve `SessionOrchestrator` as the only product-state writer and `IntercomService` as Android lifecycle/resource/effect owner. It MUST NOT change state machine, Target Lock, signaling, socket identity, transport adapters, WebRTC, recovery, Room schema, Gradle dependencies, or product protocols.

#### Scenario: Architecture review
- **WHEN** the implementation reaches a review checkpoint
- **THEN** an independent read-only product architecture review against exact base/head SHAs reports APPROVED/REQUEST CHANGES, P0, P1, Non-blocking, Base SHA, Head SHA, and Next gate allowed, and no checkpoint advances with unresolved P0/P1

### Requirement: Traceable verification and evidence
The branch SHALL contain the complete authoritative PRD at `docs/product/2026-07-17-motocom-ui-redesign-prd.md`. Verification SHALL cover deterministic UI logic, the full Android build gate, emulator navigation/visual/responsive/accessibility checks, and available two-device real-flow regression. Any unexecuted check MUST be marked Not Run rather than inferred as passed.

#### Scenario: Automated gate
- **WHEN** implementation is ready for verification
- **THEN** `clean testDebugUnitTest lintDebug assembleDebug --console=plain` runs with `JAVA_HOME=F:\Android\jbr`; a Unicode-path `ClassNotFoundException` is rechecked from the existing `M:` mapping and both results are recorded

#### Scenario: Emulator evidence matrix
- **WHEN** emulator acceptance is performed
- **THEN** evidence covers all routes/back paths, nine Home states, Presence search/empty/populated/read-only states, Settings, Logs, navigation panel, incoming and placeholder dialogs, 320/360/411/600dp, 1.0x/1.3x font, cutout/rounded profile, gesture/three-button navigation, landscape reachability, fixed-light visuals, and accessibility

#### Scenario: Two-device regression
- **WHEN** two project-supported Android devices are available
- **THEN** evidence covers permission, Start/Stop, real Presence selection, incoming accept/reject, actual LAN/P2P/WebRTC/Bluetooth presentation, foreground/background/lock/recreation, logs/copy, and every placeholder’s lack of side effects, with listening quality labeled manual evidence
