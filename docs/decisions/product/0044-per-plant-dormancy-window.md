# Product ADR-0044: Per-plant dormancy window

**Status**: accepted — full watering-suspension clause superseded by [ADR-0046](0046-optional-dormant-watering-cadence.md)

**Date**: 2026-09-21

## Context

Many succulents, cacti and geophytes stop growing entirely for a few months a year and must be kept
nearly dry through that stretch; watering on the app's normal schedule during dormancy rots the
plant. [#699](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/699) proposes a
per-plant dormancy window — an arbitrary, user-set stretch of the year during which watering
reminders are suspended outright, rather than merely stretched. This ADR records the decision for
the whole feature; the change lands in five dependency-ordered slices (#759-#763), of which this
issue (#759) is the first: the product decision, the data model, and the migration/backup plumbing.
**No behaviour changes in this slice** — nothing reads the new columns yet.

### Why ADR-0026's seasonal cosine cannot express this

ADR-0026's computed seasonal watering curve multiplies the base interval by a factor in
`[1 - amplitude, 1 + amplitude]`. That is a continuous, multiplicative scaling of a schedule; it can
stretch an interval, but it categorically cannot suspend one, because a multiplicative factor is
always finite and the curve is always active. The arithmetic from #699 makes the gap concrete: a
cactus on a 10-day summer base needs roughly 120 days between waterings in winter dormancy. The
longest interval the curve can produce is `base × (1 + amplitude)`:

| Amplitude | Longest winter interval | Needed |
|---|---|---|
| 0.35 (Standard) | 14 days | ~120 days |
| 0.50 (Strong) | 15 days | ~120 days |
| 1.00 (hypothetical, not offered) | 20 days | ~120 days |

Even the most extreme amplitude the curve could theoretically support falls an order of magnitude
short. This is not a tuning shortfall to be closed by a stronger amplitude preset — it is a
structural mismatch between "scale a schedule" and "suspend a schedule," and it also matches the
underlying horticulture: dormancy is a discrete state a plant enters and leaves, not a point on a
continuum of watering frequency.

### Why month-pair granularity, not day-of-year

A day-of-year range (`dormancyStartDay: Int?` / `dormancyEndDay: Int?`, 1-366) was considered and
rejected as an actual correctness bug, not a UX preference: day 305 is October 31 in a common year
and November 1 in a leap year. A window boundary stored as a day-of-year silently shifts by a day
around every leap year, which is unacceptable for a boundary the user picked deliberately. A
month-pair (`dormancyStartMonth: Int?` / `dormancyEndMonth: Int?`, 1-12) has no such ambiguity, needs
no calendar-day picker in the eventual UI, and matches the granularity #699's own body already leaned
toward. It also gives [#286](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/286)
(seasonal fertilizing) a stable shape to key its own winter slot off later — see below.

### The dropped `CareType.CHECK` acceptance criterion

#699's original acceptance criteria included "`CareType.CHECK` reminders continue during dormancy" —
the idea being that watering stops but a low-frequency "is it still healthy" nudge continues. This
criterion is dropped; it is unrepresentable on current `develop` for two independent reasons:

1. Product ADR-0039 made `CareType.CHECK` write-only-in-the-past. There are zero write sites left in
   `app/src/main/kotlin` — the enum constant survives only for Room/`.yapt` deserialization of
   historical rows written before ADR-0039 shipped. Resurrecting a CHECK-log write path to serve
   dormancy would reopen exactly the write path ADR-0039 closed on record.
2. The "Check {plant}" notification wording (ADR-0027) was never a distinct low-frequency reminder —
   it is the ordinary watering-due reminder renamed, still gated on
   `status.isOverdue || status.isDueSoon`. Since dormancy must suppress exactly those two flags to
   suppress the watering reminder at all (see Decision below), this notification stops firing as a
   direct, structural consequence of the dormancy suppression itself. There is no independent
   check-cadence mechanism left underneath the renamed text for dormancy to preserve.

A user who wants a periodic "check on the cactus" nudge during dormancy has an existing, unrelated
mechanism available: **Custom Reminders** (`CustomReminderEntity`/`CustomReminderRepository`/
`CustomRemindersSection`), already wired into `ReminderWorker`, unbounded per plant, anchored to its
own `createdAt` and entirely independent of watering status. Dormancy suppresses watering-derived
reminders specifically; it has no opinion on custom ones.

### Manual-only configuration

The window is set by the user via two month pickers, default off (both `null`). There is no
detection or auto-suggestion of dormancy from `Plant.species` or from observed watering behaviour —
`Plant.species` is free text with no backing database (YAPT is offline by design), so it cannot drive
inference, and inferring a window from behaviour is explicitly out of scope for this feature.

This is a deliberate, named trade-off: the motivating scenario in #699 — a cactus owner on the
shipped default who has no reason to suspect "dormancy window" is a setting worth going looking for —
is **not itself fixed by this feature**. Manual configuration only helps a user who already knows (or
is told, e.g. by this ADR's eventual UI copy) that the control exists. Detect-and-offer is real,
future, and out of scope work, not a gap in this decision.

### Relationship to #286 (seasonal fertilizing)

#286's dominant real need is the same winter pause described here — "this plant rests from November
to February" is one fact about the plant, not two independently-shaped facts. #286 will consume
`dormancyStartMonth`/`dormancyEndMonth` **by reference** for its winter slot rather than adding a
second, parallel range field to the schema and the backup format. The two features share the
dormancy window and nothing else: watering keeps ADR-0026's continuous cosine outside the window,
while fertilizing needs discrete slots for its growing-season taper — neither model is forced into
the other's shape.

## Decision

Two nullable columns are added to the plant record, both defaulting to `null` (no dormancy) so every
existing plant is unaffected:

- `dormancyStartMonth: Int?` (1-12)
- `dormancyEndMonth: Int?` (1-12)

Mirrored verbatim on `PlantEntity` (Room), `domain/model/Plant` (the UI-facing domain model), and
`BackupPlant` (`.yapt` export/import). Both fields are either both `null` (no dormancy) or both set;
`dormancyStartMonth > dormancyEndMonth` represents a window that wraps the year boundary (e.g.
November-February for northern-hemisphere winter dormancy), and `dormancyStartMonth <=
dormancyEndMonth` represents a non-wrapping window (e.g. June-August for a summer-dormant
winter-grower like *Lithops*). Membership testing, `PlantCareStatus.isDormant`, and reminder
suppression are **not** part of this slice — they land in #760.

Storage-only consequences of the eventual (future-slice) behaviour, recorded here since they shape
this slice's schema even though nothing reads it yet:

- Due-date computation is unaffected by dormancy directly — `nextWateringDueAt` keeps being computed
  exactly as today and may land inside the window. Suppression comes from a separate
  `PlantCareStatus.isDormant` flag forcing `isOverdue`/`isDueSoon` false while inside the window,
  not from pushing the due date to the window's end. This is a future-slice (#760) decision, noted
  here so the two nullable columns above are understood to be the only storage this feature needs —
  no separate "effective due date" column.
- `Migration 13→14` is a pure `ALTER TABLE ... ADD COLUMN` with no row iteration (per technical
  ADR-0002, every schema change ships an explicit `Migration`; `fallbackToDestructiveMigration()` is
  never used). `PlantDatabase.DB_VERSION` moves 13 → 14, with schema JSON committed at
  `app/schemas/com.yapt.planttracker.data.db.PlantDatabase/14.json`.
- `.yapt` backup schema moves `BackupManager.CURRENT_SCHEMA_VERSION` 16 → 17, with both new
  `BackupPlant` fields defaulting to `null` so a backup taken before this change restores with no
  dormancy window on any plant.

## Consequences

- This slice ships no user-visible or behavioural change: no reminder, due-date, or UI difference is
  observable from it alone. It exists purely so the four dependent slices (#760 window predicate and
  reminder suppression, #761 adaptive-model protection, #762 editing controls, #763 due groupings and
  the "Why this date?" sheet) have committed storage to build on, each shippable and reviewable on
  its own.
- The dropped `CareType.CHECK` acceptance criterion is a permanent scope reduction for this feature,
  not a deferral — a future "periodic check during dormancy" request should be routed to Custom
  Reminders, not to a resurrected CHECK write path.
- The discoverability gap for the motivating cactus-owner scenario is accepted as a known limitation
  of manual-only configuration, not solved by this ADR. Detect-and-offer, if ever built, is separate,
  future work with no issue filed against it yet.
- #286 is expected to key its winter slot off `dormancyStartMonth`/`dormancyEndMonth` by reference
  once this ships, rather than adding a parallel range field — a constraint on #286's own future
  design, not something this ADR builds.
- DB version 14 and backup schema 17 are now claimed. Any other in-flight work targeting either
  number needs to rebase on top of this slice.
