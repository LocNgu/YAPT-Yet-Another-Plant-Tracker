# Product ADR-0072: Propagate is a log-only care type, and plants can record where they came from

**Status**: accepted

**Date**: 2026-10-28

## Context

Issue #960. Users take cuttings, divide plants and separate offsets, and had nowhere to record it. The request began as
a new care type and grew, over three rounds of owner decisions, into a small family model: a propagation can produce
child plants, and the user wants to see "Propagated from <Parent>" and the list of children.

Alternatives considered:

- **A schedulable type** (an interval, Care queue tasks, notifications). Rejected: propagation is an occasional event,
  not a routine. It would add an interval column, `CareSchedule` and `TodayQueueAggregator` branches, and a reminder
  nobody asked for.
- **Treat it like REPOT** (technical ADR-0023: confidence to 0, a fresh `wateringResetAt` anchor, a 4-week learning
  freeze). Rejected: taking cuttings may or may not change how much the mother plant drinks, and a 4-week freeze
  would throw away learning for plants that did not change. The app should not assume either way.
- **No parent link; only a name hint** ("<Parent> (cutting)"). Rejected by the owner: a stored link is what lets Plant
  Detail show the family.
- **A parent field on Add/Edit Plant.** Rejected by the owner: that page is already long. Linking lives on the Propagate
  tab's Family card instead.
- **A family-tree screen.** Deferred to a follow-up issue; tapping through relatives is enough for now.
- **A dedicated technical ADR for the self-referencing foreign key.** Not written: the choices (self-FK, two-pass
  restore, transactional cycle check) are small and only make sense together with this feature, so they are recorded here.

## Decision

**Propagate is a log-only care type.** `CareType.PROPAGATE` is appended last. It has no interval, no reminder and no
due state, and never appears in the Care queue, Calendar quick-log, notifications, Plant List or the bulk bar. One
propagation event is one log, however many child plants it produces (splitting a plant into five is one log on the
mother and four new plants).

**Surface.** A new `PlantDetailTab.PROPAGATE` sits after Prune in the collapsed second row. Its filled "Propagate" button
opens the shared `CareDatePickerBottomSheet` (the Prune pattern). The tab lists the plant's PROPAGATE logs (editable
and deletable, also shown in Home's combined history). It has no notes field; notes and a photo are added by editing the
log in Add Care Log, which stays edit-only (product ADR-0062).

**Same-day duplicate guard.** PROPAGATE joins WATER and FERTILIZE in `CareLogRepository.hasLogOfTypeOnDay()`, checked in
`QuickLogUseCase` and when editing in Add Care Log (excluding the edited row). Because one event is one log, a second
one on the same day is a user error. REPOT and PRUNE stay unguarded; changing that is a separate decision.

**Effect on watering: a confidence drop, not a reset.** A newly created PROPAGATE log sets
`wateringConfidence = max(c - 2, 0)` on the 0-5 scale (technical ADR-0021), matching `STREAK_CONFIDENCE_PENALTY`. A `null`
confidence stays `null`. Lower confidence raises the adaptive gain, so the next waterings re-tune the interval faster if
the plant's needs changed; if they did not, gap agreement raises confidence again. The base interval, due date,
`wateringResetAt` and `wateringFreezeUntil` are untouched. One `WateringAdjustmentTrigger.PROPAGATE_CONFIDENCE_DROP` row
(appended last, `before == after`) is written, and only when the confidence actually changed. The drop is applied once,
at creation, from `QuickLogUseCase` after a fresh read of the plant; it ignores the log's date and any freeze window,
and editing or deleting the log never re-applies or reverses it. Technical ADR-0023 is deliberately **not** used, so
nobody later "fixes" PROPAGATE to behave like REPOT. On the same day, PROPAGATE then REPOT ends in the same state as a
lone REPOT (both rows are kept when the drop changed anything), and REPOT then PROPAGATE leaves the drop a no-op.
A PROPAGATE log never clears a pending repot plan; dividing at the root is logged as a Repot too, which does.

**Plant families.** `Plant.propagatedFromId` is a nullable self-reference (`plants.propagatedFromId INTEGER NULL
REFERENCES plants(id) ON DELETE SET NULL`, indexed; `MIGRATION_19_20`, `DB_VERSION` 20, `20.json` committed per
technical ADR-0002). Deleting a parent nulls its children's link. It is carried over by `AddEditPlantViewModel.saveEdit()`
and written only by narrow column statements (technical ADR-0036): `setPropagatedFrom`, and `linkToParent`, which refuses
a self-link, a missing parent and any descendant in one atomic statement, so the repository is the cycle guarantee and the
picker's exclusions are only a convenience.

**Family card (Propagate tab).** "Propagated from" shows the parent, or a "Link parent..." button when there is none.
"Children" lists the children, with "Link existing plant..." and "Add new plant". Rows open the relative's Plant Detail
(on its Propagate tab, so the tree can be walked); a long-press, or the "Unlink" custom accessibility action, unlinks.
Archived or hidden relatives are listed and labelled as such. Both link buttons open a searchable plant picker: "Link
parent" excludes the plant and its descendants; "Link existing plant" excludes the plant, its ancestors and its existing
children. A candidate that already has a different parent stays selectable, shows that parent, and is re-parented when
picked.

**After a log is saved**, a dialog offers **New plant** (Add Plant prefilled with the parent's species and room and the
name "<Parent> (cutting)", already linked), **Existing plant** (the picker) or **Not now**. Add Plant gets no editable
parent field; when opened from the offer it shows only a read-only "Propagated from <Parent>" line. The Family card
offers both again later, for a child the user forgot.

**Backup.** Schema 24 adds `BackupPlant.propagatedFromId`. Restore inserts every plant unlinked, then applies the links
in a second pass, so insert order never trips the foreign key. A dangling, self or cyclic link becomes `null`; a tail that
leads into a cycle keeps its own link. Older backups read `null`.

**Tab strip.** The strip grows to nine tabs: Home · Water · Fertilize · Photo | Repot · Prune · Propagate · Reminders ·
Issues. At product ADR-0043's fixed 25% width the expanded strip is 4 + 4 + 1, leaving one tab alone on a third row.
`COLLAPSED_TAB_COUNT` stays 4. That layout is accepted rather than changing the tab width.

## Consequences

- Product ADR-0043 and product ADR-0060 (tab set and order) and product ADR-0062 (the care types a new log can have) get
  Status-line amendments; technical ADR-0021 gets one for its confidence-fall conditions.
- Propagating costs the mother plant some confidence even when its water use did not change; the model recovers through
  normal gap agreement. Accepted over assuming a reset.
- A second PROPAGATE on the same day is refused, so a user who splits one plant twice in a day logs it as one event.
- The expanded tab strip has a lone ninth tab on its own row. Needs a device check.
- Out of scope, as follow-ups: a family-tree screen, duplicate guards for REPOT/PRUNE, and detecting relationships
  automatically.
