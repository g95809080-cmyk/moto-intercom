---
name: rasen:guard
description: |
license: MIT
compatibility: Requires rasen CLI.
metadata:
  author: rasen
  version: "1.0"
  generatedBy: "0.1.3"
---

# /guard — Full Safety Mode

Activates both destructive command warnings and directory-scoped edit restrictions.
This is the combination of `/careful` + `/freeze` in a single command.

**Dependency note:** This skill references hook scripts from the sibling `/careful`
and `/freeze` skill directories. Both must be installed (they are installed together
by the gstack setup script).

## Setup

Ask the user which directory to restrict edits to. Use AskUserQuestion:

- Question: "Guard mode: which directory should edits be restricted to? Destructive command warnings are always on. Files outside the chosen path will be blocked from editing."
- Text input (not multiple choice) — the user types a path.

Once the user provides a directory path:

1. Resolve it to an absolute path:
```bash
FREEZE_DIR=$(cd "<user-provided-path>" 2>/dev/null && pwd)
echo "$FREEZE_DIR"
```

2. Ensure trailing slash and save to the freeze state file:
```bash
FREEZE_DIR="${FREEZE_DIR%/}/"
STATE_DIR="${CLAUDE_PLUGIN_DATA:-$HOME/.gstack}"
mkdir -p "$STATE_DIR"
echo "$FREEZE_DIR" > "$STATE_DIR/freeze-dir.txt"
echo "Freeze boundary set: $FREEZE_DIR"
```

Tell the user:
- "**Guard mode active.** Two protections are now running:"
- "1. **Destructive command warnings** — rm -rf, DROP TABLE, force-push, etc. will warn before executing (you can override)"
- "2. **Edit boundary** — file edits restricted to `<path>/`. Edits outside this directory are blocked."
- "To remove the edit boundary, run `/unfreeze`. To deactivate everything, end the session."

## What's protected

See `/careful` for the full list of destructive command patterns and safe exceptions.
See `/freeze` for how edit boundary enforcement works.

**Store selection:** If the user names a store (a store is a standalone Rasen repo registered on this machine) or the work lives in one, run `rasen store list --json` to discover registered store ids and project ids (the `type` field on each entry), then pass `--store <id>` (or `--project <id>` for a project registered via `store add-project`) on the commands that read or write specs and changes (`new change`, `status`, `instructions`, `list`, `show`, `validate`, `archive`, `doctor`, and the top-level `context`). The `rasen pipeline` inspection group (`pipeline list`, `pipeline show`, `pipeline agents`, `pipeline classify`, `pipeline resume`) also accepts `--store <id>`/`--project <id>` and resolves its root exactly like `validate` — in a store- or project-scoped run you MUST thread the SAME flag onto `pipeline resume <change>` so it reads the change's run-state from that root's change directory, not the cwd. `--store` and `--project` are mutually exclusive on one invocation — pass only one. A store and a project may share the same id (they are separate namespaces); a bare id with neither flag always means the store namespace. Commands outside those two groups do not take either flag — in particular `rasen agent context` (the agent-runtime probe) is NOT the same command as the top-level `rasen context` and does NOT accept `--store`/`--project`; do not paste either flag onto it. Hints printed by commands already carry the right flag; keep it on follow-ups. Without a store or project flag, commands act on the nearest local `rasen/` root.
