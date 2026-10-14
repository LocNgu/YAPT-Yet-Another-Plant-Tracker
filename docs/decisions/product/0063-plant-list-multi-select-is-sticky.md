# Product ADR-0063: Plant List multi-select is sticky across visibility changes

**Status**: accepted

**Date**: 2026-10-13

## Context

Issue #449, a follow-up to the tap-and-hold multi-select of #441. While selecting, the room chips stay active, and
`PlantListViewModel.selectedPlantIds` was never reconciled with the visible `plantsWithStatus`. A plant selected
under one room could be hidden by a later filter yet stay in "N selected". `selectAll()` replaced the whole set with
the visible plants and so silently dropped hidden selections. `bulkLog()` filtered to the visible list and silently
skipped hidden plants, while `bulkArchive()` already acted on every selected id. That inconsistency was the actual
bug. A "N selected · M hidden" label was tried first and replaced by the chip dots at the owner's request: the dots
show where the hidden selections are, not just how many.

Alternatives considered:

- **Prune the selection to the visible plants on every filter change.** Rejected: switching rooms would silently
  destroy work the user did, and a search keystroke or a day change could do the same.
- **Disable the room chips while selecting.** Rejected: it blocks building a selection across rooms, which is a main
  reason to select, and it does nothing for search or sort filters that hide plants too.

## Decision

**The selection is sticky.** Nothing that changes what `plantsWithStatus` shows (room chip, sort filter, search, day
change) ever changes `selectedPlantIds`. Room chips stay enabled during selection. There is no automatic pruning.

- **Room-chip dots:** while selecting, each room chip whose room contains at least one selected plant shows a small
  dot in the theme's primary color, the Unassigned chip included. The "All" chip never does, and no chip does outside
  selection mode. The chip announces it through a `stateDescription` ("Contains selected plants"). The selection bar
  stays a plain `N selected`. `PlantListViewModel.roomsWithSelection` exposes the set of room keys, derived from the
  selected ids joined with the full active plant list (not `plantsWithStatus`), so a chip whose plants are currently
  hidden still shows its dot, and it runs no per-plant Room queries.
- **`selectAll()` is a union:** it adds the visible plants and keeps hidden selections.
- **`bulkLog()` acts on every selected plant, hidden ones included.** Plants are resolved from the repository's active
  plants, not from the filtered list. Ids that no longer resolve to an active plant are skipped silently.
- **`bulkArchive()` and its "Move to Graveyard?" dialog are unchanged.** The room-chip dots already show that hidden
  plants are part of the selection.

## Consequences

- Bulk actions never act on a different set than the selection the user built; the only surprise left (hidden
  plants) is visible as dots on the room chips.
- A selected plant that is archived or deleted elsewhere stays in the count until the user clears the selection.
  That is practically unreachable mid-selection, so there is no extra UI for it.
- Out of scope: a hidden-plant warning in the archive dialog and a select-all/deselect-all toggle.
