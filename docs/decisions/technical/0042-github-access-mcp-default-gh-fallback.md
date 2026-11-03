# Technical ADR-0042: GitHub access — MCP by default, pinned `gh` writes as the fallback

**Status**: accepted

**Date**: 2026-11-03

## Context

The Claude workflow (`.claude/CLAUDE.md`, `.claude/pipeline.md`, the four agents in `.claude/agents/`)
reads and writes GitHub only through the `mcp__github__*` tools. Those tools exist in claude.ai/code
cloud sessions, but a local CLI session has them only if the contributor configured a GitHub MCP
server; no `.mcp.json` is committed. `CLAUDE.md` also claimed "`gh` is not installed", which is wrong:
`gh` is installed both locally (Homebrew) and in cloud sessions (where the environment restricts it to
search). So a session without MCP had no documented way to post a spec, review or QA comment, and the
pipeline only worked by accident through `gh` reads that no agent prompt mentioned (#326).

Technical ADR-0024 limits the shared `.claude/settings.json` to **read-only** `gh` subcommands and
explicitly keeps write-capable wildcards such as `gh pr *` out; technical ADR-0025 adds that a trailing
`*` is safe only where every flag the subcommand accepts leaves state unchanged and discloses no
credentials. Both ADRs are finalized. A `gh` fallback that still prompts on every orchestrator comment
would be usable but noisy; the human chose (#326 spec clarifications) to allowlist a small, named set of
`gh` writes instead, each justified against the flag test.

Alternatives considered:

- **`gh` as the default, MCP optional** (the 2026-08 proposal on #326). Rejected: cloud sessions — the
  common case for automated runs — allow `gh` for search only, so `gh` cannot be the default there.
- **Broad `gh pr *` / `gh issue *` allow entries.** Rejected: they admit merge, close, edit, label and
  approve, which technical ADR-0024 deliberately keeps behind a prompt.
- **A PreToolUse hook blocking subagent `gh` writes.** Out of scope for #326; the orchestrator-only
  rule stays a written rule.

## Decision

**MCP is the default; `gh` is the fallback.** A session uses the `mcp__github__*` tools when they are in
its tool list (or discoverable as deferred tools). Only when they are absent does it use `gh`, and only
after `gh auth status` succeeds. If neither works, the orchestrator tells the human and stops that step;
a comment is never skipped silently. One phase's comment never mixes the two paths. The operational
detail — detection, the read/write command mapping, owner/repo — lives in one place,
`.claude/rules/github-access.md`; `CLAUDE.md`, `rules/permissions.md`, `pipeline.md` and the agent files
point to it instead of repeating it.

**Three `gh` writes are allowlisted in `.claude/settings.json`**, each pinned against technical
ADR-0025's flag test, with deny entries for the flags that fail it:

| Allow entry | Flags that pass | Flags denied / accepted explicitly |
|---|---|---|
| `Bash(gh issue comment *)` | `-b`, `-e`, `-w` — each only adds a new comment | `-F`/`--body-file` **fails the flag test**: it posts any local file, so it could publish a credential file. It is kept (scratchpad body files are the recommended form) and guarded by deny entries on the known secret paths for every `gh` command — `gh * *hosts.yml*`, `*.ssh/*`, `*.aws/*`, `*.netrc*`, `*.git-credentials*`, `*.env` / `*.env *` / `*.env.*` — mirroring technical ADR-0025's `cat`/`grep` set; see *Accepted residual risk*. `--delete-last` (+ `--yes`) **denied** (`gh issue comment* --delete-last*`): it destroys a comment. `--edit-last` / `--create-if-none` **accepted**: they can only rewrite the session account's own latest comment, and GitHub keeps the edit history. |
| `Bash(gh pr comment *)` | same as above | same as above (`gh pr comment* --delete-last*` denied) |
| `Bash(gh pr create --base develop *)` | title/body/label/reviewer/draft flags — they shape the new PR only (`--body-file` as above) | The allow entry pins `--base develop` as the first argument, so every other shape (including a bare `gh pr create`) still prompts. `gh` string flags are last-wins, so a repeated, later `--base` would retarget the PR; the common spellings of that are **denied**: `--base main`, `--base=main`, `-B main`, their single- and double-quoted forms, and the attached `-Bmain` / `-B=main` / `-B"main"` / `-B'main'`. This covers careless use, not every shell spelling (see *Accepted residual risk*). A blanket `*main*` deny is avoided because it would block PR titles that mention "main". |

`gh pr create` never merges; merging stays human-only (`CLAUDE.md` Forbidden list). The orchestrator
pushes the `claude/*` branch with `git push` before creating the PR, so `gh` has nothing to push.

**`gh pr review` stays prompted.** `gh pr review` accepts `--approve`/`-a` and `--request-changes`/`-r`
anywhere on the line. A positive pattern such as `gh pr review * --comment*` cannot exclude them — a
review body that merely contains the text `--comment` would satisfy it alongside `--approve` — and deny
patterns on the short flags (`* -a*`, `* -r*`) would also match ordinary review prose, blocking
legitimate comments outright. Since no pattern can guarantee a COMMENT-only review, the `gh` fallback
posts the combined review+CI result with the allowlisted `gh pr comment`, putting inline findings in
the body as `File.kt:42 — **BLOCKING**: …` (the same form the reviewer already uses for unchanged
lines). A human may still approve a `gh pr review --comment` at the prompt.

**Accepted residual risk.** All three commands accept the inherited `-R/--repo` flag, so an allowlisted
comment or PR can target another repository the token reaches. The write is still only a comment or a
PR, never a merge, close or edit of someone else's content, so this is accepted rather than pinned —
flag order is free, so a literal pattern cannot pin it. Likewise, the secret-path and `--base main`
deny entries match the common spellings only — a path reached through a symlink, variable or other
shell indirection, or an unusual quoting of `main`, slips past them. Because the patterns match the
whole command line, they also deny an inline `-b "…"` body that merely contains one of those strings —
`--body-file` avoids that — and the `.env` patterns likewise deny an otherwise-allowed `gh search` term
or a PR title that contains `.env` (e.g. `process.env.FOO`); rephrase the term or title rather than
loosening the patterns. As in technical ADR-0025, these deny entries guard against careless use;
they are not a security boundary.

**Orchestrator-only writes stay a written rule.** `settings.json` is shared by every agent, so a
subagent with `Bash` could technically run the allowlisted commands. The rule "subagents never post;
they return text and the orchestrator posts" stays in every agent file and in
`.claude/rules/github-access.md`.

This extends technical ADR-0024 and technical ADR-0025 rather than rewriting them: their read-only
`gh` baseline is amended only by the three entries above, recorded on their Status lines.

## Consequences

- Sessions without the GitHub MCP server can run the whole pipeline: reads via the already-allowlisted
  `gh` read subcommands, writes via the three pinned entries.
- Widening `gh` writes beyond these three — merging, closing, labelling, editing issues, approving
  reviews — still needs a new decision; this ADR is not a precedent for `gh pr *`.
- The `gh` fallback's review result is a PR comment, not a PR review, so it does not appear in GitHub's
  review panel. This is the cost of keeping approve/request-changes behind a prompt.
- The tool surface is described in one rules file. Agent `tools:` frontmatter still lists the MCP read
  tools (a missing tool is simply unavailable), and agents that have `Bash` use `gh` reads when MCP is
  absent.
- A PreToolUse hook that blocks subagent `gh` writes, and committing a `.mcp.json`, remain possible
  follow-ups; neither is part of this decision.
