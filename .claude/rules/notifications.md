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
Daily at the user-configured time; cancels every active YAPT notification except the independent post-watering
notification (`-2`) before posting (self-heals if the user switched modes; technical ADR-0026). One notification
per overdue/due-soon plant (ID = `plant.id.toInt()`); body = care items joined with `" · "`.
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
`SkipWateringReceiver` handles the notification action (+1 day override, unchanged; labelled "Not now" since
#586's `check_reminders` reframe, now the only watering-due label since that flag graduated, #657); guards on `intent.action`
(#178). Its actual logic is pulled into an `internal suspend fun skipWatering(context, plantId)` outside
`goAsync()` for direct testability (mirrors `BootReceiver.rescheduleFromStoredPrefs`). Deliberately **not** a
learning signal (#570, product ADR-0027, reaffirmed by ADR-0029) — it only ever touches `wateringDueDateOverride`,
never `wateringConfidence`/`wateringIntervalDays`/`wateringBaseIntervalDays`; `SkipWateringReceiverTest` pins this
so it can't be wired up later by accident. The action's label string (`reschedule_watering_title`, was
`skip_watering_title`) is shared with the Plant Detail Reschedule button/dialog (`.claude/rules/plant-detail.md`) —
one rename covers both surfaces. The now-unregistered duplicate under `worker/SkipWateringReceiver.kt` (which
mutated `wateringIntervalDays` directly, contradicting product ADR-0007/ADR-0029) was deleted in #508.

## Check reminders (#570, product ADR-0027; `CHECK_REMINDERS` graduated #657)
The watering-due reminder is always a check-in prompt, not an instruction. Gated in
`ReminderWorker.postPlantNotification()` on `isWateringDue (= status.isOverdue || status.isDueSoon)` only — a
fertilizing/repotting-only reminder never reframes, since there's no "check the soil" action to offer it.
- **Not watering-due**: title = plant name, no action row.
- **Watering-due**: title becomes "Check {plant}" (`R.string.notification_check_title`); the action
  row is **fixed regardless of how overdue the plant is** (#586, product ADR-0030). It is two —
  **Watered** (reuses the same deep-link `PendingIntent` as tapping the notification body — a
  discoverability affordance, not a new code path) and **Not now** (`SkipWateringReceiver`, the same
  +1-day override write the pre-#570 "Reschedule watering" action used, relabelled) — narrowed from
  three by #738 (product ADR-0039), which drops the **Still moist** action entirely rather than
  reworking it: rescheduling now requires opening the app and using Plant Detail. This is safe from a
  *learning* standpoint: "Not now" writes only `wateringDueDateOverride` and never a model field. It
  computes `maxOf(wateringDueDateOverride ?: now, now) + 1 day` — anchored to whichever is later, the
  existing override or now — so a tap always moves the due date to at least one day past today,
  regardless of any stale past override (#741; mirrors Plant Detail's
  `rescheduledRelativeDueAt()` anchor). **History:** before #741's fix, the arithmetic was
  `(wateringDueDateOverride ?: now) + 1 day`, anchored to the existing override alone — on a plant with
  a stale past override, a tap advanced that stale date by only one day and could leave the plant still
  overdue, requiring several taps to clear. An earlier draft of ADR-0039 asserted the opposite (that
  "Not now" always clears "due"); that claim was corrected before merge and is now true again with this
  fix. Varying the
  remaining action set by overdue-ness is still rejected, for the same reason as before: unpredictable
  buttons between firings cost more than the one attribution the fixed set gives up. A reminder fires
  at or after the due date, so a notification watering is never *early* — **Watered** therefore writes
  no reason at all, which is correct on schedule and the safe exclusion when late (see
  `.claude/rules/schedule.md`). The "I watered late *because* it was dry" attribution stays available
  in-app. `StillMoistReceiver`, its test, `ACTION_STILL_MOIST`, `stillMoistPendingIntent()`, and
  `R.string.notification_action_still_moist` are all deleted. Technical ADR-0007's note about distinct
  `PendingIntent` request codes for the Still-moist/Skip-watering pair is moot now that receiver is
  gone.
- `CareType.CHECK` entries are explicitly excluded from `WateringHistoryChart`'s data series/marker-color map
  (`computeCareEventMarkers()`, see `.claude/rules/chart.md`). Per product ADR-0039 (#738) the CHECK
  write path is retired along with the reschedule reason prompt — no new CHECK logs are written at
  all. Existing rows are hidden from Plant Detail's care-history list by a display filter — see
  `.claude/rules/watering-transparency.md` for the `watering_adjustments`-side posture, which stays
  unfiltered.

## Photo reminder (DataStore-only, no DB migration)
`PHOTO_REMINDER_ENABLED` toggle. Pure logic in `domain/reminder/PhotoReminderPolicy`
(`shouldShowPhotoReminder`, `lastPhotoDaysSince`, `PHOTO_REMINDER_INTERVAL_DAYS`, session dedup
`shownThisSession`). Fires once-per-session-per-plant when the newest photo is ≥ 30 days old (or plant ≥ 30 days
with no photos), on PlantDetail open and after each quick-log on PlantList (#233/#407/#410/#416). Shared
`PhotoReminderDialog` in `ui/components/`; suppressed while an interval-suggestion dialog is showing.

## Post-watering standing-water reminder (#519, product ADR-0036)
Every successfully inserted current-day WATER log schedules one unique `OneTimeWorkRequest` for 30 minutes later.
`ExistingWorkPolicy.REPLACE` debounces a watering round to the latest WATER; bulk logging waits until its Room
transaction commits and schedules once. Full Add Care Log, quick-water, bulk, and liquid-fertilizer paired-WATER
paths all route through the same callback. Backdated logs, edits, and rejected duplicates never schedule.
- Settings key `post_watering_reminder_enabled`, default `true`, is gated by master notifications and round-trips
  through backup schema v16. Turning either switch off cancels pending work and clears both presentations; the worker
  rechecks both switches.
- At fire time, a foreground app writes the device-local `post_watering_reminder_pending_at` DataStore token and shows
  one global dismissible modal. A background app clears stale modal state and posts generic notification ID `-2` on the
  plant-care channel. Android notification permission gates only the background path. The two paths are mutually
  exclusive per firing (product ADR-0036).
- The background notification has no actions. Tap opens PlantList with an in-memory `CARED_FOR_TODAY` sort that never
  overwrites the stored sort preference. The pending modal token is transient operational state and never backed up.
- Developer mode's **Show drain-water reminder now** action writes the modal token directly for no-wait manual testing.
- Pure eligibility/resource composition lives in `domain/notification/PostWateringReminderNotificationComposer`.
- Daily reminder cleanup explicitly preserves ID `-2` (technical ADR-0026, superseding technical ADR-0007's `cancelAll()`).

## Tests
`ReminderNotificationComposerTest` (both toggle branches), `ReminderWorkerTest` (Robolectric — denied/ due/ not-due
+ fertilizing-only suppression), `ReminderSchedulerTest`, `BootReceiverTest`, `NotificationHelperTest`,
`PhotoReminderTest`, `PostWateringReminderNotificationComposerTest`, `PostWateringReminderSchedulerTest`,
`PostWateringReminderPresentationTest`, `PostWateringReminderWorkerTest`, and `PostWateringReminderDialogTest`.
