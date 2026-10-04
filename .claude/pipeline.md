# Orchestrator pipeline — details

Read on demand when orchestrating an issue through spec → implement → review → QA. `.claude/CLAUDE.md`'s Development Workflow has the summary.

## Fast-path
**The full pipeline below is the default.** A narrow fast-path exception exists for a change that is **both** mechanical **and** confined to a single file (a typo, a string tweak, a *pinned-version* dependency bump, a comment, a doc edit — never a logic change, and never an unpinned/range dependency version): that case **skips step 1 (Spec) only**. Review (step 3) always runs regardless of path — CI passing is not a substitute for a review pass, since e.g. an unpinned new dependency compiles green but is a BLOCKING reviewer finding. QA (step 4) is already on-demand by design (it only runs when the reviewer flags a device item), so the fast-path has nothing extra to skip there. If a change doesn't clearly meet *both* fast-path conditions, run the full pipeline. Don't add explicit "double-check your work" or re-verification steps beyond this — verification lives in the implementer's pre-push gate, CI, and the review round, not in a duplicated pass on top of them.

## Steps in full
1. **Spec** (`spec` agent) — scans product ADRs, batches every clarifying question (≤4) for `AskUserQuestion`, resumes on the human's answers, posts clarifications on the issue; appends a `## Suggested sub-tasks` split when scope spans 3+ shippable layers. Skipped on the fast-path.
2. **Implement** (`implementer` agent) — writes code, runs its pre-push gate (`.claude/agents/implementer.md`'s "Before every push"), pushes a `claude/*` branch, returns the PR title/body as text; the **orchestrator** opens the PR targeting `develop` (pre-authorized — no need to ask). Merging still requires a human.
3. **Review** (`reviewer` agent, read-only — can't post, never compiles) — opens with the acceptance-criteria checklist (its sole ownership), then findings tagged **BLOCKING** / **NON-BLOCKING (SMALL|LARGE)**, plus a "needs a device" list; the orchestrator posts them alongside the CI result (see "Review on push" below). SMALL findings are fixed by default in the same round as BLOCKING ones — no human ask; LARGE findings go to the human first (recommend in-PR fix or a new issue). Self-review must use `event: COMMENT` (APPROVE/REQUEST_CHANGES are blocked for the same account). **Never skipped**, including on the fast-path.
4. **QA** (`qa` agent, read-only) — runs only when the reviewer's "needs a device" list is non-empty and a disposable emulator is available; drives just those items via `run-yapt` and reports pass/not-run. Never re-checks acceptance criteria (the reviewer owns that) and never re-runs the Gradle gate (CI already did). When there's nothing to check on a device, the orchestrator goes straight to human review once step 3 approves.
5. **Update docs** — implementer updates the relevant `.claude/rules/*.md` (`.claude/CLAUDE.md` only for repo-wide rules), `CHANGELOG.md` `[Unreleased]`, and `WhatsNewContent.kt` (append to `WhatsNewContent.unreleased`, never `all` — mirrors `CHANGELOG.md`'s `[Unreleased]`) **in the feature PR, before merge** (`chore:`/docs-only PRs may omit the CHANGELOG + What's New entries).
6. **Merge** — **human only**; Claude never merges.

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
one comment per phase, in order: spec→issue (`add_issue_comment`); the combined review+CI result→PR inline review (`pull_request_review_write` + `add_comment_to_pending_review`); QA→PR, only when QA actually ran; summary→PR.
