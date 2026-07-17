---
name: rasen:codebase-design
description: |
license: MIT
compatibility: Requires rasen CLI.
metadata:
  author: rasen
  version: "1.0"
  generatedBy: "0.1.3"
---

<!-- adapted from mattpocock/skills (MIT, Copyright Matt Pocock) -->

## Preamble (run first)

```bash
_BRANCH=$(git branch --show-current 2>/dev/null || echo "unknown")
echo "BRANCH: $_BRANCH"
```

**Config (embedded at install time):**
- **Proactive:** `true` — if `false`, do not proactively suggest expert skills. Only invoke them when the user explicitly asks.
- **Repo mode:** `collaborative` — controls issue ownership behavior (see Repo Ownership Mode below).

## Canonical severity vocabulary

Findings from the generic expert skills (review, cso, qa, qa-only, benchmark, design-review) feed one canonical severity scale — the same scale the review→fix loop and the verify stage consume to decide clean vs. escalate. Classify against these four levels:

- **Blocker** — must not ship: wrong behavior on a common path, data loss or corruption, an exploitable security hole, a failing test or gate, or a required spec behavior missing.
- **Major** — should not ship without an explicit decision: wrong behavior on a plausible path, or a significant regression.
- **Minor** — ship-able friction or quality; recorded as accepted-known, never silently dropped.
- **Trivial** — cosmetic or a nit.

Each expert speaks a native scale; map it onto the canonical scale below. **Finding content overrides the native label where they disagree** — an item that names data loss, a security hole, or silent corruption maps UP regardless of the label its skill gave it (e.g. a review `INFORMATIONAL` item describing silent data corruption is Major, not Minor).

| Expert (native scale) | Blocker | Major | Minor | Trivial |
|---|---|---|---|---|
| review `CRITICAL` / `INFORMATIONAL` | `CRITICAL` naming data-loss / security / corruption / crash on a common path | other `CRITICAL` (correctness); `INFORMATIONAL` naming data-loss / security / silent corruption | `INFORMATIONAL` (default) | pure nit / style |
| cso `CRITICAL` / `HIGH` / `MEDIUM` (+ conf N/10) | `CRITICAL` | `HIGH` | `MEDIUM` | — (cso drops < MEDIUM by design) |
| qa / qa-only `critical` / `high` / `medium` / `low` / `cosmetic` | `critical` | `high` | `medium` / `low` | `cosmetic` |
| benchmark `REGRESSION` / `WARNING` / `OK` (+ Grade A–F) | `REGRESSION` crossing a hard budget (a FAIL row) | `REGRESSION` (timing / size) | `WARNING` | `OK`; grade-only deltas |
| design-review impact `high` / `medium` / `polish` (+ Grade A–F) | high-impact broken / unusable UI (rare) | high impact | medium | polish |
| codex `[P1]` / `[P2]` (display-only, not gate-consumed) | `[P1]` | `[P2]` | — | — |

In dispatched mode (see below) each expert self-maps and tags every finding it emits with a canonical severity in its report file, so the LEAD and the loop never have to infer a mapping.

## Dispatched vs standalone mode

The generic expert skills (review, cso, qa, qa-only, benchmark, design-review) run in one of two modes. Detect the mode from your own invocation — no flag is required:

- **Dispatched (report-only) mode** — your invocation instructs you to do a single unit of work, to not spawn subagents, and states that a LEAD owns orchestration (the signature every orchestrated dispatch carries). You are a role-isolated leaf reviewer worker.
- **Standalone mode** — a human invoked you directly (none of the above). Keep your full behavior as described in this skill.

If an explicit `MODE: dispatched (report-only)` token is present in your instructions, honor it; the self-trigger above is the fallback when the token is absent.

**In dispatched mode you MUST:**
- Apply **no** AUTO-FIX and make **no** code edits. Fix-class items are reported for the LEAD's triage to a non-author fixer, never applied by you.
- Issue **no** `AskUserQuestion`. There is no interactive user at a leaf worker; ASK-class items are reported as unresolved findings for the LEAD.
- Make **no** `git commit`. The LEAD / ship owns commits; concurrent commits on the shared index clobber each other.
- Spawn **no** subagents of your own. Independence comes from the LEAD's parallel reviewers and the mandatory non-author re-review, not from a leaf worker's own fan-out.
- Return classified findings and **write only the canonical `<skill>-report.md`** (review → `review-report.md`, cso → `cso-report.md`, qa and qa-only → `qa-report.md`, benchmark → `benchmark-report.md`, design-review → `design-review-report.md`) in the change's **work directory** — the `workDir` reported by `rasen status --change <name> --json` (or the dispatch prompt); fall back to the change directory when `workDir` is absent or the report already lives there (sticky-legacy) — each finding tagged with a canonical severity. Do NOT also write to the standalone `.rasen/*-reports/` or `~/.rasen/projects/` paths.

These dispatched-mode prohibitions **override** any contrary standalone instruction later in this skill (fix loops, batched questions, clean-tree gates, adversarial subagent dispatch, native report paths). Standalone mode retains all of that behavior.

**Denied-edit honesty.** If an Edit or Write you attempt is **denied** by an active edit boundary — a `/freeze` or `/guard` whose target is outside the allowed directory — the fix did NOT land. Report it as an un-applied finding, `[BLOCKED: freeze/guard] file:line — proposed fix`, never as `[AUTO-FIXED]`, and never silently drop it. The boundary hook wins over any Fix-First rule; do not claim a fix succeeded when it was refused. (Dispatched mode does no AUTO-FIX at all; this clause primarily governs the standalone fix loops.)

## AskUserQuestion Format

**ALWAYS follow this structure for every AskUserQuestion call:**
1. **Re-ground (per the Dialogue Override):** State the project, the current branch (use the `_BRANCH` value printed by the preamble — NOT any branch from conversation history or gitStatus), and the current plan/task (1-2 sentences). This step follows the Dialogue Override's re-ground rule — restate at the START of a session or after a genuine long gap, NOT on every consecutive AskUserQuestion call in continuous back-and-forth. The "for every AskUserQuestion call" framing above does NOT require repeating the full project/branch/plan opener between consecutive replies (steps 2–4 apply every call; this re-ground is gap-gated).
2. **Simplify:** Explain the problem in plain English a smart 16-year-old could follow. No raw function names, no internal jargon, no implementation details. Use concrete examples and analogies. Say what it DOES, not what it's called.
3. **Recommend:** `RECOMMENDATION: Choose [X] because [one-line reason]` — always prefer the complete option over shortcuts. Include `Completeness: X/10` for each option **only when the decision weighs a shortcut against a complete implementation**; discussion-type or exploratory forks do NOT carry a Completeness score. Calibration (when it applies): 10 = complete implementation (all edge cases, full coverage), 7 = covers happy path but skips some edges, 3 = shortcut that defers significant work. If both options are 8+, pick the higher; if one is ≤5, flag it.
4. **Options:** Lettered options: `A) ... B) ... C) ...` — when an option involves effort, show both scales: `(human: ~X / CC: ~Y)`

Assume the user hasn't looked at this window in 20 minutes and doesn't have the code open. If you'd need to read the source to understand your own explanation, it's too complex.

Per-skill instructions may add additional formatting rules on top of this baseline.

## Dialogue Override

AskUserQuestion is a **decision tool, not a conversation tool.** Before every AskUserQuestion call, read the user's previous message. If it contains a question, a request to explain or discuss, or free-text that is not a clean selection of one of your options → **pause the question flow.** Answer in body prose — no lettered options, no `RECOMMENDATION`, no `Completeness` score — and keep discussing until the user explicitly signals to proceed. Then resume the phase exactly where you paused; never skip ahead.

- **Never answer and advance in the same turn.** Answer the question this turn; ask your next question only once the user signals they are ready.
- **A request for more dialogue is the opposite of a skip signal.** "Answer me first," "let's discuss," and repeated follow-up questions mean the user wants *more* conversation — they NEVER trigger a fast-forward, an escape hatch, or a jump to the next phase.
- **Re-ground only after a genuine long gap.** In continuous back-and-forth, do not repeat the template opener (project / branch / plan restatement) on every turn — it belongs at the start of a session or after the user has been away, not between consecutive replies.

## Repo Ownership Mode — See Something, Say Something

`Repo mode` from the preamble config tells you who owns issues in this repo:

- **`solo`** — One person does 80%+ of the work. They own everything. When you notice issues outside the current branch's changes (test failures, deprecation warnings, security advisories, linting errors, dead code, env problems), **investigate and offer to fix proactively**. The solo dev is the only person who will fix it. Default to action.
- **`collaborative`** — Multiple active contributors. When you notice issues outside the branch's changes, **flag them via AskUserQuestion** — it may be someone else's responsibility. Default to asking, not fixing.
- **`unknown`** — Treat as collaborative (safer default — ask before fixing).

**See Something, Say Something:** Whenever you notice something that looks wrong during ANY workflow step — not just test failures — flag it briefly. One sentence: what you noticed and its impact. In solo mode, follow up with "Want me to fix it?" In collaborative mode, just flag it and move on.

Never let a noticed issue silently pass. The whole point is proactive communication.

**Scope (dispatched leaf workers override this section):** every absolute above — `solo`'s "**investigate and offer to fix proactively**" / "**Default to action**", the "**ANY workflow step**" reach of See-Something-Say-Something, and "**Never let a noticed issue silently pass**" — is scoped to **interactive / standalone** sessions, where you can actually reach the user to offer a fix. When you are a **dispatched leaf worker** (a one-unit-of-work dispatch under the LEAD; see the dispatched-mode contract), this whole section is OVERRIDDEN: an out-of-scope issue you notice goes into your `DONE` **durable-findings** for the LEAD to triage — you do NOT investigate it, fix it, or ask the user about it (you cannot reach the user, and investigating breaks your one-unit-of-work isolation). Recording it in durable-findings IS "not letting it silently pass" — it is the dispatched-mode form of the same discipline. This is consistent with the dispatched-mode one-unit-of-work contract; it does NOT reopen the report-only dispatched contract.

## Completion Status Protocol

When completing a skill workflow, report status using one of:
- **DONE** — All steps completed successfully. Evidence provided for each claim.
- **DONE_WITH_CONCERNS** — Completed, but with issues the user should know about. List each concern.
- **BLOCKED** — Cannot proceed. State what is blocking and what was tried.
- **NEEDS_CONTEXT** — Missing information required to continue. State exactly what you need.

### Escalation

It is always OK to stop and say "this is too hard for me" or "I'm not confident in this result."

Bad work is worse than no work. You will not be penalized for escalating.
- If you have attempted a task 3 times without success, STOP and escalate.
- If you are uncertain about a security-sensitive change, STOP and escalate.
- If the scope of work exceeds what you can verify, STOP and escalate.

Escalation format:
```
STATUS: BLOCKED | NEEDS_CONTEXT
REASON: [1-2 sentences]
ATTEMPTED: [what you tried]
RECOMMENDATION: [what the user should do next]
```

## Plan Status Footer

When you are in plan mode and about to call ExitPlanMode:

1. Check if the plan file already has a `## GSTACK REVIEW REPORT` section.
2. If it DOES — skip (a review skill already wrote a richer report).
3. If it does NOT — write a `## GSTACK REVIEW REPORT` section to the end of the plan file with this placeholder table:

\`\`\`markdown
## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| Verify | \`/rasen:verify\` | Implementation matches the change artifacts | 0 | — | — |
| Verify (enhanced) | \`/rasen:verify-enhanced\` | Adds code-review, security, and browser passes | 0 | — | — |
| Review cycle | \`/rasen:review-cycle\` | Iterate review → triage → fix until clean | 0 | — | — |
| Codex Review | \`/codex review\` | Independent 2nd opinion | 0 | — | — |

**VERDICT:** NO REVIEWS YET — run \`/rasen:review-cycle\` for the full review loop, or the individual reviews above.
\`\`\`

**PLAN MODE EXCEPTION — ALWAYS RUN:** This writes to the plan file, which is the one
file you are allowed to edit in plan mode. The plan file review report is part of the
plan's living status.

# Codebase Design

Design **deep modules**: a lot of behaviour behind a small interface, placed at a clean seam, testable through that interface. Use this language and these principles wherever code is being designed or restructured. The aim is leverage for callers, locality for maintainers, and testability for everyone.

## Glossary

Use these terms exactly — don't substitute "component," "service," "API," or "boundary." Consistent language is the whole point.

**Module** — anything with an interface and an implementation. Deliberately scale-agnostic: a function, class, package, or tier-spanning slice. _Avoid_: unit, component, service.

**Interface** — everything a caller must know to use the module correctly: the type signature, but also invariants, ordering constraints, error modes, required configuration, and performance characteristics. _Avoid_: API, signature (too narrow — they refer only to the type-level surface).

**Implementation** — what's inside a module, its body of code. Distinct from **Adapter**: a thing can be a small adapter with a large implementation (a Postgres repo) or a large adapter with a small implementation (an in-memory fake). Reach for "adapter" when the seam is the topic; "implementation" otherwise.

**Depth** — leverage at the interface: the amount of behaviour a caller (or test) can exercise per unit of interface they have to learn. A module is **deep** when a large amount of behaviour sits behind a small interface, **shallow** when the interface is nearly as complex as the implementation.

**Seam** _(Michael Feathers)_ — a place where you can alter behaviour without editing in that place; the *location* at which a module's interface lives. Where to put the seam is its own design decision, distinct from what goes behind it. _Avoid_: boundary (overloaded with DDD's bounded context).

**Adapter** — a concrete thing that satisfies an interface at a seam. Describes *role* (what slot it fills), not substance (what's inside).

**Leverage** — what callers get from depth: more capability per unit of interface they learn. One implementation pays back across N call sites and M tests.

**Locality** — what maintainers get from depth: change, bugs, knowledge, and verification concentrate in one place rather than spreading across callers. Fix once, fixed everywhere.

## Deep vs shallow

**Deep module** = small interface + lots of implementation:

```
┌─────────────────────┐
│   Small Interface   │  ← Few methods, simple params
├─────────────────────┤
│                     │
│  Deep Implementation│  ← Complex logic hidden
│                     │
└─────────────────────┘
```

**Shallow module** = large interface + little implementation (avoid):

```
┌─────────────────────────────────┐
│       Large Interface           │  ← Many methods, complex params
├─────────────────────────────────┤
│  Thin Implementation            │  ← Just passes through
└─────────────────────────────────┘
```

When designing an interface, ask:

- Can I reduce the number of methods?
- Can I simplify the parameters?
- Can I hide more complexity inside?

## Principles

- **Depth is a property of the interface, not the implementation.** A deep module can be internally composed of small, mockable, swappable parts — they just aren't part of the interface. A module can have **internal seams** (private to its implementation, used by its own tests) as well as the **external seam** at its interface.
- **The deletion test.** Imagine deleting the module. If complexity vanishes, it was a pass-through. If complexity reappears across N callers, it was earning its keep.
- **The interface is the test surface.** Callers and tests cross the same seam. If you want to test *past* the interface, the module is probably the wrong shape.
- **One adapter means a hypothetical seam. Two adapters means a real one.** Don't introduce a seam unless something actually varies across it.

## Designing for testability

Good interfaces make testing natural:

1. **Accept dependencies, don't create them.**

   ```typescript
   // Testable
   function processOrder(order, paymentGateway) {}

   // Hard to test
   function processOrder(order) {
     const gateway = new StripeGateway();
   }
   ```

2. **Return results, don't produce side effects.**

   ```typescript
   // Testable
   function calculateDiscount(cart): Discount {}

   // Hard to test
   function applyDiscount(cart): void {
     cart.total -= discount;
   }
   ```

3. **Small surface area.** Fewer methods = fewer tests needed. Fewer params = simpler test setup.

## Relationships

- A **Module** has exactly one **Interface** (the surface it presents to callers and tests).
- **Depth** is a property of a **Module**, measured against its **Interface**.
- A **Seam** is where a **Module**'s **Interface** lives.
- An **Adapter** sits at a **Seam** and satisfies the **Interface**.
- **Depth** produces **Leverage** for callers and **Locality** for maintainers.

## Rejected framings

- **Depth as ratio of implementation-lines to interface-lines** (Ousterhout): rewards padding the implementation. We use depth-as-leverage instead.
- **"Interface" as the TypeScript `interface` keyword or a class's public methods**: too narrow — interface here includes every fact a caller must know.
- **"Boundary"**: overloaded with DDD's bounded context. Say **seam** or **interface**.

## Going deeper

- **Deepening a cluster given its dependencies** — see [DEEPENING.md](DEEPENING.md): dependency categories, seam discipline, and replace-don't-layer testing.
- **Exploring alternative interfaces** — see [DESIGN-IT-TWICE.md](DESIGN-IT-TWICE.md): spin up parallel sub-agents to design the interface several radically different ways, then compare on depth, locality, and seam placement.

**Store selection:** If the user names a store (a store is a standalone Rasen repo registered on this machine) or the work lives in one, run `rasen store list --json` to discover registered store ids and project ids (the `type` field on each entry), then pass `--store <id>` (or `--project <id>` for a project registered via `store add-project`) on the commands that read or write specs and changes (`new change`, `status`, `instructions`, `list`, `show`, `validate`, `archive`, `doctor`, and the top-level `context`). The `rasen pipeline` inspection group (`pipeline list`, `pipeline show`, `pipeline agents`, `pipeline classify`, `pipeline resume`) also accepts `--store <id>`/`--project <id>` and resolves its root exactly like `validate` — in a store- or project-scoped run you MUST thread the SAME flag onto `pipeline resume <change>` so it reads the change's run-state from that root's change directory, not the cwd. `--store` and `--project` are mutually exclusive on one invocation — pass only one. A store and a project may share the same id (they are separate namespaces); a bare id with neither flag always means the store namespace. Commands outside those two groups do not take either flag — in particular `rasen agent context` (the agent-runtime probe) is NOT the same command as the top-level `rasen context` and does NOT accept `--store`/`--project`; do not paste either flag onto it. Hints printed by commands already carry the right flag; keep it on follow-ups. Without a store or project flag, commands act on the nearest local `rasen/` root.
