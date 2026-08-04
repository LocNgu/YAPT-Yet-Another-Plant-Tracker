# Product ADR-0022: Repotting reminder (months-based) and the extended-care first-due anchor

**Status**: accepted

**Date**: 2026-07-31

## Context

Reminders historically covered only watering and fertilizing (`wateringIntervalDays` / `fertilizingIntervalDays` on the plant, surfaced by `CareSchedule.computeStatus()` and `ReminderWorker`). Issue #232 asked for reminders on other recurring care tasks — originally **misting and repotting** — plus, eventually, fully user-defined custom reminders.

The issue is large (extended built-in reminders + a custom-reminders table + new CRUD UI), so per the workflow's scope-splitting guidance it was split: this decision covers the **built-in repotting reminder**; free-text custom reminders are tracked as a separate follow-up issue.

Three questions had product weight:

1. **How are extended-care intervals modelled?** The existing pattern is a nullable `xIntervalDays` column per care type on the plant, with a Switch + Slider on Add/Edit Plant. The alternative — a generic `reminders` table keyed by `CareType` — is more uniform but a much larger refactor of `CareSchedule` / `PlantCareStatus` and their many consumers.
2. **When is the *first* repotting due for a plant that has never had it logged?** Watering resolves a never-watered plant to "due today" (#428); never-fertilized uses a 30-day grace anchored to `createdAt` (`FIRST_FERTILIZE_GRACE_DAYS`). Neither is right for a care task on a multi-month cycle.
3. **What unit does the user pick?** Days is the existing convention, but it is false precision for a task measured in seasons and years.

## Decision

Model repotting like watering/fertilizing: a nullable `repottingIntervalDays` column on `plants` (Room DB v7, `MIGRATION_6_7` adds it as a nullable `INTEGER`), a Switch + Slider on Add/Edit Plant, and four new `PlantCareStatus` fields (`nextRepottingDueAt` / `isRepottingOverdue` / `isRepottingDueSoon` / `lastRepottedAt`). `CareSchedule.computeStatus()` gains an optional `lastRepottedAt` param (defaulted to `null`, so existing callers are unaffected). `ReminderWorker` queries the last `REPOT` log for a plant only when its interval is set, so **logging a repotting resets its due date** with no extra bookkeeping. `ReminderNotificationComposer` gains `Repotting*` `CareReminderItem`s, joined into the notification body with the existing `" · "` separator.

The generic `reminders`-table approach was rejected for this slice: it would churn `CareSchedule` / `PlantCareStatus` and every consumer for no user-visible gain. The custom-reminders feature may revisit a generic model.

**Repotting is picked in months, not days.** Day-level precision is false precision here — repotting cadence is driven by how root-bound a plant looks, not a date, and nobody repots "every 185 days". The sane floor is roughly a quarter (fast-growing young plants and nursery stock), with most houseplants on a 12–18 month cycle and slow growers/succulents/cacti on 2–3 years; a **3–36 month** range (1-month steps, default 12) covers all of it. The UI state is `AddEditPlantViewModel.repottingIntervalMonths`, persisted as `months * DAYS_PER_MONTH (30)` into the day-based `Plant.repottingIntervalDays`, so the scheduling logic, DB schema, and `MIGRATION_6_7` stay day-based. Reading back clamps into 3–36, so an out-of-range stored value still lands on a valid slider position.

**First-due anchor for extended care:** for a plant that has never had repotting logged, the first due date is `createdAt + interval`, not "due today" and not a fixed grace period. A newly acquired plant was presumably just potted, so it should not fire on day one; anchoring to `createdAt + interval` gives a full interval of lead time and then follows normal date math (overdue once `createdAt + interval` is in the past). This lives in the private `CareSchedule.extendedCareDueAt()` helper, written generically so a future extended-care type can reuse it.

**Misting was deliberately dropped.** It was implemented alongside repotting and then removed before merge. Misting is largely a houseplant myth: it raises ambient humidity for only a few minutes, and repeatedly wetting foliage can encourage fungal leaf spot and bacterial problems — fuzzy-leaved plants (African violets, begonias) dislike it outright. A pebble tray or a humidifier is the effective intervention. Shipping a *scheduled reminder* would have actively nudged users toward a practice we do not endorse, so no misting interval, column, UI, or notification exists. `CareType.MIST` remains available as a **manually logged care event** — users who mist can still record it; the app just will not prompt them to. Since `MIGRATION_6_7` had not shipped when this was decided, the misting column was removed from the migration entirely rather than left as a permanently-unused column.

Repotting status is **not** surfaced on the plant list, plant detail, or calendar in this slice — the reminder notification and the Add/Edit toggle are the delivery mechanism the acceptance criteria call for. Surfacing it on those screens is future work.

The interval round-trips through backup/restore as `BackupPlant.repottingIntervalDays` (backup schema bumped 7 → 8; old backups deserialize it to `null` via the field default).

## Consequences

- Users get a repotting reminder on a realistic cadence, in the unit they actually think in, and logging a `REPOT` care event resets the schedule automatically.
- The `createdAt + interval` first-due rule means a brand-new plant with a 12-month repotting interval won't nag for a year — no immediate "overdue" on the day the reminder is enabled. This is a deliberate difference from watering's "due today" default.
- Storing months as `months * 30` days is a lossy approximation (a "12 month" interval is 360 days, not 365). That is acceptable for a reminder whose real trigger is "the roots look crowded"; it keeps one storage unit across all care types and avoids a schema change.
- `PlantCareStatus` grew four defaulted fields, so positional constructions in tests and the widely-used consumers keep compiling; only `CareSchedule` sets them.
- Declining to ship misting is a product stance, not an oversight: the app should not schedule care it considers ineffective. The tradeoff is that users who believe in misting get no reminder — they can still log it manually, and the decision is revisitable if evidence changes.
- Custom (free-text) reminders remain unbuilt; they are tracked in a separate issue and are why acceptance criteria about user-named reminders are not closed by this PR.
