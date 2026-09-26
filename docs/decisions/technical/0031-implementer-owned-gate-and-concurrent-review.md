# Technical ADR-0031: The implementer owns the pre-push gate; review runs concurrently with CI, joined by head SHA

**Status**: accepted

**Date**: 2026-09-26

## Context

Issue #807 found that the implementer → push → reviewer → QA pipeline (`.claude/CLAUDE.md`'s
Development Workflow, building on technical ADR-0028's resumed-implementer fix rounds) still carried
avoidable wall-clock waiting and redundant verification: the implementer never compiled or ran tests
before pushing, so a compile or Detekt miss cost a full CI run and a fresh reviewer; the reviewer waited
for green CI and then compiled the code itself, duplicating work `.claude/rules/ci-build.md`'s gate
already required; QA's acceptance-criteria pass largely repeated the reviewer's own first checklist item;
and every SMALL, low-risk non-blocking finding still round-tripped through the human before it could be
fixed.

Alternatives considered and rejected:

- **Keep the reviewer compiling and let it wait for green CI**, only fixing the "implementer never
  verifies" gap. Rejected — it leaves the double-verification (implementer's own future gate plus the
  reviewer's compile) and the CI wait time both in place; the two findings are coupled; fixing one
  without the other keeps most of the cost.
- **Have the orchestrator fix SMALL findings itself, in place of the implementer.** Rejected on technical
  ADR-0028's own grounds: an edit landing on a PR without going through the implementer/reviewer split
  reintroduces the class of problem that split exists to prevent, even for a small, low-risk fix.
- **Keep waiting for green CI before the reviewer runs at all**, only removing its own compile step.
  Rejected — it still serializes two independently runnable steps (static review and CI) that a
  low-risk, well-formed compile issue rarely correlates strongly enough with a review verdict to justify
  chaining them.
- **Delete QA entirely.** Rejected — a genuine class of acceptance criteria (visual layout, animation,
  haptics, emulator-only flows) cannot be verified by reading code or by CI, and removing QA would leave
  those criteria with no verification path at all rather than a conditional one.

## Decision

- **The implementer owns the pre-push gate.** It runs the existing compile/lint/Detekt/test gate
  (`.claude/rules/ci-build.md`, matching `AGENTS.md` byte-for-byte) before every push that opens or
  updates a PR, fix rounds included. The reviewer no longer compiles or runs the gate at all; it may run
  one narrow, targeted test only to confirm or refute a specific suspected bug, never as a general
  verification step.
- **The reviewer owns the acceptance-criteria check.** Its review body opens with that checklist,
  verified against the issue and its spec clarifications; no later step re-derives it.
- **Review and CI run concurrently, joined by head SHA.** The reviewer launches as soon as the PR opens,
  given the exact head commit to review, rather than waiting for CI to go green first. The orchestrator
  combines the two results only once both belong to the same SHA, re-confirming the PR's head immediately
  before posting and discarding a stale pairing if the head moved in the meantime.
- **SMALL non-blocking findings are fixed by default**, in the same combined commit as any BLOCKING
  findings and any PR-caused red CI, without a human round-trip. A finding only counts as SMALL when it
  is localized, involves no product/UX decision, and touches no architecture or data-contract surface
  (migrations, permissions, scheduling, persisted data); anything else is LARGE and still goes to the
  human. A second review round is not automatic once a SMALL-only fix lands — it runs again only when the
  fix round itself introduces a new BLOCKING condition (a fresh BLOCKING finding, PR-caused red CI, a
  weakened or removed test, or a fix that reached outside what the findings named).
- **Device-dependent QA is conditional, not a standing pipeline step.** It runs only when the reviewer's
  review names specific acceptance criteria that reading code and tests cannot verify, and only against a
  disposable device explicitly designated for that purpose — never the machine's own default emulator.
  When no such device is available, the check is reported as not run, not failed, and those items become
  a pre-merge list for the human.
- The CI-graph parallelization that removes the reviewer's own wait time on the CI side is deliberately
  a separate, executable change (tracked as its own PR against `.github/workflows/android.yml`) — this
  ADR only records the agent-facing contract change; it does not itself alter the CI job graph.

This builds on technical ADR-0028's resumed-implementer pattern and does not edit it: the same fix round
that now covers CI/BLOCKING/SMALL findings together still resumes the one implementer agent instance
that opened the PR, and the reviewer's "fresh, standalone review per round" posture is unchanged.

## Consequences

- A compile, lint, Detekt, or test failure is caught before a PR ever opens, rather than surfacing for
  the first time in CI or in a reviewer's own build.
- The reviewer's job narrows to static review, acceptance-criteria verification, and (at most) one
  targeted confirmation test — it is no longer a second place the full gate runs.
- The orchestrator takes on the responsibility of correlating a review result and a CI result to the same
  head SHA before treating either as final; a mismatch (a new push landing mid-review) must restart the
  review rather than silently pairing stale data with a fresh one.
- Most PRs with only small, low-risk findings clear in one review round instead of two, since a SMALL fix
  no longer waits on a second full reviewer pass by default.
- QA stops being a default pipeline step for every PR and becomes an on-demand check gated on the
  reviewer explicitly flagging something a device is needed for — a PR with no such finding never invokes
  it at all.
