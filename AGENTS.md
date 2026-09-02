# Working in YAPT with Codex

## Source guidance

Read [`.claude/CLAUDE.md`](.claude/CLAUDE.md) before making a change. It is the
authoritative guide to YAPT's product behaviour, architecture, engineering
conventions, and ADR process. Load the applicable file under `.claude/rules/`
before touching a covered area.

Treat the checked-in build files, `CHANGELOG.md`, ADRs, and current GitHub
issues as the source of truth when they conflict with dated details in
`CLAUDE.md`. Do not copy those documents' Claude-specific agent, model, branch,
or tool instructions into Codex workflows.

## Git workflow

- Make each change on its own `codex/<kebab-description>` branch.
- Start from a freshly fetched `origin/develop`; target `develop` in any pull
  request.
- Do not mix unrelated changes in one branch or pull request.
- Never merge a pull request. A human performs merges.
- Preserve existing user changes and untracked files unless the user explicitly
  asks to change or remove them.

## Authorization and collaboration

- Local implementation, focused tests, and static analysis are normal parts of
  an authorized coding task.
- Ask for explicit authorization before externally creating or modifying GitHub
  issues, pull requests, reviews, comments, labels, or branches, and before
  pushing commits.
- Use GitHub context to inspect existing issues and pull requests when it helps
  scope a request, but do not assume an issue must be created before starting
  user-authorized local work.

## Engineering guardrails

- Follow relevant ADRs. If a requested change contradicts one, explain the
  conflict and get confirmation before proceeding; create or supersede ADRs for
  significant decisions.
- Keep Room migrations explicit and commit the exported schema for every
  database version change.
- Keep UI strings in Android resources, use lifecycle-aware StateFlow
  collection, and test observable semantics rather than Compose tree structure.
- Verify changes with the smallest relevant Gradle checks from
  `.claude/CLAUDE.md` and `.claude/rules/ci-build.md`; use the build files for
  current versions and task names.
