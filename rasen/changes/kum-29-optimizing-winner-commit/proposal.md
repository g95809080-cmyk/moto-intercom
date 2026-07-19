## Why

KUM-29 remains open even though PR #5 already introduced the approved one-second
`OPTIMIZING` arbitration and single-media-owner behavior. The behavior needs an
issue-scoped contract and independently traceable evidence before the Sprint can
advance without duplicating the runtime implementation.

## What Changes

- Formalize the responder-side one-second `OPTIMIZING` contract.
- Verify deterministic preferred/fallback winner selection and first-claim wins.
- Verify that only the selected channel can start Signaling/WebRTC media.
- Verify bounded loser and terminal-path cleanup for dual-success, single-success,
  all-failure, cancellation, and deadline cases.
- Add KUM-29-specific Rasen and verification evidence without changing Android
  runtime behavior.

## Capabilities

### New Capabilities

- `optimizing-winner-arbitration`: Defines and certifies the bounded optimization,
  unique winner, single media owner, and loser cleanup contract required by KUM-29.

### Modified Capabilities

None.

## Impact

- Rasen planning artifacts and issue-scoped verification documentation.
- Existing coordinator, signaling, media-selection, and cleanup tests are reused as
  the executable evidence.
- No protocol, database, UI, Android framework, dependency, or release-path change.
