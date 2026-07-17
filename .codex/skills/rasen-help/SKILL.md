---
name: rasen-help
description: Ask which Rasen command, skill, or flow fits your situation - guides new users from zero, routes daily work, and unlocks advanced usage like custom pipelines.
license: MIT
compatibility: Requires rasen CLI.
metadata:
  author: rasen
  version: "1.0"
  generatedBy: "0.1.3"
---

Help the user find their way around Rasen. You are the map, not the worker: explain, route, and hand off to the command or skill that does the actual work. The only work you do yourself is configuration the user explicitly asks for (editing `rasen/config.yaml`, re-running `rasen update`, writing a custom `pipeline.yaml`).

**Store selection:** If the user names a store (a store is a standalone Rasen repo registered on this machine) or the work lives in one, run `rasen store list --json` to discover registered store ids and project ids (the `type` field on each entry), then pass `--store <id>` (or `--project <id>` for a project registered via `store add-project`) on the commands that read or write specs and changes (`new change`, `status`, `instructions`, `list`, `show`, `validate`, `archive`, `doctor`, and the top-level `context`). The `rasen pipeline` inspection group (`pipeline list`, `pipeline show`, `pipeline agents`, `pipeline classify`, `pipeline resume`) also accepts `--store <id>`/`--project <id>` and resolves its root exactly like `validate` — in a store- or project-scoped run you MUST thread the SAME flag onto `pipeline resume <change>` so it reads the change's run-state from that root's change directory, not the cwd. `--store` and `--project` are mutually exclusive on one invocation — pass only one. A store and a project may share the same id (they are separate namespaces); a bare id with neither flag always means the store namespace. Commands outside those two groups do not take either flag — in particular `rasen agent context` (the agent-runtime probe) is NOT the same command as the top-level `rasen context` and does NOT accept `--store`/`--project`; do not paste either flag onto it. Hints printed by commands already carry the right flag; keep it on follow-ups. Without a store or project flag, commands act on the nearest local `rasen/` root.

## How to answer

1. **Gauge the user's level from evidence, not assumption.** Run `rasen status` (and `rasen list`) quietly first: no `rasen/` directory → they are at Level 0; a workspace with changes → route within Level 1–2; questions about gates, roles, stores, or multi-repo → Level 3. Their question overrides the probe — a beginner can ask an advanced question.
2. **Ground every version-, flag-, or state-specific claim** in actual CLI output: `rasen --version`, `rasen --help`, `rasen <command> --help`, `rasen pipeline list`. Never invent flags — check `--help` first when unsure.
3. **Close with one next action** — a single command to run, not a menu. If the user already knows which command they want, skip routing and answer the question directly.

## Level 0 — Never used Rasen (start here for "what is this?")

Give the mental model in three sentences before any command:

> Rasen turns AI-assisted work into **spec-driven development**: your `rasen/specs/` are the long-term truth about how the system behaves, and every piece of work is a **change** — a folder carrying its own proposal (why), delta specs (what), design (how), and tasks (steps) from idea to archive. Commands and skills drive that loop by hand; orchestration commands run the same loop autonomously with review built in. When a change ships, its delta specs merge into the main specs, so the truth stays current and the change folder becomes searchable decision history.

Then the first-session path — in order, one step at a time:

1. `rasen init` — pick your AI tools and a profile (`core` is the streamlined set; `full` is everything).
2. `/rasen:onboard` — a guided 15–20 minute walkthrough that does one real small task in their codebase through the complete cycle, narrating each artifact. **Route every first-time user here before anything else.**
3. First real work: `/rasen:propose <idea>` — then follow the main flow below.

If they ask "why not just prompt the AI directly?": the artifacts are the answer — specs survive the conversation, reviews check the diff against a written contract instead of vibes, and the archive explains next quarter why the code is the way it is.

## Level 1 — The main flow (daily work)

A change moves: **propose → apply → verify → sync → archive**.

| Step | Command | What happens |
|---|---|---|
| 1 | `/rasen:propose <idea>` | Creates the change, drafts all artifacts in one pass |
| 2 | `/rasen:apply <name>` | Implements the tasks, checking them off |
| 3 | `/rasen:verify <name>` | Checks implementation against the artifacts (`/rasen:verify-enhanced` for the deeper multi-pass review) |
| 4 | `/rasen:sync <name>` | Merges the change's delta specs into main specs |
| 5 | `/rasen:archive <name>` | Closes the loop; the change becomes decision history |

**Choosing a variant** — route by situation:
- Idea still fuzzy → `/rasen:office-hours <topic>` (structured design Q&A) or `/rasen:explore` (investigate, no code changes).
- Want artifacts one at a time with review between → `/rasen:new <name>`, then `/rasen:continue <name>`.
- Want everything scaffolded at once → `/rasen:ff <name>`.
- Several finished changes piled up → `/rasen:bulk-archive`.
- Session running out of context mid-change → `/rasen:handoff` (writes a handoff document a fresh session resumes from).

## Level 2 — Autonomous orchestration

When the user wants Rasen to drive the loop instead of stepping manually:

- **`/rasen:auto <task>`** — the default "do this for me": a LEAD agent classifies the task, picks a pipeline, and drives role agents (planner/implementer/reviewer/fixer/shipper) through it, pausing at gates for approval.
- **`/rasen:review-cycle <name>`** — adversarial review loop over a change until findings run dry.
- **`/rasen:ship <name>`** — finalize: commit, sync specs, archive.
- **`/rasen:goal <goal>`** — goal-driven iteration (plan → iterate → report) toward a measurable target; for "make X faster/better" work rather than a defined feature.
- **`/rasen:retro`** — retrospective over recent work.

Resuming and inspecting a run: `rasen pipeline resume <change>` shows a change's run-state (next/remaining stages); `/rasen:auto` picks up where it left off.

## Level 3 — Advanced: pipelines, gates, and extension

For users who ask "can I change how the autonomous run works?" — yes, this is the extension surface:

**Inspect what exists.** `rasen pipeline list` shows available pipelines and where each comes from (**project > user > package** — nearer wins by name). Built-ins include `bug-fix`, `small-feature`, `full-feature`, `auto-decompose`, and the `goal-loop-*` family. `rasen pipeline show <name>` prints a pipeline's stage DAG and build order; `rasen pipeline classify "<task>"` suggests which pipeline fits a task.

**Build your own pipeline.** A pipeline is a YAML file at `rasen/pipelines/<name>/pipeline.yaml` (project-level) or `<machine home>/pipelines/<name>/pipeline.yaml` (user-level, default `~/.rasen`). Start by copying a built-in's structure from `rasen pipeline show <name> --json`. Each stage declares:
- `id` + `skill` — which worker skill runs (e.g. `rasen-propose`, `rasen-apply-change`, `rasen-ship`)
- `role` — planner / implementer / reviewer / fixer / shipper
- `requires` — DAG edges to earlier stage ids
- `gate: true` — a human approval pause point
- optional `condition`, `verifyPolicy`, `model`, `childPipeline` (fan-out to child changes, see `auto-decompose`)

A project-level pipeline with the same name as a built-in **overrides it** — that is how you customize `/rasen:auto`'s behavior for one repo. Verify with `rasen pipeline show <name>` that your file resolves and the DAG is what you meant.

**Tune gates and roles.**
- `rasen/config.yaml` → `autopilot.gates: on|off` — whether ordinary gates pause for approval (default on) or auto-approve.
- `rasen pipeline agents <name> --reviewer codex --implementer claude` — assign per-role runtimes for a pipeline.

**Work across repositories.** Register other Rasen repos as stores (`rasen store register <path>`) or projects (`rasen store add-project`), then target them with `--store <id>` / `--project <id>` on workspace commands. `rasen store list --json` shows what's registered.

**Adjust what's installed.** Profile (`full` / `core` / `custom` with an explicit workflow list) controls WHICH workflows install; delivery (`both` / `skills`) controls HOW. Change via `rasen config` or re-running `rasen init`, then `rasen update` regenerates.

## CLI quick reference

| Command | What it does |
|---|---|
| `rasen init [path]` | Set up Rasen in a project; creates `rasen/` |
| `rasen update` | Regenerate skills/commands after upgrade or config change |
| `rasen migrate` | Copy-only migration of a legacy upstream OpenSpec workspace into `rasen/` |
| `rasen status` / `rasen list` | Where things stand: changes, specs, artifact progress |
| `rasen show` / `rasen view` | Inspect a change or spec |
| `rasen validate` | Check specs/changes for structural problems |
| `rasen pipeline …` | list / show / classify / resume / agents (see Level 3) |
| `rasen store …` | Register stores/projects for cross-repo work |
| `rasen doctor` | Diagnose install/workspace state |
| `rasen feedback <message>` | File feedback as a GitHub issue on the Rasen repo |

## Configuration & privacy

- Project config: `rasen/config.yaml`. Machine state: `~/.rasen` (override with the `RASEN_HOME` environment variable).
- Telemetry is anonymous (command name, version, anonymous UUID, OS/Node only). Opt out with `RASEN_TELEMETRY=0` or `DO_NOT_TRACK=1`; disabled automatically in CI.
- Upstream coexistence: Rasen lives in its own namespaces (`rasen` binary, `/rasen:*`, `rasen-*` skills, `rasen/` workspace). An upstream OpenSpec install in the same project keeps working untouched; `rasen migrate` copies its legacy workspace into `rasen/` and never modifies the original.

## Troubleshooting

- **"Missing rasen/ directory"** — not initialized: `rasen init` (or `rasen migrate` if a legacy upstream OpenSpec workspace exists).
- **Slash commands or skills missing/stale** — `rasen update`; if something specific is absent, check profile and delivery (a custom profile only installs its listed workflows).
- **Pipeline not found / wrong pipeline running** — `rasen pipeline list` to see resolution order; a project-level file shadows same-named built-ins.
- **Environment looks broken** — `rasen doctor`.
- Anything beyond this map: `rasen <command> --help` is authoritative for flags; `rasen feedback` files a bug.

## Guardrails

- Route and explain; do NOT start another workflow's work from here — name the command and offer to run it as the next step.
- Gauge level from `rasen status` evidence and the question itself; never condescend to an advanced user or bury a beginner in Level 3.
- Ground version/flag/state claims in actual CLI output; check `--help` before answering when unsure.
- Keep answers short: the situation's flow, the one next action, and only the context needed to choose.
- If the question reveals a missing or broken install, fix setup first (init/update/doctor) before routing to workflow commands.
