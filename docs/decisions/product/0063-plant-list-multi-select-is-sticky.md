# Product ADR-0063: Plant List multi-select is sticky across visibility changes

**Status**: accepted

**Date**: 2026-10-13

## Context

Issue #449, a follow-up to the tap-and-hold multi-select of #441. While selecting, the room chips stay active, and
`PlantListViewModel.selectedPlantIds` was never reconciled with the visible `plantsWithStatus`. A plant selected
under one room could be hidden by a later filter yet stay in "N selected". `selectAll()` replaced the whole set with
the visible plants and so silently dropped hidden selections. `bulkLog()` filtered to the visible list and silently
skipped hidden plants, while `bulkArchive()` already acted on every selected id. That inconsistency was the actual
bug.

Alternatives considered:

- **Prune the selection to the visible plants on every filter change.** Rejected: switching rooms would silently
  destroy work the user did, and a search keystroke or a day change could do the same.
- **Disable the room chips while selecting.** Rejected: it blocks building a selection across rooms, which is a main
  reason to select, and it does nothing for search or sort filters that hide plants too.

## Decision

**The selection is sticky.** Nothing that changes what `plantsWithStatus` shows (room chip, sort filter, search, day
change) ever changes `selectedPlantIds`. Room chips stay enabled during selection. There is no automatic pruning.

- **Label:** the selection bar reads `N selected`, and `N selected · M hidden` when `M > 0`. "Hidden" is a selected id
  that is not in the current `plantsWithStatus`. `PlantListViewModel.hiddenSelectedCount` exposes it as a StateFlow.
- **`selectAll()` is a union:** it adds the visible plants and keeps hidden selections.
- **`bulkLog()` acts on every selected plant, hidden ones included.** Plants are resolved from the repository's active
  plants, not from the filtered list. Ids that no longer resolve to an active plant are skipped silently.
- **`bulkArchive()` and its "Move to Graveyard?" dialog are unchanged.** The "· M hidden" label already carries the
  warning that hidden plants are included.

## Consequences

- Bulk actions never act on a different set than the selection the user built; the only surprise left (hidden
  plants) is named in the bar.
- A selected plant that is archived or deleted elsewhere stays in the count until the user clears the selection.
  That is practically unreachable mid-selection, so there is no extra UI for it.
- Out of scope: a hidden-plant warning in the archive dialog and a select-all/deselect-all toggle.
