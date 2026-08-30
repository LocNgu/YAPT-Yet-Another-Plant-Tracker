# ADR-0022: Edit button fades on scroll; watering/fertilize action rows revert to plain margins

**Status**: accepted

**Date**: 2026-08-30

**Amends**: ADR-0018 (documents the Back/Edit overlay buttons as persistently pinned and interactive
throughout scrolling). Not superseded — the Box-overlay layout itself, and Back's/the FAB's persistent
pinning, are unchanged; only Edit's persistence changes.

## Context

PR #604 gave `WateringDueActionsRow`/`FertilizeDueActionRow` (`WateringDueActions.kt`) a `64.dp` leading
/ `88.dp` trailing horizontal inset (`152.dp` total), in place of the `16.dp` every other card on Plant
Detail uses. The reason was real: in the tabs layout these rows are the first item under their tab, so
they can scroll to sit flush against either edge of the screen's own scrollable viewport — exactly where
the *permanently pinned* Back icon button (top-left), Edit icon button (top-right), and "Log care" FAB
(bottom-right) live, overlaid on top of the scrolling `LazyColumn` in z-order (Box overlay, not Scaffold
— technical ADR-0018). Those pinned buttons draw on top and win any touch landing in their bounds; a
button hugging the row's own `16.dp` edge would have its clickable bounds fall inside theirs.

Issue #610 reported that the fix, while functionally correct, was visually broken: on a ~360dp-wide
screen, `152.dp` of combined inset left only ~208dp for the row's own content — large, obviously
inconsistent gaps on both sides versus every sibling card (interval settings, insights, etc.), sized to
the *worst-case* danger and paid at *every* scroll position, not just the ones actually flush against a
pinned corner.

Alternatives considered:

- **Keep the wide insets, just narrow them.** Still pays a permanent visual cost for a risk that exists
  only transiently, and any smaller margin has to be re-justified against both pinned buttons' full
  touch-target reach — the underlying problem (permanently-interactive pinned overlay buttons) is
  unaddressed.
- **Fade both Back and Edit on scroll, matching a conventional collapsing-header pattern.** Removes the
  risk from both leading and trailing pinned icon buttons, but takes away Back's icon entirely — a
  higher-cost regression than Edit's, since Edit has no equivalent (gesture/system back always works
  regardless of what's on screen; there is no equivalent fallback for Edit).
- **Fade only the Edit button once the hero has scrolled substantially out of view; leave Back and the
  FAB pinned as before; revert the rows to plain `16.dp` margins.** Removes the one collision risk that
  a persistently-interactive pinned button actually created (Edit, opposite the rows' off-center
  Reschedule/Fertilize controls), at the cost of a real but narrow functional regression (Edit
  unreachable via its icon once scrolled), while leaving Back's low-cost pinning and the FAB's
  by-design pinning untouched. Chosen, per human direction recorded in issue #610's spec-clarification
  comment.

## Decision

- `WateringDueActionsRow`/`FertilizeDueActionRow` revert to plain `padding(horizontal = 16.dp)` — the
  `ROW_START_INSET`/`ROW_END_INSET` constants and their KDoc are removed entirely.
- The pinned Edit `IconButton` (`PlantDetailScreen.kt`'s Back/Edit `Row`) now fades out
  (`AnimatedVisibility` + `fadeIn()`/`fadeOut()`) once the `LazyColumn`'s `LazyListState` reports
  `firstVisibleItemIndex > 0` — i.e. once the 280dp hero photo (item index 0) has fully scrolled out of
  the viewport. `AnimatedVisibility` removes the button from composition (not merely its alpha) once the
  exit animation completes, so a faded-out Edit is neither clickable nor discoverable via
  accessibility/Compose UI tests — not just visually hidden.
- **Back is untouched**: no visibility logic, always pinned, never fades, exactly as ADR-0018 describes.
- **The FAB is untouched**: always pinned and clickable throughout scrolling, as before — persistent
  visibility is the entire point of a FAB.

## Consequences

- **The rows visually match every other card on Plant Detail again** — the problem #610 exists to fix.
- **Edit becomes unreachable via its icon once scrolled substantially past the hero**, with no
  alternative on-screen entry point today. This is a real, if narrow, functional regression, knowingly
  accepted rather than solved — a future issue could add a second Edit entry point (e.g. in a tab or an
  overflow menu) if this proves to matter in practice, but none exists as of this change.
- **The narrow Back/FAB touch-collision risk #604 fixed is knowingly re-accepted**: a tap near a row's
  leading edge can still land on the pinned Back button, or near the trailing edge on the FAB, on the
  rare occasion the row happens to be scrolled flush against that corner. This is a deliberate,
  human-confirmed trade-off (visual consistency over eliminating a narrow edge case), not a reopening of
  #604's review finding — the finding is still correct, just weighed differently now that its fix's own
  cost is visible.
- ADR-0018 remains `accepted`; it now carries a one-line "Amended by ADR-0022 (Edit button scroll fade)"
  note under its own Consequences section, since Edit is no longer persistently interactive throughout
  scrolling as that ADR originally described.
