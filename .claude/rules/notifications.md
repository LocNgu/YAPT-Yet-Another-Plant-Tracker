---
description: ReminderWorker, notification composer, and reminder/notification toggles
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/worker/**"
  - "app/src/main/kotlin/com/yapt/planttracker/notification/**"
  - "app/src/main/kotlin/com/yapt/planttracker/domain/notification/**"
  - "app/src/main/kotlin/com/yapt/planttracker/domain/reminder/**"
  - "app/src/test/**/{worker,notification,reminder}/**"
---

# Notifications & Reminders rules

## ReminderWorker (WorkManager, REPLACE policy — technical ADR-0010)
Daily at the user-configured time; **always `cancelAll()` first** (self-heals if the user switched modes), then
posts. One notification per overdue/due-soon plant (ID = `plant.id.toInt()`); body = care items joined with `" · "`.
No-ops when POST_NOTIFICATIONS is denied. Deep-link: tap → `MainActivity` `plantId` extra → PlantDetail (#7).
- Default reminder time (hour 9, minute 0) is written to DataStore on first launch so `?: 9` fallbacks never
  silently re-anchor the schedule (#356). Lives in `SettingsDefaults.REMINDER_HOUR`/`MINUTE`, not magic numbers.
- `MainActivity` passes the **stored** hour/minute to `ReminderScheduler.schedule()` on every launch (#394).
- `BootReceiver` reschedules from stored prefs via an `internal suspend fun` pulled out of `goAsync()` so it's testable.

## Pure composer (`domain/notification/ReminderNotificationComposer`, JVM-testable, no Context)
`computeCareReminderItems` / `computeDueReminders` decide due/not-due and per-plant care-item composition;
`ReminderWorker` turns `CareReminderItem`s into localized strings.
- **Combine toggle** (`combine_notifications`, default `false`) — when on, one count-only notification under fixed
  `COMBINED_NOTIFICATION_ID = -1` (plant ids are always positive), lands on PlantList, no Skip-watering action.
  See product ADR-0020 (documents the dropped per-plant Skip in combined mode) (#474).
- **"Notify for fertilizing"** (`fertilizing_notifications_enabled`, default `true`) — when off, drops
  fertilizing-only reminders via `List<CareReminderItem>.hasWateringItem()`; a watering-due plant keeps its
  fertilizing line; liquid-fertilizer plants never make a fertilizing-only reminder. Product ADR-0021 (#223).
- **Liquid-fertilizer** plants: "Fertilize with watering" appended to the watering alert, no separate notification (#56).

## Skip watering
`SkipWateringReceiver` handles the notification action (+1 day override); guards on `intent.action` (#178).

## Photo reminder (DataStore-only, no DB migration)
`PHOTO_REMINDER_ENABLED` toggle. Pure logic in `domain/reminder/PhotoReminderPolicy`
(`shouldShowPhotoReminder`, `lastPhotoDaysSince`, `PHOTO_REMINDER_INTERVAL_DAYS`, session dedup
`shownThisSession`). Fires once-per-session-per-plant when the newest photo is ≥ 30 days old (or plant ≥ 30 days
with no photos), on PlantDetail open and after each quick-log on PlantList (#233/#407/#410/#416). Shared
`PhotoReminderDialog` in `ui/components/`; suppressed while an interval-suggestion dialog is showing.

## Tests
`ReminderNotificationComposerTest` (both toggle branches), `ReminderWorkerTest` (Robolectric — denied/ due/ not-due
+ fertilizing-only suppression), `ReminderSchedulerTest`, `BootReceiverTest`, `NotificationHelperTest`,
`PhotoReminderTest`.
