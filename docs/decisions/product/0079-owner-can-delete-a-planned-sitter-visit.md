# Product ADR-0079: The owner can delete a planned sitter visit

**Status**: accepted

**Date**: 2026-11-03

**Amends**: [ADR-0065](0065-away-mode.md) (a planned visit was fixed once shared; a planned visit from today on can now be edited or deleted).

## Context

Issue #993. Product ADR-0065 stores a visit when its list is shared and only lets the owner answer it afterwards
(confirmed or did not happen). A list shared by mistake, or for a day that changed, stayed in Settings and kept
counting as watered on the next projection, with no way to remove it.

Alternatives rejected:

- **Moving a visit to another day.** The suggested list depends on the day, and so do the later visits'
  projections, so a moved list would be wrong for its new day. Deleting and planning the new day gives a fresh,
  correct suggestion.
- **Delete on the Plan screen's top bar.** Settings' Sitter visits list is where visits are seen as a set; Plan
  stays about one day's list.
- **Editing or deleting confirmed or did-not-happen visits.** Those record what happened and stay final.

## Decision

- A `PLANNED` visit whose day is **today or later** can be **edited** (tap the row, or Edit in its overflow menu:
  Plan a sitter visit opens on that day, seeded from the saved list) or **deleted** (overflow menu, after a
  confirmation dialog).
- A past `PLANNED` visit keeps **Confirm** only. `CONFIRMED` and `DID_NOT_HAPPEN` visits have no menu and no tap.
- Delete removes the visit and its plants. It writes no care log and no plant row. It re-reads the visit under a
  lock and does nothing if it is gone or no longer `PLANNED`, so a final visit is never deleted.
- An unconfirmed visit counts its whole list as watered, so deleting it moves later suggestions earlier: its plants
  come up again on the next visit. The dialog says so.

## Consequences

- Product ADR-0065's "final once answered" rule is unchanged.
- No schema change; the projector is pure and re-derives from storage.
- Moving a visit costs two steps (delete, plan anew) in exchange for a correct list.
