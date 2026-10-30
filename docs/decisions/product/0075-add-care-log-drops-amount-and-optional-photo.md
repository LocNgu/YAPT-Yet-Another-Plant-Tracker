# Product ADR-0075: Add Care Log drops the Amount field and the optional photo attachment

**Status**: accepted

**Date**: 2026-10-30

## Context

Issue #442. The edit-only Add Care Log screen (product ADR-0062) still carried two redundant controls. A free-text
**Amount** field sat next to the free-text **Notes** field, so it added a second place to type the same thing. Every
care type also offered an optional photo attachment (a "Photo (optional)" label and an add-photo icon that opened the
photo-source sheet). That attachment is redundant with the Photo quick action and the plant gallery, which are where
photos are added (product ADR-0038, product ADR-0013), and it is clutter on Water, Fertilize, Prune and Repot logs.

Alternatives considered: folding existing amounts into Notes during the migration (rejected: nobody has used the
field yet, and appending text to user notes is a silent edit), keeping the Amount column but hiding the field
(rejected: dead data in the schema and in every backup), and stripping the photo from existing non-photo logs
(rejected: it would delete gallery content).

## Decision

- **No Amount.** `care_logs.amount` is dropped (Room v21, `MIGRATION_20_21`), along with `CareLog.amount`, the backup
  field and the screen field. Existing values are discarded on upgrade, not copied into Notes. Old `.yapt` backups
  still restore, because the backup serializer ignores unknown keys, so the backup schema version is unchanged.
- **Photo only on PHOTO logs.** The photo block (label, preview, Take photo / Choose from gallery) shows only when the
  stored type is `PHOTO`, which still requires a photo and still updates the cover photo (product ADR-0015). Other
  types show no photo control and no thumbnail.
- **A photo a non-PHOTO log already has is kept silently.** Saving writes the stored `photoUri` back unchanged, so the
  log keeps surfacing in the gallery (product ADR-0013). There is no way to remove or replace it from this screen.
- **Notes hint.** The label stays "Notes (optional)". Water and Fertilize, the two types that used to have an Amount
  field, add the placeholder "e.g. watering amount"; Prune, Repot, Propagate and Photo show none.

## Consequences

An amount a user typed before this change is gone after the upgrade. A legacy non-PHOTO photo can no longer be changed
or cleared on its log (it can still be deleted with the log). Quick-log surfaces are unaffected. This amends the
"notes, amount" wording of product ADR-0062's accepted trade-offs: notes can still be added by editing a log, an amount
no longer exists.
