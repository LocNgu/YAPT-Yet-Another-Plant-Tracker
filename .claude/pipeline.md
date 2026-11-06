# Orchestrator pipeline — details

Read on demand when orchestrating an issue through spec → implement → review → QA. `.claude/CLAUDE.md`'s Development Workflow has the summary.

## Fast-path
**The full pipeline below is the default.** A narrow fast-path exception exists for a change that is **both** mechanical **and** confined to a single file (a typo, a string tweak, a *pinned-version* dependency bump, a comment, a doc edit — never a logic change, and never an unpinned/range dependency version): that case **skips step 1 (Spec) only**. Review (step 3) always runs regardless of path — CI passing is not a substitute for a review pass, since e.g. an unpinned new dependency compiles green but is a BLOCKING reviewer finding. QA (step 4) is already on-demand by design (it only runs when the reviewer flags a device item), so the fast-path has nothing extra to skip there. If a change doesn't clearly meet *both* fast-path conditions, run the full pipeline. Don't add explicit "double-check your work" or re-verification steps beyond this — verification lives in the implementer's pre-push gate, CI, and the review round, not in a duplicated pass on top of them.

## Steps in full
1. **Spec** (`spec` agent) — scans product ADRs, batches every clarifying question (≤4) for `AskUserQuestion`, resumes on the human's answers, posts clarifications on the issue; appends a `## Suggested sub-tasks` split when scope spans 3+ shippable layers — that split is a **hard gate** (see "Sub-task split gate" below). Skipped on the fast-path.
2. **Implement** (`implementer` agent) — writes code, runs its pre-push gate (`.claude/agents/implementer.md`'s "Before every push"), pushes a `claude/*` branch, returns the PR title/body as text; the **orchestrator** opens the PR targeting `develop` (pre-authorized — no need to ask). Merging still requires a human.
3. **Review** (`reviewer` agent, read-only — can't post, never compiles) — opens with the acceptance-criteria checklist (its sole ownership), then findings tagged **BLOCKING** / **NON-BLOCKING (SMALL|LARGE)**, plus a "needs a device" list; the orchestrator posts them alongside the CI result (see "Review on push" below). SMALL findings are fixed by default in the same round as BLOCKING ones — no human ask; LARGE findings go to the human first (recommend in-PR fix or a new issue). Self-review must use `event: COMMENT` (APPROVE/REQUEST_CHANGES are blocked for the same account). **Never skipped**, including on the fast-path.
4. **QA** (`qa` agent, read-only) — runs only when the reviewer's "needs a device" list is non-empty and a disposable emulator is available; drives just those device-exclusive items via `run-yapt` and reports a PASS / FAIL / NOT-RUN matrix (see "QA scope" below). Never re-checks acceptance criteria (the reviewer owns that) and never re-runs the Gradle gate (CI already did). When there's nothing to check on a device, the orchestrator goes straight to human review once step 3 approves.
5. **Update docs** — implementer updates the relevant `.claude/rules/*.md` (`.claude/CLAUDE.md` only for repo-wide rules), `CHANGELOG.md` `[Unreleased]`, and `WhatsNewContent.kt` (append to `WhatsNewContent.unreleased`, never `all` — one short plain-language bullet per change, not a copy of the `CHANGELOG.md` bullet; writing rule and CI length cap in `.claude/rules/whats-new.md`) **in the feature PR, before merge** (`chore:`/docs-only PRs may omit the CHANGELOG + What's New entries).
6. **Merge** — **human only**; Claude never merges.

## Sub-task split gate (#299, #854, technical ADR-0043)
When the spec's output contains `## Suggested sub-tasks`, implementation of the parent issue is **blocked** until the human records a split decision:
- Present the proposed split and ask the human (`AskUserQuestion`) to **approve or adjust the split** or to **explicitly override it with a single PR**. The override option must state the cost: one larger review, more fix rounds, more usage, more exposure to base drift.
- **Generic approval is not a decision.** "go", "continue", "implement it", "lgtm", or approving the spec comment does not authorize building the parent as one PR — ask the split question anyway.
- **Default: split.** Create one child issue per sub-task (`mcp__github__issue_write` `create` with `parent_issue_number`), post the decision on the parent issue, and process the children **sequentially** — each child runs the full pipeline and merges before the next starts, branching from the freshly fetched `develop` that contains it.
- **Override:** post the human's explicit single-PR override on the parent issue (`## Split decision: single PR (override)` plus the human's words), then dispatch the implementer for the parent.
- Pass the recorded decision to the implementer in its dispatch (`split: children #a, #b, …` or `split: override`); the implementer refuses to start a parent issue whose spec proposed sub-tasks without one.

## Bounded agent context (#854, technical ADR-0043)
Every subagent dispatch is **bounded by default** — a brief with exact references, not the accumulated orchestration transcript:
- **Always pass:** issue number, PR number, branch, head SHA, the round number, and only the findings relevant to this round. Agents fetch the issue, spec comment and diff themselves.
- **Prefer a fresh agent over a fork** for reviewer and QA dispatches; if a fork is the only option, use a minimal / recent-turn one.
- **Reviewer:** issue (its acceptance criteria and spec clarifications), PR, head SHA, round, and — in round 2 — the round-1 findings with the implementer's fixed/declined status. Never the full transcript, raw CI logs, or earlier agents' full output.
- **QA:** PR, head SHA and the reviewer's "needs a device" items verbatim — nothing else.
- **Implementer fix rounds:** resume the same instance (technical ADR-0028) and send only the new findings + CI failure summary.
- **Full-history inheritance is justified only** when the task depends on a human decision or reasoning that exists nowhere but this conversation and cannot be summarized into the brief. Say why in the dispatch when you use it.
- When you only need an agent's status, keep its `NEXT:` line and verdict; don't re-quote its full output into later turns or briefs.

## Freshness and overlap checkpoints (#854)
- **Before implementation:** `git fetch origin develop`; check open PRs, open `in-progress` issues and the last few `develop` commits for overlapping files or acceptance scope. If an overlap exists, sequence the work (wait for the other PR) or reconcile scope on the issue before a long implementation starts.
- **Before opening the PR and before each fresh reviewer:** re-fetch `develop`, re-check the overlap, and confirm the PR is mergeable (`pull_request_read` `get` → `mergeable`). Never review a head that has conflicts or can't produce a current merge ref — get the implementer to rebase/merge `develop` first.

## CI observation (#684, #854)
- Poll compact status snapshots (`pull_request_read` `get_check_runs`/`get_status`, or `actions_list` `list_workflow_runs` for the branch) with **backoff** — roughly 2, 4, 8 minutes, then every 10 — never a streamed `--watch`.
- Don't narrate or keep unchanged polling output; report only a state change.
- On failure, follow `.claude/rules/ci-build.md`'s "Diagnosing a failed CI check": annotations first, then test-report artifacts, then a saved log filtered locally for the failing test names and stack traces. Never paste a raw log into the transcript or an agent brief — pass the filtered excerpt.

## QA scope (#854)
- QA runs only the reviewer's items that CI and instrumented tests **cannot** establish (visual layout, animation, haptics, emulator-only flows).
- Don't repeat a flow a green instrumented test already covers unless the reviewer names a specific real-device risk.
- Disposable emulator data only — never expand QA by mutating a personal device fixture (`.claude/agents/qa.md`).
- Result: a short PASS / FAIL / NOT-RUN matrix, one row per item.

## Usage checkpoint (#854)
Before launching an **additional** broad fix, review or QA phase on unusually large work (more than one fix round, or a PR spanning several layers):
- Check current usage when the host exposes it.
- Post a short checkpoint on the PR: completed work, remaining risk, and the cheapest safe continuation.
- If the usage window is nearly exhausted, **stop at a stable pushed SHA** and hand off to the human instead of starting an agent that will hit the limit.
- A repeated `usage_limit_exceeded` result is a workflow signal: don't redispatch unchanged work — checkpoint and stop.
- Elapsed Gradle/CI wall-clock time is not model usage; waiting on CI is not a reason to stop.

## Review on push
the reviewer launches as soon as the PR opens, given the exact head SHA, running **in parallel with CI** rather than waiting for it green. The orchestrator posts once both that SHA's review and its CI conclusion are in — re-reading the PR's current head immediately before posting, and discarding a stale review/CI pairing if the head moved on in the meantime (restart review against the new head instead). The posted CI line reads `CI: ✓ green for <sha>`, `CI: — Android jobs skipped (docs-only) for <sha>`, or `CI: ✗ <job> failed for <sha>` — a skipped job set is never described as green. A PR-caused red CI counts as a BLOCKING finding. One combined fix round then goes to the resumed implementer, covering CI failures + BLOCKING + SMALL findings together. Round 2 runs only when round 1 had a BLOCKING finding, the fix left CI red for a reason this PR caused, the fix commit weakens or removes a test/assertion, or it touches files or behavior outside what the SMALL findings named (the orchestrator checks the last two from the fix commit's diff) — the cap stays at 2 regardless.

## Resuming the implementer across fix rounds (#684, technical ADR-0028)
when a fix round is needed on a PR
already in flight, resume the *same* implementer agent instance (send a follow-up message to its
agent name/id from the earlier `Agent` call) rather than launching a fresh `Agent` call. A fresh dispatch
re-reads CLAUDE.md, the relevant rules docs, and every touched source file from scratch — that's most of
the token cost on a multi-round PR, and the resumed agent already has all of it in context from round 1.
Caveat: this only works within the same orchestrator session, since a resumed agent needs the
orchestrator's own tool-call history to reference back to — if the orchestrator session itself gets
restarted or compacted, a fresh dispatch is unavoidable and that's fine. This is about resuming the
*implementer* only — the reviewer's "each round is a fresh, standalone review" posture (step 3) is
unchanged; a resumed implementer does not mean a resumed reviewer.

## Comment cadence
one comment per phase, in order: spec→issue (`add_issue_comment`); the combined review+CI result→PR inline review (`pull_request_review_write` + `add_comment_to_pending_review`); QA→PR, only when QA actually ran; summary→PR. Without the GitHub MCP tools, post the same comments through the `gh` fallback in `rules/github-access.md` (the review+CI result becomes a `gh pr comment`); if neither path works, tell the human — never skip a comment.
