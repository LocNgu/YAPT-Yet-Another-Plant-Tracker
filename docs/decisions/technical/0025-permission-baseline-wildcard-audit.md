# Technical ADR-0025: Permission baseline wildcard audit

**Status**: accepted

**Date**: 2026-09-07

## Context

ADR-0024 (#666) moved the Claude permission baseline into the tracked `.claude/settings.json`,
changing entries that used to be one contributor's private convenience into repository policy applied
to every contributor and every automated session after checkout. That PR's round-2 review found one
wildcard admitting a credential-printing flag (`Bash(gh auth status*)` matching `--show-token`) and
fixed it, but auditing the rest of the baseline for the same *class* of problem was out of scope for
#541's mechanical prune and #666's file move, so several entries were deliberately carried over
unchanged (#683).

This ADR extends ADR-0024 rather than editing it: ADR-0024 shipped `accepted` in #666 and is finalized,
and CLAUDE.md permits only a Status-line edit to a finalized ADR. The audit outcome below is recorded
here instead.

Four allowlist entries admit more than their intended read-only or low-risk use:

- `Bash(git branch*)` also matches `-d`/`-D`/`-m`/`-M` (branch deletion and rename).
- `Bash(git stash*)` also matches `drop`/`clear` (stash loss).
- `Bash(find *)` also matches `-exec`/`-execdir`/`-ok`/`-okdir`/`-delete`/`-fprint`/`-fprint0`/
  `-fprintf`/`-fls` (arbitrary command execution, file deletion, or file overwriting via `find`'s
  action primaries) — and, because GNU `find` treats the path argument as optional, in a no-path form
  (`find -delete`) as well as the usual `find . -delete`.
- `Bash(cat*)` also matches any path outside the repository, including plaintext credential files.

Two deny-list gaps were also found: `git push --force-with-lease` against `main`/`develop` is
unmatched by the existing `--force`/`-f` deny pairs, and `git switch main` is an uncovered alternative
to the already-denied `git checkout main`.

## Decision

**`git branch*` is narrowed** to its read-only listing forms (`git branch`, `-a`, `-r`, `-v`, `-vv`,
`--list*`, `--show-current`, `--contains*`), each with its `git -C *` twin. The mutating flags
(`-d`/`-D`/`-m`/`-M`/`--delete*`/`--move*`) are moved to the deny list. Branch listing has a small,
enumerable vocabulary, so narrowing the allow list costs no prompt fatigue here — every legitimate use
is still covered by name.

**`git stash*`, `find *`, and `cat*` are left broad.** For `git stash*`, only `drop`/`clear` are added
to deny; the rest of stash's surface (push, pop, apply, list, show) is fluid, frequent, and
CLAUDE.md's own workflow assumes it stays unprompted. For `find *`, only the action-primary flags are
denied; `find`'s non-action flag combinations vary too much for a narrower allow to track, and a rule
that fires on routine searches gets worked around, which is worse than a documented accepted risk. For
`cat*`, narrowing to a path allowlist is not practical — arbitrary repository files must remain
readable — so the lever here is a deny list of specific known-secret paths instead, addressed below.

Each `find` action primary is denied in **two** forms — `find * -delete*` and `find -delete*` — because
GNU `find` makes the path argument optional and defaults to the current directory. `find -delete` is
therefore a valid, destructive command that matches the broad `find *` allow but *not* a deny pattern
requiring an intervening path token. The pattern is also `-fprint*` rather than `-fprintf*`, so that it
covers `-fprint` and `-fprint0` (both write files) alongside `-fprintf`. A deny entry that never matches
is worse than no entry, because it reads as protection while providing none.

**Deny is the preferred lever throughout**, not just for these two cases: a deny entry beats an allow
entry, and it survives a personal `settings.local.json` re-widening the corresponding allow rule.
Narrowing an allow only helps until someone's local override re-adds it; the deny list is the one place
a widened local override can't reach.

**Deny-list gaps closed**: `git push --force-with-lease origin main|develop` is added, mirroring the
existing `--force`/`-f` pairs' exact shape (bare and `<space>*` forms for the plain command, bare-only
for the `-C *` form). `git switch main` is added, mirroring `checkout main`'s exact scope (bare and
`<space>*` forms, plus the `-C *` twins). `git switch -c newbranch main` is deliberately *not* denied,
being analogous to `checkout -b` rather than to `checkout main`. Note this leaves it at a prompt, not
auto-approved: there is no `git switch` allow entry at all, so every other `switch` invocation already
required confirmation before this change and still does. Only the protected-branch form is now
mechanically blocked rather than merely prompted.

**Secret-path deny for `cat`**, plus `grep` twins for the one path checked most often
(`~/.config/gh/hosts.yml`, which stores the `gh` CLI's OAuth token in plaintext — the same secret #666
closed one path to via `gh auth status --show-token`). Each `cat` pattern is added in both a `~/` form
and a `*/` form, since a command may reach the same file via an absolute path rather than a tilde:
`~/.config/gh/hosts.yml`, `~/.ssh/*`, `~/.aws/*`, `~/.netrc`, `~/.git-credentials`, and `*.env`.

### This is not a security boundary

The `cat`/`grep` denies reduce *accidental* disclosure via the commands people actually reach for; they
do not contain a determined or prompt-injected actor, and they must never be described as closing the
underlying hole. `grep*` remains fully allowed and reaches the exact same bytes the `cat` denies
block — `grep '' ~/.config/gh/hosts.yml` or `grep -r . ~/.ssh/` dump the same secrets a `cat` deny
would refuse. Only the `gh hosts.yml` path gets a `grep` twin, and only its most common invocation
shape (`grep <pattern> <path>`); `grep`'s argument order varies too much (`-r`, `-f`, interleaved
flags) for a literal pattern to cover the general case. Beyond `grep`, other allowed commands still
disclose by different means — `jar tf` for archive contents, `ls` and `git ls-tree` for names — and a
path denylist is incomplete by construction, since the set of paths a credential could live at is
infinite. This audit narrows the easy, careless path to disclosure; it does not, and cannot, eliminate
disclosure as a category.

### `env` and hook commands never go in the tracked file

`.claude/settings.json` also supports an `env` block and hook commands, and that is where this file
type actually leaks secrets in practice — API keys in `env`, tokens embedded in a hook's `curl`. The
file is clean today (only `attribution` and `permissions`), but ADR-0024 established it as "where
Claude configuration lives," which could invite someone to add environment values there by analogy.
The rule going forward: **environment values and any credential-bearing hook command belong only in the
untracked `.claude/settings.local.json`.** The tracked `.claude/settings.json` carries permissions
only.

## Consequences

- Branch listing stays unprompted; branch deletion and rename now require explicit authorization on
  every checkout, closing an accidental-destruction path that existed since #541/#666.
- Stash, find, and cat remain broad allowlist entries by design — narrowing them was considered and
  rejected as counterproductive, not overlooked. The residual risk in each is accepted and documented
  here rather than solved.
- `git push --force-with-lease` and `git switch` against protected branches are now denied to the same
  degree `--force`/`-f` and `checkout` already were. The one acknowledged gap: the attached
  `--force-with-lease=refs/heads/main:<sha>` form won't match a literal deny pattern; nothing in the
  documented workflow emits that syntax, so it is noted rather than fixed.
- The `cat`/`grep` secret-path denies are explicitly framed as reducing accidental disclosure, not as a
  security boundary — future readers of the permission baseline should not treat this list as
  exhaustive or as closing off credential exposure as a risk.
- Any future addition of an `env` block or a hook command to `.claude/settings.json` should be treated
  as a policy violation of this ADR, not a stylistic choice — it belongs in `settings.local.json`
  instead.
- The companion CODEOWNERS work for `.claude/` and `.github/workflows/` (making future baseline
  changes require owner review) was split out to its own issue and is not part of this decision.
