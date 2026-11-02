# Product ADR-0077: The owner has the final say on a sitter visit's list

**Status**: accepted

**Date**: 2026-11-02

**Amends**: [ADR-0065](0065-away-mode.md) (planning a visit: step 3's rule now produces a suggestion).

## Context

Issue #979. Product ADR-0065 step 3 lists every plant due by the visit, or strictly before halfway to the next
visit, and the owner could only share that list as it came. The owner often knows better than the schedule: a
plant that was watered by hand yesterday, a plant that is poorly and is better left alone, or a plant that is not
due yet but will not last until the next visit. A resting plant (dormant, or watering suspended) might still need
a drink on a warm shelf. Sharing a list the owner disagrees with meant editing it by hand outside the app.

Alternatives rejected:

- **Letting the owner only untick.** The common complaint is a missing plant, so it would fix half of it.
- **Editing the plant's schedule to move it onto the list.** Product ADR-0065 already rejected plant-row writes
  from the trip flow (no clean undo, and it would feed adaptive learning).
- **Reusing the Confirm checklist's state.** That set belongs to confirming the last visit and is cleared when
  it is answered; sharing one state between two screens' meanings would leak ticks between them.

## Decision

The rule in product ADR-0065 step 3 now produces a **suggestion**, and the owner can adjust it:

- **Untick** a suggested plant.
- **Add** a plant that was not suggested, including a **resting** one.
- Only the **ticked** plants are shared (PDF and text) and saved as the visit.
- An added resting plant **moves**: it goes on the visit pages (due on the visit day) and is dropped from
  **Please don't water these**, so a plant never appears on both.
- **Changing the day or the gap keeps the owner's edits** while the screen is open. They are cleared when the
  screen is left; losing them when the process dies is acceptable.
- **Re-opening a day whose list was already shared** (a PLANNED visit exists for that day) starts from the list
  that was shared. Picking a day without a saved visit keeps the current edits.

Product ADR-0065's invariants still hold: the due dates still come only from the Care queue's aggregator, planning
writes no plant row, a visit is saved only when shared, and a same-day replace swaps the list. A removed plant
comes up on the next visit because an unconfirmed visit counts its saved list as watered, and the removed plant
is not on it. An added plant that was not due is counted as watered by the same rule, and so waits its interval.

The not-listed plants (due after the list reaches, up to a year ahead) come from the same single aggregator
call, with a wider `horizonEnd`, so the picker never has due-date logic of its own. A resting plant is never
also not-listed.

## Consequences

- The suggestion is still the default, so an owner who edits nothing sees no change.
- The picker's edits live in the ViewModel's own state, apart from the Confirm checklist's.
- Re-opening a shared day shows the saved list, even if the plants due have changed since; the owner can adjust it.
- A later part of #979 adds the screen for ticking and adding plants; this decision is the domain rule it shows.
