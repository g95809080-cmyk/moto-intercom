---
name: rasen-goal-report
description: Goal-loop report tail (shipper role, research pipeline only) — summarizes goal-run.json into a final report artifact. No code to ship; surfaces maxRounds-exhausted honestly.
license: MIT
compatibility: Requires rasen CLI.
metadata:
  author: rasen
  version: "1.0"
  generatedBy: "0.1.3"
---

Summarize the goal-loop run into a final report — the research pipeline's tail.

**Store selection:** If the user names a store (a store is a standalone Rasen repo registered on this machine) or the work lives in one, run `rasen store list --json` to discover registered store ids and project ids (the `type` field on each entry), then pass `--store <id>` (or `--project <id>` for a project registered via `store add-project`) on the commands that read or write specs and changes (`new change`, `status`, `instructions`, `list`, `show`, `validate`, `archive`, `doctor`, and the top-level `context`). The `rasen pipeline` inspection group (`pipeline list`, `pipeline show`, `pipeline agents`, `pipeline classify`, `pipeline resume`) also accepts `--store <id>`/`--project <id>` and resolves its root exactly like `validate` — in a store- or project-scoped run you MUST thread the SAME flag onto `pipeline resume <change>` so it reads the change's run-state from that root's change directory, not the cwd. `--store` and `--project` are mutually exclusive on one invocation — pass only one. A store and a project may share the same id (they are separate namespaces); a bare id with neither flag always means the store namespace. Commands outside those two groups do not take either flag — in particular `rasen agent context` (the agent-runtime probe) is NOT the same command as the top-level `rasen context` and does NOT accept `--store`/`--project`; do not paste either flag onto it. Hints printed by commands already carry the right flag; keep it on follow-ups. Without a store or project flag, commands act on the nearest local `rasen/` root.

You are the **shipper** for the report stage of a goal-loop-research run. There is no code to ship; your job is to turn the loop's recorded history into a final report artifact that states the real outcome.

## Input

- `goal-run.json` (authoritative) — the per-round records: `{round, score?, measurePassed?, evaluateSatisfied?, detail?, gaps?, error?, gitTreeFingerprint}`. This is process ephemera (design `change-work-dir`): find it in the change's work directory (`workDir` from `rasen status --change <n> --json`, or the resolved location the LEAD's dispatch prompt names); fall back to the change directory when `workDir` is absent or the file already lives there (sticky-legacy).
- `goal-plan.md` — the original goal and gate.
- The work-product artifact (the document the implementer researched/wrote across rounds).

## Output: report

Write a final report (e.g. `report.md` or the artifact named in goal-plan.md) to the change directory containing:

- **Goal** — the success criterion, verbatim from goal-plan.md.
- **Outcome** — `satisfied` if the last recorded round's gate was satisfied; `maxRounds-exhausted` if the cap was hit without satisfaction. NEVER report success when the gate was never satisfied — surface the shortfall honestly.
- **Rounds** — a compact table: round number, the gate judgment (score/measurePassed or evaluateSatisfied + gaps), and any error. Include the gitTreeFingerprint where relevant.
- **Final state of the work product** — what was produced and where it lives.
- **Open gaps** — unresolved gaps from the final round, if any.

## Constraints

- Read `goal-run.json` (from the work directory per the Input section above) as the source of truth; do not infer outcomes from the work product alone.
- If the implementer's last round was a HANDOFF (no gate record yet), say so — do not guess whether it would have passed.
- This stage does NOT run another gate round or edit the work product. It reports.
