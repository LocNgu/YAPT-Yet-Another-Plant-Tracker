---
description: How Claude sessions and agents read/write GitHub — MCP by default, gh as the fallback
paths:
  - ".claude/**"
---

# GitHub access (technical ADR-0042)

**Repository:** owner `LocNgu`, repo `YAPT-Yet-Another-Plant-Tracker` (case-insensitive). `gh` infers
both from the checkout, so `gh` commands need no `-R`.

## Which path

1. **MCP (default).** If `mcp__github__*` tools are in the session's tool list — or listed as deferred
   tools you load with `ToolSearch` — use them. Cloud (claude.ai/code) sessions always have them there,
   and their `gh` is restricted to search.
2. **`gh` (fallback).** Only when no `mcp__github__*` tool is available. Check `gh auth status` first.
3. **Neither** (no MCP and `gh auth status` fails): the orchestrator tells the human what it could not
   post and stops that step. Never skip a comment silently.

Pick the path once per phase; never split one phase's comment across MCP and `gh`.

## Who may write

**Only the orchestrator writes to GitHub**, by either path. Subagents (`spec`, `implementer`,
`reviewer`, `qa`) only read, and return their comment/PR text for the orchestrator to post — even
though the shared `settings.json` would let any agent with `Bash` run the allowlisted `gh` writes.

## Command mapping

| Operation | MCP | `gh` | `gh` permission |
|---|---|---|---|
| Read issue + comments | `issue_read` `get` / `get_comments` | `gh issue view <n> --comments` | allowed |
| Read PR / diff / files | `pull_request_read` `get` / `get_diff` / `get_files` | `gh pr view <n>` / `gh pr diff <n>` / `gh pr view <n> --json files` | allowed |
| CI status | `pull_request_read` check-run/status methods / `actions_*` | `gh pr checks <n>` / `gh run view <id>` | allowed |
| Comment on issue | `add_issue_comment` | `gh issue comment <n> --body-file <file>` | **allowlisted write** |
| Comment on PR | `add_issue_comment` (PR number) | `gh pr comment <n> --body-file <file>` | **allowlisted write** |
| Create PR | `create_pull_request` (base `develop`) | `gh pr create --base develop --title … --body-file <file>` | **allowlisted write** — `--base develop` must come first |
| Post review (review + CI result) | `pull_request_review_write` + `add_comment_to_pending_review`, `event: COMMENT` | `gh pr comment <n> --body-file <file>`, opening with "Review (posted as a PR comment — `gh` fallback) for `<sha>`" and inline findings written as `File.kt:42 — **BLOCKING**: …` in the body; re-check the PR head against `<sha>` before posting, as on the MCP path | allowlisted (`gh pr review` stays **prompted**) |

- Prefer `--body-file` (a file in your scratchpad) over `-b "…"` for multi-line Markdown — it avoids
  shell-quoting mistakes, and an inline `-b "…"` body that merely contains a denied string below
  (`--delete-last`, `--base main`, `hosts.yml`, `.ssh/`, `.env`, …) is itself denied.
- **Denied:** `--delete-last` on `gh issue comment`/`gh pr comment`; `gh pr create` with `--base main`
  in any common spelling (`--base=main`, `-B main`, quoted, `-Bmain`); any `gh` command line naming a
  known secret path (`gh` token file, `~/.ssh`, `~/.aws`, `.netrc`, `.git-credentials`, `.env`), so
  `--body-file` can't post one — this also denies a `gh search` term or PR title containing `.env`
  (e.g. `process.env.FOO`); rephrase it. **Prompted:** `gh pr review` (any form), `gh pr create` without a leading
  `--base develop`, and every other `gh` write (merge, close, edit, label). Rationale per flag:
  technical ADR-0042.
- Every comment ends with the attribution footer the session's instructions require.
