---
description: ReminderWorker, notification composer, and reminder/notification toggles
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/worker/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/notification/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/domain/notification/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/domain/reminder/**/*"
  - "app/src/test/**/worker/**/*"
  - "app/src/test/**/notification/**/*"
  - "app/src/test/**/reminder/**/*"
---

# Notifications & Reminders rules

## ReminderWorker (WorkManager, REPLACE — technical ADR-0010)
- **Schedule and cleanup:** runs daily at the user's time. Before posting it cancels every active YAPT notification **except** the post-watering one (`-2`, technical ADR-0026).
- **What it posts:** one notification per overdue/due-soon plant (ID `plant.id.toInt()`), with care items joined by `" · "`. Tapping opens `MainActivity` with a `plantId` extra → Plant Detail (#7). It does nothing when POST_NOTIFICATIONS is denied.
- **Reminder time:** the default (`SettingsDefaults.REMINDER_HOUR`/`MINUTE` = 9:00) is written to DataStore on first launch, so `?: 9` fallbacks never re-anchor (#356). `MainActivity` passes the **stored** time to `ReminderScheduler.schedule()` on every launch (#394).
- `BootReceiver` reschedules via an `internal suspend fun` pulled out of `goAsync()` for testability.
- Custom reminders are fetched per plant (`getRemindersForPlantOnce()`) before `computeStatus()`.

## Pure composer (`ReminderNotificationComposer`, no Context)
`computeCareReminderItems`/`computeDueReminders` decide what's due; `ReminderWorker` localizes the resulting `CareReminderItem`s.
- **Dormancy:** a fixed dormant cadence notifies normally, a full pause exposes no watering flags, and fertilizing is suppressed in both (#785).
- **Combine toggle** (`combine_notifications`, default off): one count-only notification, `COMBINED_NOTIFICATION_ID = -1`, landing on Plant List, with no per-plant action (product ADR-0020, #474).
- **"Notify for fertilizing"** (`fertilizing_notifications_enabled`, default on): when off, fertilizing-only reminders are dropped (`isFertilizingOnly()`), but a watering-due plant keeps its fertilizing line. Liquid-fertilizer plants never get a fertilizing-only reminder; "Fertilize with watering" is appended to the watering alert instead (product ADR-0021, #223, #56).
- **Custom reminders:** `CustomReminderOverdue(name, days)` / `CustomReminderDueToday(name)`, with the free-text name straight into the body (technical ADR-0019).
- **Planned repot** (#809, product ADR-0057):
  - In season: `RepottingPlannedThisSeason(season)` ("Repot planned this spring", one string per season via `repotPlannedNotificationRes()`).
  - After the season: `RepottingPlanOverdue(days)`, with N counted from the season's **last** day (`repottingPlanSeasonEndAt` − 1 day), never from `nextRepottingDueAt`.
  - An upcoming plan composes nothing and suppresses the interval date.
  - Interval repots use `RepottingOverdue`/`RepottingDueToday`. A plan-only plant needs no `lastRepottedAt` load.
  - A repot-only reminder never gets the watering "Check" reframe.

## Watering-due reminders are check-ins (product ADR-0027/0030/0039)
- The reframe applies only when watering is due (`isOverdue || isDueSoon`). Fertilizing/repotting-only reminders keep the plant-name title and no actions.
- When watering is due, the title is "Check {plant}" (`notification_check_title`). The action row is **fixed, whatever the overdue-ness** (#586): varying buttons between firings costs more than it gains. It has two actions:
  - **Watered** reuses the body-tap deep link. It writes no reason, which is correct: a reminder fires at or after the due date, so it is never early, and a late one is safely excluded from learning (`rules/schedule.md`).
  - **Not now** (`SkipWateringReceiver`, label `reschedule_watering_title`, shared with Plant Detail) writes `wateringDueDateOverride = maxOf(override ?: now, now) + 1 day`, so a stale past override still clears "due" in one tap (#741).
- "Not now" is **never a learning signal**: it touches only the override, never confidence or either interval. `SkipWateringReceiverTest` pins this (product ADR-0007/0027/0029). Its logic is `internal suspend fun skipWatering(context, plantId)` outside `goAsync()`, and it guards on `intent.action` (#178).
- "Still moist" no longer exists anywhere (#738). Rescheduling to another date happens in-app.

## Photo reminder (DataStore-only)
- `PHOTO_REMINDER_ENABLED` toggle; pure logic in `domain/reminder/PhotoReminderPolicy` (`shouldShowPhotoReminder`, `lastPhotoDaysSince`, `PHOTO_REMINDER_INTERVAL_DAYS`, session dedup `shownThisSession`).
- It fires once per session per plant when the newest photo is ≥ 30 days old (or the plant is ≥ 30 days old with no photos). Triggers: opening Plant Detail, and each Plant List quick-log.
- The shared `PhotoReminderDialog` is suppressed while an interval-suggestion dialog shows (#233/#407/#410/#416).

## Post-watering standing-water reminder (#519, product ADR-0036)
- **Scheduling:** every successful **current-day** WATER insert schedules a unique `OneTimeWorkRequest` 30 minutes later; `REPLACE` debounces to the latest watering. All WATER paths (quick-water, bulk, liquid-fertilizer paired WATER) use the same callback. Bulk logging schedules once, after its transaction commits. Backdated logs, edits and rejected duplicates never schedule.
- **Setting:** `post_watering_reminder_enabled` (default on) is gated by the master notifications switch and backed up (schema v16). Turning either off cancels pending work and clears both presentations; the worker rechecks both.
- **At fire time** (mutually exclusive):
  - Foreground: write the device-local `post_watering_reminder_pending_at` token (never backed up) and show one global dismissible modal.
  - Background: post ID `-2` with no actions. Its tap opens Plant List with an in-memory `CARED_FOR_TODAY` sort that never overwrites the stored sort.
  - Notification permission gates only the background path.
- Composition lives in `PostWateringReminderNotificationComposer`. Developer mode's **Show drain-water reminder now** writes the modal token directly.

## Tests
- Composer and worker: `ReminderNotificationComposerTest`, `ReminderWorkerTest` (Robolectric).
- Scheduling and helpers: `ReminderSchedulerTest`, `BootReceiverTest`, `NotificationHelperTest`, `PhotoReminderTest`.
- Post-watering reminder: the `PostWateringReminder*Test` set (composer, scheduler, presentation, worker, dialog).
