---
description: ReminderWorker, notification composer, and reminder/notification toggles
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/worker/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/notification/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/domain/notification/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/domain/reminder/**/*"
  - "app/src/test/**/{worker,notification,reminder}/**/*"
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
- **Custom reminders** — `CustomReminderOverdue(name, days)` / `CustomReminderDueToday(name)` `CareReminderItem`s,
  one per overdue/due-today reminder in `PlantCareStatus.customReminderStatuses`; the reminder's free-text `name`
  goes straight into the body (no icon/category), joined with the same `" · "` separator. `ReminderWorker` fetches
  each plant's reminders via `CustomReminderRepository.getRemindersForPlantOnce()` before calling `computeStatus()`.
  See technical ADR-0019 (#232).

## Reschedule watering (renamed from "Skip watering", #508, product ADR-0029)
`SkipWateringReceiver` handles the notification action (+1 day override, unchanged; labelled "Not now" in the
`check_reminders` branch since #586); guards on `intent.action`
(#178). Its actual logic is pulled into an `internal suspend fun skipWatering(context, plantId)` outside
`goAsync()` for direct testability (mirrors `BootReceiver.rescheduleFromStoredPrefs`). Deliberately **not** a
learning signal (#570, product ADR-0027, reaffirmed by ADR-0029) — it only ever touches `wateringDueDateOverride`,
never `wateringConfidence`/`wateringIntervalDays`/`wateringBaseIntervalDays`; `SkipWateringReceiverTest` pins this
so it can't be wired up later by accident. The action's label string (`reschedule_watering_title`, was
`skip_watering_title`) is shared with the Plant Detail Reschedule button/dialog (`.claude/rules/plant-detail.md`) —
one rename covers both surfaces. The now-unregistered duplicate under `worker/SkipWateringReceiver.kt` (which
mutated `wateringIntervalDays` directly, contradicting ADR-0007/ADR-0029) was deleted in #508.

## Check reminders (#570, product ADR-0027)
`FeatureFlagRegistry.CHECK_REMINDERS` (`check_reminders`, default off) reframes the watering-due reminder from an
instruction to a check-in prompt. Gated in `ReminderWorker.postPlantNotification()` on `isWateringDue
(= status.isOverdue || status.isDueSoon)` **and** the flag — a fertilizing/repotting-only reminder never reframes,
even with the flag on, since there's no "check the soil" action to offer it.
- **Flag off** (or not watering-due): byte-for-byte identical to today — title = plant name, single "Reschedule
  watering" action.
- **Flag on, watering-due**: title becomes "Check {plant}" (`R.string.notification_check_title`); the single
  "Reschedule watering" action is replaced by **three, fixed regardless of how overdue the plant is** (#586,
  product ADR-0030): **Watered** (reuses the same deep-link `PendingIntent` as tapping the notification body — a
  discoverability affordance, not a new code path), **Still moist** (`StillMoistReceiver`), and **Not now**
  (`SkipWateringReceiver`, the same +1-day override write as the flag-off action, relabelled). Varying the action
  set by overdue-ness was rejected: unpredictable buttons between firings cost more than the one attribution the
  fixed set gives up. A reminder fires at or after the due date, so a notification watering is never *early* —
  **Watered** therefore writes no reason at all, which is correct on schedule and the safe exclusion when late
  (see `.claude/rules/schedule.md`). The "I watered late *because* it was dry" attribution stays available in-app.
  Since #508/#586, Still moist is also reachable from Plant Detail as the Reschedule prompt's "Soil still moist"
  answer, through the same `QuickLogUseCase.recordStillMoistCheck()` call site.
- `StillMoistReceiver` mirrors `SkipWateringReceiver`'s no-dialog, single-tap shape exactly (same `goAsync()` +
  internal-suspend-fun-for-testability pattern), but delegates the actual work to
  `QuickLogUseCase.recordStillMoistCheck(plant, newDueAtMillis)` rather than touching repositories directly — that's the one choke
  point that also owns the CHECK same-day dedupe guard (`isDuplicateGuarded()`). It writes a `CareType.CHECK` log
  (`wateringFeedback = TOO_SOON`), **sets** `wateringDueDateOverride` to a caller-supplied date — the receiver
  passes `now + suggestedStillMoistDeferralDays(plant)`, since it has no picker, replacing #570's flat +1 day that
  could never clear "due" for a plant overdue by two or more days (#586) — and unconditionally (`ADAPTIVE_WATERING`
  graduated, #655) feeds the observation into
  `CareSchedule.computeAdaptiveInterval()` (see `.claude/rules/schedule.md`), persisting only the resulting
  `wateringConfidence`, never a silent interval change.
- Notification IDs/`PendingIntent` request codes stay `plant.id.toInt()` for the Still-moist action too (technical
  ADR-0007) — a different target `BroadcastReceiver` component already makes it distinct from the Skip-watering
  `PendingIntent` even sharing that request code, so no new ID scheme was needed.
- `CareType.CHECK` entries are explicitly excluded from `WateringHistoryChart`'s data series/marker-color map
  (`computeCareEventMarkers()`, see `.claude/rules/chart.md`) but stay visible in the plain care-history list.

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
