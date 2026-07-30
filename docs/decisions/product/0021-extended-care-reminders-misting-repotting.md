# Product ADR-0021: Extended care reminders (misting & repotting) and their first-due anchor

**Status**: accepted

**Date**: 2026-07-30

## Context

Reminders historically covered only watering and fertilizing (`wateringIntervalDays` / `fertilizingIntervalDays` on the plant, surfaced by `CareSchedule.computeStatus()` and `ReminderWorker`). Issue #232 asked for reminders on other recurring care tasks — misting (some tropicals need it daily/weekly) and repotting (typically every 1–2 years) — plus, eventually, fully user-defined custom reminders.

`MIST` and `REPOT` already exist as first-class `CareType` values, so logging them worked; what was missing was a *schedule* for them and inclusion in the daily reminder.

Two questions had product weight:

1. **Where do the interval controls live and how are they modelled?** The existing pattern is a nullable `xIntervalDays` column per care type on the plant, with a Switch + Slider on Add/Edit Plant. The alternative — a generic `reminders` table keyed by `CareType` — is more uniform but a larger refactor of `CareSchedule` / `PlantCareStatus` and their many consumers.
2. **When is the *first* misting/repotting due for a plant that has never had that care logged?** Watering resolves a never-watered plant to "due today" (#428); never-fertilized uses a 30-day grace anchored to `createdAt` (`FIRST_FERTILIZE_GRACE_DAYS`). Neither is the right default for misting/repotting.

The issue is large (built-in extended reminders + a custom-reminders table + new CRUD UI). Per the workflow's scope-splitting guidance it was split: this decision and PR cover the **built-in MIST/REPOT** reminders; free-text custom reminders are tracked as a separate follow-up issue.

## Decision

Model misting and repotting exactly like watering/fertilizing: nullable `mistingIntervalDays` / `repottingIntervalDays` columns on `plants` (Room DB v7, `MIGRATION_6_7` adds both as nullable `INTEGER`), a Switch + Slider each on Add/Edit Plant (misting 1–30 days, default 7; repotting 30–730 days in 5-day steps, default 365), and eight new `PlantCareStatus` fields (`next*DueAt` / `is*Overdue` / `is*DueSoon` / `last*At` for each). `CareSchedule.computeStatus()` gains optional `lastMistedAt` / `lastRepottedAt` params (defaulted to `null`, so existing callers are unaffected). `ReminderWorker` queries the last `MIST` / `REPOT` log for a plant only when its interval is set, so **logging that care type resets its due date** with no extra bookkeeping. `ReminderNotificationComposer` gains `Misting*` / `Repotting*` `CareReminderItem`s, joined into the notification body with the existing `" · "` separator.

**First-due anchor for extended care:** for a plant that has never had misting/repotting logged, the first due date is `createdAt + interval`, not "due today" and not a fixed grace period. A newly acquired plant was presumably just misted/repotted, so it should not fire on day one; anchoring to `createdAt + interval` gives a full interval of lead time and then follows normal date math (overdue once `createdAt + interval` is in the past). This lives in the private `CareSchedule.extendedCareDueAt()` helper.

The generic `reminders`-table approach was rejected for this slice: it would churn `CareSchedule` / `PlantCareStatus` and every consumer for no user-visible gain, and the per-column pattern is what the codebase already reads cleanly. The custom-reminders feature (free-text name + interval, its own table) is deferred to a follow-up issue and may revisit a generic model then.

Misting/repotting status is **not** surfaced on the plant list, plant detail, or calendar in this slice — the reminder notification and the Add/Edit toggles are the delivery mechanism the acceptance criteria call for. Surfacing them on those screens is future work.

The settings round-trip through backup/restore as `BackupPlant.mistingIntervalDays` / `repottingIntervalDays` (backup schema bumped 6 → 7; old backups deserialize both to `null` via field defaults).

## Consequences

- Users can set misting and repotting reminders per plant and receive them in the daily notification alongside watering/fertilizing; logging a `MIST`/`REPOT` care event resets the schedule automatically.
- The `createdAt + interval` first-due rule means a brand-new plant with a repotting interval of 365 days won't nag for a year, and one with a 7-day misting interval waits a week — no immediate "overdue" on the day the reminder is enabled. This is a deliberate difference from watering's "due today" default.
- `PlantCareStatus` grew eight fields, all defaulted, so positional constructions in tests and the widely-used consumers keep compiling; only `CareSchedule` sets them.
- Extended-care status is intentionally absent from the list/detail/calendar surfaces for now — a follow-up can add chips/rows if desired.
- Custom (free-text) reminders remain unbuilt; they are tracked in a separate issue and are the reason acceptance criteria about user-named reminders are not closed by this PR.
