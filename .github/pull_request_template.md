## Summary

<!-- One or two sentences: what does this PR do and why? -->

## Linked issue

<!-- Every feature/bug-fix PR needs an issue (issue-first rule). Use "Closes #123". -->
<!-- For release-prep or process/docs PRs with no issue, write "None (docs/release)". -->

Closes #

## Changes

<!-- Bullet list of the notable changes. Keep it to what a reviewer needs. -->

-

## Testing

<!-- How was this verified? New/updated unit tests, Compose tests, manual steps on device/emulator. -->
<!-- Note: cloud sessions can't build (#419) — say so if CI is the only verification. -->

-

## Checklist

- [ ] `CHANGELOG.md` `[Unreleased]` updated (not required for `chore:`/docs-only PRs)
- [ ] `WhatsNewContent.kt` `unreleased` (not `all`) appended to if there's a user-visible change (not required for `chore:`/docs-only PRs)
- [ ] Relevant `.claude/rules/*.md` updated if a feature's behaviour/internals changed; `.claude/CLAUDE.md` only for repo-wide rules (kept to one or two lines each)
- [ ] All new UI strings live in `strings.xml` (no hardcoded strings in Compose)
- [ ] Room schema JSON committed under `app/schemas/` and a `Migration` added if the DB version was bumped
- [ ] New/changed behaviour covered by tests, or explained above why not
- [ ] Relevant ADRs in `docs/decisions/` consulted; new/superseding ADR added if a decision changed
