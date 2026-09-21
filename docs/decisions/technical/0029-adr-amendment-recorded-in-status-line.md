# Technical ADR-0029: An ADR amendment is recorded on the amended ADR's Status line, never as a Consequences note

**Status**: accepted

**Date**: 2026-09-21

## Context

CLAUDE.md's "Architecture Decision Records" section had an internal contradiction. It stated that "the
only permitted edits to a finalized ADR are its Status line → `superseded by [ADR-XXXX](file.md)`, and
**non-substantive corrections**" — an amendment note was not on that list — while, one sentence later,
giving citation-resolution guidance for exactly such a note: "an 'Amended by ADR-XXXX' line in a
`technical/` ADR means the technical one." The rule forbade the very line it told a reader how to read.

That contradiction was not hypothetical. Three different conventions for recording an amendment had
already accumulated across the tree, each looking equally "correct" against some part of the rule:

- **A one-line note under Consequences.** Technical ADR-0018 (Edit button scroll fade → ADR-0022),
  product ADR-0029 and product ADR-0030 (visibility gate → ADR-0031) all did this. This is the pattern
  the "Amended by ADR-XXXX" citation guidance was implicitly written for.
- **A dedicated back-reference left off entirely**, "per CLAUDE.md's rule that the only permitted edit
  to a finalized ADR is its Status line." Product ADR-0040's own Consequences section says exactly this
  about product ADR-0031, and product ADR-0031 itself carries no back-reference to ADR-0040 as a result.
- **A Status-line amendment clause**, `accepted — <clause> amended by [ADR-NNNN](file.md)`. Technical
  ADR-0021 (whole-day-granularity clause amended by ADR-0027) already used exactly this form, ahead of
  any rule sanctioning it.

Issue #747 spent two review rounds oscillating between the first and second conventions on the same PR
because the written rule did not clearly permit either one. That cost is the immediate trigger for
resolving this now rather than letting a fourth convention accumulate on the next PR that touches an
amended ADR.

## Decision

An ADR amendment is recorded **only** on the amended ADR's own Status line, in the form:

    **Status**: accepted — <clause description> amended by [ADR-NNNN](nnnn-slug.md)

mirroring the existing supersession form (`superseded by [ADR-XXXX](file.md)`) and matching technical
ADR-0021's own line, which already used this shape before this ADR made it a rule. Where an ADR needs to
record more than one relationship (a supersession and an amendment, or more than one amendment), they
are combined in the same Status line — product ADR-0016's multi-clause, comma-separated Status line is
the in-repo precedent for that shape, and the migrations this ADR lands alongside follow it: product
ADR-0029 chains its ADR-0030 supersession with its ADR-0031 amendment, and product ADR-0030 folds the
ADR-0031 amendment into a Status line that already recorded two partial supersessions.

A Consequences-section note recording the same fact is removed once its Status-line equivalent exists —
the two must not both exist, or a future edit to one silently leaves the other stale. This resolves the
contradiction by choosing the Status line as the one legal location and treating the "Amended by
ADR-XXXX under Consequences" convention as retired, not as a second accepted form.

This choice keeps three properties that mattered when weighing it against the alternative of instead
loosening the rule to explicitly permit a Consequences-section note:

- **The "prose otherwise stays untouched" invariant stays literally true**, not just true in spirit. The
  Status line was already understood to be metadata rather than the ADR's substantive record (Context/
  Decision/Consequences); recording an amendment there doesn't touch Consequences prose at all, so the
  invariant needs no exception carved into it — it simply continues to describe a boundary the rule
  never crosses.
- **No new editable surface opens up on a finalized ADR.** Permitting "add one line to Consequences for
  an amendment" is a materially larger permission than "edit one line of metadata" — it invites scope
  creep into what counts as a small enough Consequences addition to allow, exactly the kind of judgment
  call a hard-crash-style rule (Room migrations' `fallbackToDestructiveMigration` posture, technical
  ADR-0002) is meant to remove.
- **Back-references stay discoverable from the direction a reader actually arrives from.** CLAUDE.md's
  own guidance is "consult the relevant ADR before working in a covered area" — a reader opens the
  *older*, amended ADR first, precisely because that's the one describing the pattern they're about to
  touch. A Status line is the first thing on the page; a back-reference buried in a Consequences bullet
  several paragraphs down is easy to miss and easy to forget to add (as product ADR-0040 explicitly did,
  citing the very contradiction this ADR resolves, as its stated reason for omitting one).

## Consequences

- CLAUDE.md's ADR section is amended in the same PR that introduces this ADR: the permitted-edits clause
  now explicitly includes the amendment form on the Status line, and the citation-resolution example is
  re-anchored to a Status-line amendment note instead of a Consequences-section one.
- Three existing ADRs are migrated in the same PR: technical ADR-0018 (Edit button scroll fade →
  ADR-0022), product ADR-0029 and product ADR-0030 (due-status visibility gate → ADR-0031) each lose
  their Consequences-section "Amended by ADR-XXXX" note and gain the equivalent Status-line clause.
  Product ADR-0031 gains a new Status-line back-reference to product ADR-0040 that did not exist before
  under any convention (ADR-0040 deliberately omitted one, per the old rule's own contradiction).
- **Technical ADR-0022, product ADR-0031, and product ADR-0040 are deliberately left byte-for-byte
  unchanged by this migration**, even though their own Consequences prose now describes an arrangement
  that is no longer true — ADR-0022 and ADR-0031 each say the ADR they amend "now carries a one-line
  'Amended by ADR-XXXX' note under its own Consequences section" (no longer the case after this PR's
  migration), and ADR-0040 says no back-reference is added to ADR-0031 "per CLAUDE.md's rule" (a
  back-reference is added, by this same PR). This is intentional, not an oversight: those sentences are
  accurate **historical records of what those PRs actually did** at the time they merged, under the
  convention that existed then. Editing them to match the new convention would itself be the forbidden
  kind of retroactive Consequences-prose edit — describing a later revert as if it were the original
  fact is exactly what this project's ADR-editing discipline exists to prevent. A reader who lands on
  ADR-0022, ADR-0031, or ADR-0040 first and follows its stated back-reference description will find it
  stale relative to the amended ADR's current Status line; the amended ADR's Status line is the
  authoritative, current statement of the relationship, not the older ADR's prose.
- `docs/decisions/template.md` gains no new field by this ADR — it still has no `**Amends**:` or
  `**Supersedes**:` header slot (product/technical ADRs that use those headers, e.g. product ADR-0031
  and product ADR-0040, added them ad hoc). Adding first-class template fields for both relationships,
  and any CI enforcement that a Status-line amendment/supersession claim on one ADR is symmetric with a
  matching claim on the other side, are acknowledged, deliberately deferred follow-ups — out of scope
  for this ADR, which only settles where the fact is recorded once someone chooses to record it.
