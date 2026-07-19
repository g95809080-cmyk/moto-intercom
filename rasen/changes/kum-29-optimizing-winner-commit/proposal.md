## Why

KUM-29 remains open after PR #5 introduced the approved one-second `OPTIMIZING`
arbitration and single-media-owner behavior. Independent review found two bounded
correctness gaps: exact-expiry selection depended on mailbox order, and loser
closure depended indefinitely on a signaling writer callback.

## What Changes

- Formalize the responder-side one-second `OPTIMIZING` contract.
- Verify deterministic preferred/fallback winner selection and first-claim wins.
- Verify that only the selected channel can start Signaling/WebRTC media.
- Verify bounded loser and terminal-path cleanup for dual-success, single-success,
  all-failure, cancellation, and deadline cases.
- Freeze the selection cohort at exact optimization expiry and reject late owner
  claims before they can start media.
- Add an exact-context monotonic one-second loser-close watchdog that preserves
  reject-before-close when the writer completes normally.
- Add KUM-29-specific deterministic and emulator regression evidence.

## Capabilities

### New Capabilities

- `optimizing-winner-arbitration`: Defines and certifies the bounded optimization,
  unique winner, single media owner, and loser cleanup contract required by KUM-29.

### Modified Capabilities

None.

## Impact

- Coordinator duplicate-channel and media-selection deadline guards.
- Service-owned physical loser-close scheduling and its pure JVM scheduler.
- Rasen planning artifacts, deterministic tests, and issue-scoped verification.
- No protocol schema, database, UI, dependency, or release-path change.
