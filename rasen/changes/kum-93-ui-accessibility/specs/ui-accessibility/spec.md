## ADDED Requirements

### Requirement: Icon buttons expose one accessible name
Compose icon buttons SHALL expose their action label from the interactive parent and SHALL NOT repeat the same label from a decorative child icon.

#### Scenario: TalkBack focuses a header icon button
- **WHEN** the user focuses the discover or settings header icon button
- **THEN** the button exposes exactly one accessible name matching its action

### Requirement: Nickname input preserves editable text and selection semantics
The nickname text field SHALL expose its actual editable text and text selection range. The visible "本机昵称" text SHALL remain available as a separate label. The editable node SHALL NOT publish that label through `contentDescription`.

#### Scenario: TalkBack focuses the nickname field
- **WHEN** the user focuses the nickname input
- **THEN** the field exposes its current editable text and text selection range
- **AND** the field does not replace its editable text with "本机昵称" through `contentDescription`

#### Scenario: The user edits the nickname
- **WHEN** the user types into the nickname input
- **THEN** the typed value remains the field's `EditableText`
- **AND** the `SetSelection` action continues to update `TextSelectionRange`

### Requirement: Placeholder text meets normal-text contrast
The nickname empty-state hint SHALL use a foreground color with at least 4.5:1 contrast against the surrounding white surface.

#### Scenario: Nickname is empty
- **WHEN** the nickname input is empty on the settings screen
- **THEN** the displayed hint uses the accessible muted text token
