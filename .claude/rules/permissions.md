---
description: Shared permission baseline in .claude/settings.json
paths:
  - ".claude/settings*.json"
---

# Permissions baseline (technical ADR-0024, technical ADR-0025)

Keep machine-specific paths and personal overrides in the untracked (git-ignored)
`.claude/settings.local.json`. Repository-wide allow/deny rules belong in `.claude/settings.json`;
GitHub CLI allowances there must be limited to read-only subcommands so external writes continue to
require explicit authorization, and a trailing `*` is only safe where every flag the subcommand accepts
both leaves state unchanged *and* discloses no credentials — `gh auth status` is an exact literal
because `--show-token` writes nothing yet prints the live token (technical ADR-0024). A #683 audit of
the baseline's remaining wildcards narrowed `git branch*` to its read-only listing forms and added deny
entries for branch mutation, `git stash drop`/`clear`, `find`'s action primaries, `git push
--force-with-lease` and `git switch` against `main`/`develop`, and known secret-file paths for
`cat`/`grep` — none of the latter is a security boundary, only a guard against accidental disclosure
via the commands people actually reach for (technical ADR-0025). Technical ADR-0042 adds exactly three
pinned `gh` writes (issue/PR comment, PR create against `develop`) for sessions without the GitHub MCP
server, with deny entries for the flags that fail the flag test; `gh pr review` stays prompted.

| Action | Permission |
|---|---|
| Read files · read-only git (branch listing narrowed to its named forms, #683) · `add`/`commit`/`stash` (excl. `drop`/`clear`)/`cherry-pick` · checkout/push `claude/*` · `./gradlew *` | Allowed, no prompt |
| GitHub **writes** — `mcp__github__*`, or the allowlisted `gh issue comment` / `gh pr comment` / `gh pr create --base develop` (technical ADR-0042) | **Orchestrator only** — subagents return text, orchestrator posts. Path selection and command mapping: `rules/github-access.md` |
| `gh pr review` (any form) · `gh pr create` without a leading `--base develop` · any other `gh` write | Prompts |
| `git checkout develop` · `git push origin develop` · `git push --force origin claude/*` | Prompts — approve when appropriate |
| `git checkout main` · `git push origin main` · force-push (`--force`/`-f`/`--force-with-lease`) main/develop · `git switch main` · `git branch -d`/`-D`/`-m`/`-M`/`--delete`/`--move` · `git stash drop`/`clear` · `find`'s action primaries (`-exec`/`-delete`/etc.) · `git reset --hard` · `gh issue comment`/`gh pr comment --delete-last` · `gh pr create` against `main` (common spellings) · `gh` naming a known secret path (e.g. as `--body-file`) · `cat`/`grep` of known secret paths (`~/.ssh`, `~/.aws`, `gh` token file, `.env`, etc., non-exhaustive) | **Forbidden** — blocked mechanically |
| Merging PRs by any means | **Forbidden** — human only |

**Deny symmetry rule (#687):** every protected-branch deny entry has both a bare form and a trailing
` *` form, for the plain command and its `git -C * …` twin (so extra arguments can't slip past the
deny). A new deny pair is added in all four shapes, each `-C *` entry placed directly after its bare
twin. The original deny block is no longer frozen: changes are ordinary reviewed diffs, preferably
additions only.
