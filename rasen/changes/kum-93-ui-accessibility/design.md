## Context

Home, Discover, and Settings are Compose screens hosted by the existing View shell. The current screens keep product state outside Compose and use immutable UI state plus callbacks. The 2026-11-06 UI audit found two header icon buttons whose parent and child both publish the same `contentDescription`, a nickname field with no label semantics, and a placeholder color below the 4.5:1 normal-text contrast threshold.

## Goals / Non-Goals

**Goals:**

- Make header icon actions announce once in TalkBack.
- Keep the visible nickname label readable while preserving the field's editable text and selection semantics.
- Raise placeholder contrast using the existing accessible color token.
- Lock the semantics with focused Compose tests.

**Non-Goals:**

- No layout, navigation, theme refactor, shared component extraction, or state-holder change.
- No service, protocol, persistence, audio, or dependency change.
- No claim of completed physical-device TalkBack acceptance.

## Decisions

- Keep the accessible name on the interactive parent. Set decorative `Image` and `Icon` content descriptions to `null`; this preserves the button role and avoids duplicate announcements.
- Keep the visible `settings_nickname_label` text as an independent label. Do not set `contentDescription` on `BasicTextField`: Compose 1.11.4's accessibility delegate prioritizes content description over `EditableText`, which would replace the user's text during traversal and selection. The field keeps its native `EditableText`, `TextSelectionRange`, and `SetSelection` semantics.
- Use `motocom_text_muted_accessible` instead of creating a new color token. The audit measured approximately 6.66:1 against white, while `motocom_text_muted` measured approximately 4.38:1.
- Add node-level Compose assertions for exactly one icon description, editable text after input, absence of `ContentDescription` on the field, and `SetSelection` updating `TextSelectionRange`.

## Risks / Trade-offs

- Merged semantics behavior can vary by Compose version → assert the exact content-description list in the existing Robolectric Compose suites.
- The visible label remains a separate semantics node from the field → TalkBack reads the label in document order before the field; device acceptance must confirm that the sequence is understandable.
- Automated tests cannot prove TalkBack pronunciation or traversal order → record device acceptance as pending until an emulator or phone is available.
