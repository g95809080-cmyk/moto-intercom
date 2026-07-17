# MotoIntercom Project Governance

## Authority

- Linear owns the roadmap, priorities, dependencies, Exit Criteria, and issue state.
- Rasen owns per-issue proposal, spec, design, tasks, execution state, and recovery checkpoints.
- Git owns source history.
- GitHub owns pull-request review and CI state.
- `motointercom-product-architect` owns product and architecture approval.
- Rasen must not create, reorder, or replace the Linear roadmap.

## Unit Of Work

One active Linear issue equals one Rasen change, one implementation branch, and one independently reviewable pull request.

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

## Rasen Authorization

For every Rasen command in this private project, set `DO_NOT_TRACK=1` and `RASEN_TELEMETRY=0`.

Rasen may automate analysis, current-issue edits, tests, failure fixes, commits, feature-branch pushes, Draft PR creation or updates, review cycles, P0/P1 remediation, and evidence updates.

Rasen must not automate merging `main`, force pushes, remote-branch deletion, requirement or approved-architecture changes, acceptance of new high-risk residual risk, production database changes, signing-key use, releases, or deployments.

The KUM-27A pilot must not invoke `rasen-chrome-use`, enable Chrome remote debugging, use browser cookies or production accounts, or perform automatic merge or deployment.
