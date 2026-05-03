# YAPT – Active Plan

## Status: Feature development phase (as of 2026-05-03)

---

## Completed

- [x] Plant library (add / edit / delete with cover photo)
- [x] Care logging (WATER, FERTILIZE, PRUNE, MIST, REPOT, NOTE, PHOTO)
- [x] Adaptive watering intervals (feedback → Snackbar suggestion)
- [x] Care history timeline per plant
- [x] Photo gallery (horizontal scroll of care-log photos)
- [x] Stats row (days since watering/fertilizing, total logs)
- [x] Room/location grouping with filter chips on home screen
- [x] Daily care reminders via WorkManager (fires at user-configured time)
- [x] BootReceiver honours stored reminder time
- [x] Settings screen — notifications toggle + time picker dialog (PR #15, issue #10)
- [x] DataStore preferences
- [x] Nature-themed Material 3 light + dark theme
- [x] GitHub Actions CI/CD (debug APK on push to `main`, `develop`, `claude/**`; release APK on `main`)
- [x] Consistent debug keystore committed to repo — in-place APK upgrades work (PR #17)
- [x] README

---

## Upcoming features (GitHub issues)

| # | Feature | Priority |
|---|---|---|
| [#19](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/19) | Quick water / fertilize buttons on plant list | P1 |
| [#20](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/20) | Custom date on care log + edit existing entries | P1 |
| [#21](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/21) | Sort and filter options on plant list | P2 |
| [#18](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/18) | Watering history line chart in plant detail | P2 |
| [#22](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/22) | Local backup and restore (export / import) | P2 |

---

## Open bug / tech debt issues

| # | Description | Severity |
|---|---|---|
| [#2](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/2) | Fertilizing reminders silently skipped in ReminderWorker | Bug |
| [#3](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/3) | StatsRow computes days-since-fertilized inline instead of DateUtils | Bug |
| [#5](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/5) | Enum valueOf() throws on unknown DB value | Bug |
| [#4](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/4) | Release build has minification disabled | Enhancement |
| [#6](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/6) | PhotoGallery takes full CareLog list instead of just URIs | Enhancement |
| [#7](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/7) | All reminders share one notification ID | Enhancement |
| [#8](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/8) | fallbackToDestructiveMigration → explicit migrations | Tech debt |
| [#9](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/9) | No unit tests for CareSchedule | Testing |

---

## Workflow (for every new feature)

1. Update this file — move item from "Upcoming" to "Completed" when done
2. Update `CLAUDE.md` "What's Been Completed" and "Known Issues" to match
3. One branch per feature: `claude/<kebab-description>` off `develop`, PR targets `develop`
