# Product ADR-0066: Hide a plant without archiving it

**Status**: accepted

**Date**: 2026-10-17

## Context

There was no way to take a living plant out of view without moving it to the Graveyard (`archivedAt`), which says the
plant is dead or gone (#915). A plant kept somewhere else, or looked after by someone else, is neither. The owner won't
do this often, so it must not add weight to the everyday screens.

Alternatives rejected:

- **A "Show hidden" toggle on the Plants list.** It adds a control to the busiest screen for a rare action, and hidden
  plants would still feed Care, reminders and the Calendar.
- **Hiding only from the Plants list.** A plant the owner chose not to see would keep sending reminders and showing as
  overdue, which defeats the point.
- **Reusing the Graveyard with a "not dead" flag.** It would change the Graveyard's wording and behaviour, which stay as
  they are.

## Decision

- A plant has an optional `hiddenAt` timestamp, independent of `archivedAt`. `null` means visible.
- **A hidden plant leaves every operational surface**: the Plants list, Care queue, reminders and notifications, the
  Calendar, the Watering and Repotting overviews, and the away-mode sitter lists, including "Please don't water these".
  It remains only on the Hidden plants page and in backups.
- **Finding hidden plants**: Settings gets a **Hidden plants** page, nested like the Graveyard. Its row appears only
  while at least one plant is hidden. The page lists the plants, taps open Plant Detail (so care can still be logged),
  and each row has **Unhide**. The Plants list, its search, sort, room chips and multi-select don't change. When there
  are plants but every one is hidden, the Plants list shows its own empty state that points to the Hidden plants page.
- **Entry point**: a **Hide plant** / **Unhide plant** row on Edit Plant, below the form. There is no bulk action and no
  Plant Detail overflow menu. Hiding writes immediately (not on Save), returns to the Plants tab without assuming it is
  on the back stack (#836), and shows an Undo snackbar.
- **Storage**: Hide and Unhide write only `hiddenAt` (technical ADR-0036) and leave `updatedAt` alone, since hiding is a
  view preference, not an edit of care data. `AddEditPlantViewModel.saveEdit()` carries `hiddenAt` over, so an unrelated
  edit can't unhide a plant. Moving a plant to the Graveyard, single or bulk, clears `hiddenAt`, so restoring it brings it
  back visible.
- **Learning**: nothing special. Hiding writes no schedule data, and a long hidden gap is learned like any other gap.
- **Backup**: `hiddenAt` is part of the backup (schema 23). Older backups import with every plant visible.
- Background work that must still reach hidden plants, such as the seasonal graduation fixup, reads every non-archived
  plant rather than the visible ones.

## Consequences

- Every new surface that lists active plants must use the visible-plant query or `Plant.isVisible`, not just
  `archivedAt == null`.
- A notification already posted for a plant is cleared by the next daily `ReminderWorker` run, not at the moment of
  hiding.
- A hidden plant that is due has no way to nag the owner; that is the intent, and Unhide is one tap away.
- Excluding a long hidden gap from adaptive watering is a possible follow-up if it proves to skew intervals.
