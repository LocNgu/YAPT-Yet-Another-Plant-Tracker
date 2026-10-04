# Product ADR-0061: Manual misting is retired app-wide; existing mist entries stay

**Status**: accepted

**Date**: 2026-10-04

**Amends**: [ADR-0022](0022-repotting-reminder.md)'s clause "`CareType.MIST` remains available as a
**manually logged care event**" — nothing can log a new mist any more. ADR-0022's rationale for never
shipping a misting *reminder* stands unchanged. [ADR-0060](0060-plant-detail-home-tab.md)'s clause that the
Water tab's WATER list sits "beside its misting list" — that list was removed by #876, and this ADR records
the removal. Per technical ADR-0029 both amendments are recorded on those ADRs' Status lines only; their prose
is untouched.

**Follows the precedent of**: [ADR-0039](0039-reschedule-returns-to-model-neutral.md), which retired
`CareType.CHECK` the same way — enum constant kept, never written by new code. This ADR differs from it on
one point, below.

## Context

The maintainer's position is that "misting should not exist anymore" (#875). Product ADR-0022 had already
dropped misting *reminders* because misting is largely a houseplant myth — it raises humidity for minutes and
can encourage fungal leaf spot — but deliberately left `CareType.MIST` loggable by hand, so users who mist
could still record it. The maintainer has now reversed that: the app should not offer to record care it does
not endorse, anywhere.

By #876 (the second half of #530) the only remaining *list* of misting was gone from the Water tab, which left
these surfaces still able to create or present a mist: the Add Care Log type picker (and its `careType` route
argument / `preselectCareType()`), the Plant List bulk action bar, the watering chart's care-event markers,
the developer demo data, and the enum and its display resources.

Existing mist entries are the hard part. They are rows users typed in, persisted as the String `MIST`, and
`.yapt` backups carry them too. Options considered for them:

- **Hide them like CHECK rows.** Rejected: CHECK rows were app-generated observations ("soil still moist" from a
  notification action) that the owner had no use for. A mist entry is a record the user wrote, with their own
  notes, date and sometimes a photo. Making it vanish from the history is data loss as far as the user can
  tell, even though the row stays on disk.
- **Convert them to NOTE.** Rejected: it rewrites user data, needs a Room migration and a backup-version story,
  and loses the type. The issue puts any migration that rewrites or deletes MIST rows out of scope.
- **Make an old MIST log read-only in Add Care Log.** Rejected: the user may still want to fix its date, notes
  or photo, or reclassify it as something else.
- **Drop MIST markers from the watering chart.** Rejected: the markers are history, drawn from existing rows
  (technical ADR-0016), and removing them changes nothing about what can be created.

## Decision

**Nothing in the UI can create a new MIST log.**

- Add Care Log's type picker is built by `careTypePickerOptions(includeMist)`: CUSTOM and CHECK stay
  excluded as before, and MIST is excluded in create mode.
- A `careType=MIST` route argument (`NavGraph`) and `AddCareLogViewModel.preselectCareType(MIST)` both fall
  back to the default, WATER, instead of preselecting MIST.
- `BulkActionBar` no longer offers Mist (`BULK_CARE_TYPES` is Water, Fertilize, Prune, Repot); `MIST` joins
  the "not offered in bulk" defensive branch of `bulkActionLabelRes()` and the `bulk_action_mist` string is
  deleted.
- The demo data no longer seeds a MIST log.

**Editing an existing MIST log still works, and keeps the type.** The Mist chip appears in Add Care Log only
while editing a log whose stored type is MIST (`AddCareLogViewModel.offersMistType`, set once at load and never
cleared). It shows selected; the user can keep it or switch to another type, and can switch back before saving
because the chip stays for the whole session. Notes, date and photo stay editable. For any new log, and for any
edit of a non-MIST log, the picker has no Mist chip.

**Existing mist entries stay visible, editable and deletable — this is where MIST differs from CHECK.** Both
share "constant kept, never written", but there is **no display filter** for MIST. Plant Detail's Home care
history lists them like Prune and Note, counts them in the header and the five-then-"Show more" collapse, and
the row's edit and delete work as before. The watering chart keeps drawing historical MIST markers, clustered
and stacked with other care events (`WateringHistoryChart` is untouched).

**`CareType.MIST` stays as an enum constant**, with its label (`care_type_misted`) and icon
(`Icons.Filled.Shower`) in `EnumResources`, with a KDoc and comments marking it retained for historical data.
It is read back through the usual `runCatching { valueOf }.getOrDefault(fallback)` and persisted as a String,
so removing it would make existing rows and backups coerce to the wrong type. A future cleanup must not tidy it
away as dead code.

**Import and schema are unchanged.** No Room migration, no `.yapt` backup-version bump, no change in
`BackupManager` or `CareLogRepository`. A restored backup's MIST rows load unchanged and appear in Home's
history; they are never converted to NOTE. MIST never drove a due date, so the schedule is unaffected.

**The Water tab's "Recent misting" list is gone** (#876), so ADR-0060's Decision text — a WATER list "beside
its misting list" — is stale on that point; this ADR is where the removal is recorded. Existing mist entries are
reachable only through Home's combined history.

## Consequences

- A user who mists can no longer record it as a mist. A NOTE can carry the same information in free text.
- Old mist entries keep appearing in the history, in the chart and in backups, indefinitely. That is deliberate:
  they are the user's own records.
- `CareType.MIST` joins `CareType.CHECK` as a constant that is read but never written. The two are retained for
  different reasons and handled differently: CHECK rows are hidden, MIST rows are shown.
- Add Care Log's picker now depends on the screen's edit state, which is why the chip list is a small pure
  function and the "offer Mist" flag lives on the ViewModel rather than being derived from the selected type
  (the selected type changes when the user switches away; the flag must not).
- Future code that offers a list of care types to create must leave MIST out. Code that reads or displays care
  types must keep handling it.
- Released changelog and What's New entries that mention misting are history and are not rewritten.
