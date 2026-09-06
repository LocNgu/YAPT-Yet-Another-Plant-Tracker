# Technical ADR-0024: Shared Claude permissions use project settings

**Status**: accepted

**Date**: 2026-09-05

## Context

The repository's Claude permission policy had accumulated in a personal
`.claude/settings.local.json`. That file contained machine-specific paths, debugging-session leftovers,
and broad GitHub CLI patterns, but it was globally ignored and had never been committed. As a result,
the safety rules described in `.claude/CLAUDE.md` were neither reviewable nor consistently applied to
other contributors and sessions.

Committing `settings.local.json` was rejected because Claude Code reserves that file for personal
project overrides, which can legitimately contain machine-specific paths. Leaving the policy entirely
local was also rejected because repository-wide workflow and safety rules need a reviewable source of
truth.

## Decision

The repository-wide Claude permission baseline lives in the tracked `.claude/settings.json`. The
untracked `.claude/settings.local.json` remains available only for personal or machine-specific
overrides.

The shared allowlist contains:

- repository Git operations already authorized by the documented Claude workflow;
- read-only GitHub CLI subcommands for inspecting issues, pull requests, workflow runs, authentication,
  and search results;
- the Gradle wrapper and read-only local inspection commands used by routine development.

GitHub CLI write-capable wildcards such as `gh pr *`, unrestricted `gh api`, interpreter execution,
APK installation, archive extraction, and machine-specific executable or directory paths are not part
of the shared allowlist. When needed, those actions continue through the normal permission prompt or a
purpose-built integration with explicit authorization.

The existing Git deny list is copied without modification. It remains the mechanical backstop for the
protected branches and destructive reset operations described in `.claude/CLAUDE.md`.

## Consequences

- Contributors and automated sessions receive the same reviewable baseline after checkout.
- GitHub writes, including PR creation, editing, review, closing, and merging, are never implicitly
  approved by the shared configuration.
- Local paths such as SDK, Gradle cache, or package-manager locations stay private and portable across
  macOS, Linux, and cloud sessions.
- Personal settings can add local conveniences, but they are not treated as repository policy or
  included in pull requests.
