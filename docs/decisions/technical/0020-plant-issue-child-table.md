# Technical ADR-0020: Plant issues as a second child table, with an unenforced link to a `CustomReminder`

**Status**: accepted

**Date**: 2026-08-18

## Context

Issue #564 (requested by the human during review of #560's custom-reminders implementation) asks for a way to
track "this plant currently has a pest/disease problem" as an ongoing status — a start date, an optional
description, and a resolved/end date once cleared — visually distinct from custom reminders' due/not-due model.
The two concepts are easy to conflate (both are "something to keep an eye on for a plant"), but they answer
different questions: a `CustomReminder` answers "when do I next need to act", while a `PlantIssue` answers "is
this plant currently unwell, and for how long has it been". Reviewed product ADR-0022 (repotting, a single
nullable-column extended-care type) and technical ADR-0019 (custom reminders, an unbounded-per-plant child table)
before deciding; ADR-0019 is the closer analog since a plant can have zero, one, or several simultaneous issues,
which rules out ADR-0022's "nullable column on `plants`" shape the same way it did for custom reminders.

Two further questions had technical weight:

1. **Extend `CustomReminder` with a nullable `resolvedAt`, or add a new table?** A `CustomReminder` recurs
   indefinitely by design — "resolving" one doesn't fit its lifecycle (what would `lastDoneAt` mean for a resolved
   reminder?), and the due-status computation (`CareSchedule.computeCustomReminderStatuses`) has no notion of an
   end state. Bolting a resolution flag onto the existing table would make every `CustomReminderStatus` consumer
   defend against a state (`resolvedAt != null`) that never has a due date at all.
2. **How does a `PlantIssue` reference the treatment reminder created alongside it?** The "report an issue" flow
   optionally creates both rows in one action — the human confirmed a plain, nullable, non-enforced id column
   (the same shape as `CareLog.customReminderId` from ADR-0019), not a `ForeignKey`, since the two tables must not
   hard-couple lifecycles (see Decision below).

## Decision

**`PlantIssue` is a genuine child table** (`plant_issues`, Room DB v8 → v9, `MIGRATION_8_9`), mirroring
`CustomReminder`'s shape: `id`, `plantId` (FK to `plants`, `ON DELETE CASCADE`), `name`, `startedAt`,
`resolvedAt: Long?` (null = active), `resolutionNote: String?`, `linkedReminderId: Long?`. `PlantIssueDao`/
`PlantIssueRepository` mirror `CustomReminderDao`/`CustomReminderRepository`'s shape (a list-returning `Flow` per
plant, plus `getActiveIssuesForPlant` for the unresolved subset and a suspend `getActiveIssueCountForPlant` for the
plant-list badge) rather than a single-row shape, for the same unbounded-per-plant cardinality reason as ADR-0019.

**Multiple simultaneously-active issues per plant are allowed with no uniqueness constraint** — "active" is simply
"however many rows have `resolvedAt == null`"; the plant-list badge shows a count when more than one.

**`PlantIssue.linkedReminderId` is a plain nullable `Long` column with no FK, following the `CareLog
.customReminderId` pattern from ADR-0019 exactly**, not a `ForeignKey`. The "report an issue" flow's optional
"set a treatment reminder" sub-section creates a `CustomReminder` row first (if filled in) and stores its new id
on the `PlantIssue` being created in the same `PlantDetailViewModel.reportIssue()` call. This is a deliberate,
one-way, unenforced link: resolving or deleting a `PlantIssue` never touches, disables, or deletes the linked
`CustomReminder` — it keeps recurring independently and is managed entirely through the existing Custom Reminders
card. Symmetrically, deleting the `CustomReminder` (from that card) leaves `linkedReminderId` dangling; the Active
Issues card looks it up by id through the same `customReminders` list already loaded for the Custom Reminders card
and simply omits the "linked reminder" line when the lookup misses, never crashing on a stale id — the identical
dangling-reference posture ADR-0019 established for `CareLogItem`'s `customReminderName`.

**No involvement from `ReminderWorker` or `ReminderNotificationComposer`.** Unlike custom reminders (which fold
into the daily notification's overdue/due-today lines) and repotting, a `PlantIssue` has no due date to be
overdue against — it is a passive status, not a schedule. Per the spec clarifications, notification integration is
explicitly out of scope for this slice.

**A new visual-language color, `IssuePurple` (`ui/theme/Color.kt`), deliberately outside the existing
`OkGreen`/`WarnOrange`/`OverdueRed` due-status palette.** Those three colors all mean "care is due on some
timeline"; a `PlantIssue` badge means "this plant has a health problem right now", a different axis entirely, and
reusing `OverdueRed` (the closest visual match) would make an active pest issue indistinguishable from an overdue
watering at a glance — exactly the failure mode issue #564 called out ("a plant in active distress doesn't stand
out from one that's just due for routine care"). `PlantCard` shows the badge (bug icon, `IssuePurple` background,
count when > 1) whenever `PlantCareStatus.activeIssueCount > 0`; Plant Detail shows the full "Active issues" card.

**`PlantCareStatus.activeIssueCount: Int` is a plain field set directly by each ViewModel, not routed through
`CareSchedule.computeStatus()`.** Every other field on `PlantCareStatus` is a due-status computation with
calendar-day math; `activeIssueCount` has none — it is just `activeIssues.size`. Threading it through
`CareSchedule` would imply it participates in the same due/overdue logic as watering, fertilizing, repotting, and
custom reminders, which would misrepresent what it is.

Backup schema bumped 9 → 10: `BackupRoot.plantIssues: List<BackupPlantIssue>` (old backups → `emptyList()`),
following the exact `BackupModels`/`BackupManager` bulk-query-then-group-by-`plantId` pattern used for
`customReminders` (fetch `getAllIssues()` once, `plants.flatMap { grouped[it.id].orEmpty() }` — not a suspend call
inside `flatMap`, the bug #560 fixed for `customReminders`).

## Consequences

- `PlantIssueDao`/`Repository` are a fourth near-duplicate of the `CareLog`/`CustomReminder` CRUD shape. Consistent
  with the established "no generic repository/DAO abstraction" stance (technical ADR-0001).
- `PlantDetailViewModel`'s constructor and `Factory` grow to 8 parameters (`@Suppress("LongParameterList")`,
  already applied there since ADR-0019); `PlantListViewModel`'s constructor/Factory pick up the same suppression
  for the seventh parameter needed to compute `activeIssueCount` per card.
- A `PlantIssue` whose linked `CustomReminder` was since deleted permanently shows no "linked reminder" line on
  its Active Issues row — an accepted, deliberate loss of that cross-reference in exchange for not hard-coupling
  the two tables' lifecycles, identical to the trade-off ADR-0019 accepted for `CareLog.customReminderId`.
- A future "issue history" view (showing past resolved issues, not just active ones) can reuse the existing
  `getIssuesForPlant` query (already returns the full history, unfiltered by `resolvedAt`) without a schema change
  — only new UI would be needed.
