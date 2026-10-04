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
via the commands people actually reach for (technical ADR-0025).

| Action | Permission |
|---|---|
| Read files · read-only git (branch listing narrowed to its named forms, #683) · `add`/`commit`/`stash` (excl. `drop`/`clear`)/`cherry-pick` · checkout/push `claude/*` · `./gradlew *` | Allowed, no prompt |
| `mcp__github__*` **writes** (issue/PR/review/comment/create_pr) | **Orchestrator only** — subagents return text, orchestrator posts |
| `git checkout develop` · `git push origin develop` · `git push --force origin claude/*` | Prompts — approve when appropriate |
| `git checkout main` · `git push origin main` · force-push (`--force`/`-f`/`--force-with-lease`) main/develop · `git switch main` · `git branch -d`/`-D`/`-m`/`-M`/`--delete`/`--move` · `git stash drop`/`clear` · `find`'s action primaries (`-exec`/`-delete`/etc.) · `git reset --hard` · `cat`/`grep` of known secret paths (`~/.ssh`, `~/.aws`, `gh` token file, `.env`, etc., non-exhaustive) | **Forbidden** — blocked mechanically |
| Merging PRs by any means | **Forbidden** — human only |
