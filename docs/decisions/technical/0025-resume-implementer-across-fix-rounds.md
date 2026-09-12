# Technical ADR-0025: Resume the same implementer agent across a PR's fix rounds

**Status**: accepted

**Date**: 2026-09-07

## Context

Issue #684 tracked down what actually drove disproportionate token spend on #679/#682, a moderate-sized
three-bug fix that needed several review rounds. The single largest contributor was structural, not
incidental: each of the three implementer dispatches for that PR started as a fresh `Agent` call, so
each one re-read `.claude/CLAUDE.md`, every relevant `.claude/rules/*.md`, and every source file it
needed to touch — from scratch — before writing a single line of the fix. On a PR needing multiple
rounds, that re-derivation is paid again every round for context the first dispatch already built.

The alternative — having the orchestrator make small in-place edits itself instead of dispatching an
implementer at all for a follow-up fix — was considered and rejected for this issue's scope: it would
quietly reintroduce the class of problem the implementer/reviewer split exists to prevent (an
unreviewed, ungrounded edit landing on a PR), rather than just cutting redundant re-reads.

## Decision

When the reviewer requests a fix-round on a PR already in flight, the orchestrator resumes the *same*
implementer agent instance for that round — sending it a follow-up message referencing the agent
name/id from the `Agent` call that opened the PR — rather than launching a fresh `Agent` call. The
resumed agent already has CLAUDE.md, the rules docs, and the touched source files in its own context
from the round that opened the PR, so a fix round only needs to carry the reviewer's new findings, not
re-derive the whole picture.

This only works within one continuous orchestrator session, since resuming an agent depends on the
orchestrator's own tool-call history to reference back to. If the orchestrator session itself is
restarted or compacted between rounds, that history is gone and a fresh `Agent` dispatch for the next
round is unavoidable — that's an accepted fallback, not a failure of this rule.

This decision is scoped to the *implementer* only. The reviewer's "each round is a fresh, standalone
review" posture (`.claude/CLAUDE.md` step 3, `.claude/agents/reviewer.md`) is unchanged — a fresh
reviewer pass per round is deliberate (it re-verifies against the current diff rather than trusting its
own prior judgment), and resuming the implementer does not imply resuming the reviewer too.

## Consequences

- Multi-round PRs on the same branch no longer pay a full cold-context re-read per fix round; the
  token cost of a round scales roughly with the size of the reviewer's findings, not with the size of
  the whole feature again.
- The orchestrator must track which implementer agent instance opened a given PR for the lifetime of
  the orchestrator session, so it can address a follow-up message to it.
- A restarted or compacted orchestrator session loses that binding and falls back to a fresh implementer
  dispatch for the next round — accepted as the cost of the orchestrator's own session boundary, not
  something this ADR tries to work around.
- Review remains fully independent of implementation history per round — this ADR does not touch that.
