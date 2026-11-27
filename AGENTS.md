# MotoIntercom Project Governance

## Release Versioning

Follow `docs/releases/versioning.md` for every release. `version.properties` is the single source of truth for SemVer, Android versionCode, and the user-approved functional merge SHA. Never reuse a published versionCode or silently substitute the approved commit.

## Authority

- Linear owns the roadmap, priorities, dependencies, Exit Criteria, and issue state.
- Git owns source history.
- GitHub owns pull-request review and CI state.
- `motointercom-product-architect` owns product and architecture approval.

## Unit Of Work

One active Linear issue equals one implementation branch and one independently reviewable pull request.

## Concurrency

- Maximum concurrent write workers: 1.
- Read-only planner and reviewer workers may run in parallel.
- Leaf workers must not delegate to subagents.
- Multiple workers must never concurrently modify `IntercomService`, `IntercomStateMachine`, `SessionOrchestrator`, `SignalingProtocol`, transport adapters, Gradle files, or database schemas.

## Architecture Gate

Every source behavior change requires a read-only review with `motointercom-product-architect`. The review must report:

```text
APPROVED / REQUEST CHANGES
P0
P1
Non-blocking
Base SHA
Head SHA
Next gate allowed
```

No checkpoint may advance while P0 or P1 findings remain.
