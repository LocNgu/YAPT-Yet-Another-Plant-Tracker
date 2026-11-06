# Technical ADR-0043: Hard sub-task split gate and bounded agent context for the orchestrator pipeline

**Status**: accepted

**Date**: 2026-11-06

## Context

Issue #854 followed up on #836 / PR #837 (phase 4 of #810). The spec agent correctly proposed four
independently shippable sub-tasks, but a generic "go" was read as approval to build the whole parent
issue in one PR. That PR spanned aggregation, navigation/UI, single and bulk actions, a developer
experiment, ADR/docs, unit and Compose tests, CI and device QA. It exhausted a five-hour usage window,
repeatedly hit subagent limits, needed two substantial correction rounds, and had to be reconciled with
#834's overlapping #550 work that merged while it was open.

Two earlier decisions already targeted this cost and are kept, not replaced:

- **#299** introduced the spec agent's `## Suggested sub-tasks` proposal for large issues. It was
  advisory only, so it was easy to bypass with a generic approval.
- **#684 / technical ADR-0028** made fix rounds resume the same implementer instead of redispatching a
  cold one, and added the annotation-first CI-log guidance in `.claude/rules/ci-build.md`. It did not
  address context accumulated by the orchestrator itself and replayed into every fresh agent.

Alternatives considered and rejected:

- **A fixed token budget per issue.** Rejected — issue sizes vary too much, and a hard cap would stop
  work at an arbitrary, possibly unstable point instead of a pushed SHA.
- **Skip review or QA on large PRs to save usage.** Rejected — the quality gates are not the problem
  (#854 non-goals); applying them to an epic-sized PR while replaying its whole transcript is.
- **Drop multi-agent orchestration.** Rejected — the spec/implementer/reviewer split is what keeps
  review independent (technical ADR-0032).

## Decision

1. **The split is a hard gate.** When the spec emits `## Suggested sub-tasks`, the orchestrator presents
   the split and asks the human to approve or adjust it. Generic approval ("go", "continue", "implement
   it", "lgtm") never authorizes building the parent issue as one PR. The default is one child issue per
   sub-task, processed sequentially. A single-PR override must be explicit and acknowledge the larger
   review/usage cost. The decision (split or override) is recorded on the parent issue, and the
   implementer refuses to start the parent until it finds that record.
2. **Subagents get bounded context by default.** Each dispatch carries exact references (issue, PR,
   branch, head SHA) plus only the findings relevant to its round — not the accumulated orchestration
   transcript. Reviewers stay fresh each round (technical ADR-0032); QA gets the reviewer's device items
   verbatim. Fix rounds still resume the same implementer (technical ADR-0028). Full-history inheritance
   is justified only when the task depends on a human decision or reasoning that exists nowhere but the
   conversation and cannot be summarized into the prompt. Completed agents' full output is not re-injected
   when only their status is needed.
3. **Freshness and overlap checkpoints.** Before implementation starts, and again before opening or
   reviewing a PR, fetch `origin/develop` and check open issues/PRs and recent base commits for
   overlapping files or scope; sequence or reconcile overlaps before a long implementation. Before
   launching a fresh reviewer, re-check that the PR is mergeable.
4. **Context-efficient CI observation.** Poll compact status snapshots with backoff, never streamed
   `--watch` output; on failure read annotations or test-report artifacts first and filter logs locally
   (`.claude/rules/ci-build.md`). Unchanged polling output is not narrated.
5. **QA is device-exclusive.** It covers only what CI and instrumented tests cannot establish, does not
   repeat flows a green instrumented test already covers unless the reviewer names a real-device risk,
   and reports a PASS / FAIL / NOT-RUN matrix.
6. **Usage checkpoint.** Before an additional broad fix/review/QA phase on unusually large work, the
   orchestrator checks usage when the host exposes it and summarizes completed work, remaining risk and
   the cheapest safe continuation. When the window is nearly exhausted, or a `usage_limit_exceeded`
   result repeats, it stops at a stable pushed SHA and hands off instead of redispatching unchanged work.

The details live in `.claude/pipeline.md` and the agent definitions in `.claude/agents/`.

## Consequences

- An epic takes more human touchpoints (approving the split, one PR per child), in exchange for smaller
  reviews, fewer correction rounds and less exposure to base drift.
- Agents must be briefed with explicit references, so the orchestrator does slightly more writing per
  dispatch; a brief that omits something an agent needs costs a round-trip.
- Existing gates are unchanged: issue-first, ADR requirements, the implementer's pre-push Gradle gate,
  CI, an independent review on every PR, and human-only merge.
- #299's advisory split and technical ADR-0028's resumed implementer stay in force; this ADR tightens the
  former into a gate and extends the latter's context discipline to the orchestrator.
