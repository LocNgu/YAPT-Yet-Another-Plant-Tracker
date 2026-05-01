# YAPT – Active Plan

## Status: Post-Initial-Development

The initial implementation is complete and pushed to `claude/android-plant-tracker-qpjzl`. A PR review identified 8 issues; the critical reminder-time bug was fixed immediately. The remaining issues are tracked below.

---

## Completed Features

- [x] Plant library (add / edit / delete with cover photo)
- [x] Care logging (WATER, FERTILIZE, PRUNE, MIST, REPOT, NOTE, PHOTO)
- [x] Adaptive watering intervals (Too Soon / Just Right / Too Late → Snackbar suggestion)
- [x] Care history timeline per plant
- [x] Photo gallery (horizontal scroll of care-log photos)
- [x] Stats row (days since watering/fertilizing, total logs)
- [x] Room/location grouping with filter chips on home screen
- [x] Daily care reminders via WorkManager (fires at user-configured time)
- [x] Notification channel + permission check
- [x] BootReceiver honours stored reminder time
- [x] Settings screen (notifications toggle, reminder time display)
- [x] DataStore preferences
- [x] Nature-themed Material 3 light + dark theme
- [x] GitHub Actions CI/CD (debug + release APKs)
- [x] README

---

## Next Up (Prioritized)

### P1 — Bug fixes (open issues)

1. **#5 Enum valueOf crash** — `CareType.valueOf()` and `WateringFeedback.valueOf()` throw on unrecognised DB values. Wrap with `runCatching` + fallback in `CareLogRepository`.

2. **#2 Fertilizing reminders** — `ReminderWorker` hard-codes `lastFertilizedAt = null`. Query last fertilizing log and include fertilizing-overdue plants in notifications.

3. **#3 StatsRow DateUtils** — Replace inline `(now - ts) / 86_400_000` with `DateUtils.formatRelative()` in `StatsRow.kt`.

### P2 — UX improvements

4. **Settings: Add time picker** — The reminder time is displayed but not editable. Add a `TimePickerDialog` triggered by tapping the reminder time row. Wire the result to `viewModel.setReminderTime(hour, minute)`.

5. **#7 Notification grouping** — Replace the single-ID notification with `NotificationCompat.InboxStyle` or `BigTextStyle` so multiple overdue plants don't collapse into one.

6. **#6 PhotoGallery signature** — Change parameter from `List<CareLog>` to `List<String>` (pre-filtered URIs). Minor refactor, filter at call site in `PlantDetailScreen`.

### P3 — Quality / pre-release

7. **#9 Unit tests for CareSchedule** — Add `CareScheduleTest.kt` covering `computeSuggestedInterval` (all three feedback values, clamping to 1) and `computeStatus` (overdue, due-soon, not due, no interval).

8. **#4 Release minification** — Enable `isMinifyEnabled = true` + `isShrinkResources = true` in release build type. Add ProGuard keep rules for Room entities, WorkManager workers, DataStore.

9. **#8 Room migrations** — Before the next schema change, replace `fallbackToDestructiveMigration()` with an explicit `Migration` object. Commit the exported schema JSON from `app/schemas/`.

---

## Open Questions / Blockers

- **Time picker UI**: Use Material 3 `TimeInput` composable (available in M3 1.1+) or a classic `TimePickerDialog` from `android.app`? M3 `TimePicker` is the on-brand choice.
- **Notification scope**: Should fertilizing reminders be a separate notification from watering reminders, or combined into one "plants need care" notification?
- **Search**: No search/filter beyond room chips. Worth adding a search bar on the plant list?
- **Plant deletion confirmation**: Currently shows an `AlertDialog` — UX is functional but could be improved with a slide-to-delete gesture on the plant list.
- **Schema version**: Currently version 1 with `fallbackToDestructiveMigration`. Any schema change requires a migration before shipping to users.

---

## Workflow (for every new feature going forward)

1. Update this file with the feature under "Next Up"
2. Assign to **implementer** subagent for coding
3. Route completed code to **reviewer** subagent for sign-off
4. Route to **qa** subagent to validate behaviour
5. Commit only after reviewer signs off; update this file to move feature to "Completed"
