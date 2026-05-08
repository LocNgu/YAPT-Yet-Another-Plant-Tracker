# YAPT – Active Plan

## Status: Feature development phase (as of 2026-05-04)

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
- [x] Custom date on care log + edit existing care log entries (PR #28, issue #20)
- [x] Fix #2: include fertilizing-overdue plants in care reminders (PR #24)
- [x] Fix #3: use DateUtils.formatRelative for last-fertilized in StatsRow (PR #25)
- [x] Fix #5: guard CareType / WateringFeedback valueOf against unknown DB values (PR #23)
- [x] README
- [x] Local backup and restore — export/import .yapt ZIP (PR for issue #22)
- [x] Fix #30: default watering feedback chip to JUST_RIGHT (PR #42)
- [x] Fix #37: navigate to PlantList with Snackbar after successful restore
- [x] Tests #9c: ViewModel unit tests with MockK + coroutines-test + turbine (PR #62, issue #48)
- [x] Tests #9e: BackupManager instrumented integration tests (PR #67, issue #50)
- [x] Tests #9f: Compose/UI screen tests (instrumented) (PR #70, issue #51)
- [x] Quick water/fertilize buttons on plant list: compact WaterDrop + Spa IconButtons on each PlantCard; `PlantListViewModel.quickLog()` inserts a CareLog and emits a SharedFlow Snackbar event; list reorders correctly after quick-log; scroll position stays fixed by pixel offset (LazyColumn without key); unit tests for quickLog WATER + FERTILIZE (PR #82, issue #19)

---

## Upcoming features (GitHub issues)

| # | Feature | Priority |
|---|---|---|
| [#32](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/32) | Next watering / fertilizing countdown on plant detail | P1 |
| ~~[#21](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/21)~~ | ~~Sort and filter options on plant list~~ | P2 — merged |
| [#18](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/18) | Watering history line chart in plant detail | P2 |
| [#29](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/29) | Larger plant images on overview and detail screens | P2 |
| [#31](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/31) | Snooze fertilizing reminder until next watering / auto-sync | P2 |
| [#33](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/33) | Skip watering / "too soon" action without logging a watering | P2 |

---

## Open bug / tech debt issues

| # | Description | Severity |
|---|---|---|
| [#4](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/4) | Release build has minification disabled | Enhancement |
| [#6](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/6) | PhotoGallery takes full CareLog list instead of just URIs | Enhancement |
| [#7](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/7) | All reminders share one notification ID | Enhancement |
| [#8](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/8) | fallbackToDestructiveMigration → explicit migrations | Tech debt |
| [#9](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/9) | No unit tests for CareSchedule | Testing |
| [#16](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/16) | Upgrade dependencies: AGP, Kotlin, Gradle, Compose BOM, libraries | Tech debt |

---

## Workflow (for every new feature or bug fix)

1. **Spec** — run the spec agent; it interviews the human and posts clarifications as a comment on the GitHub issue
2. **Implement** — run the implementer agent; it reads the spec and writes code on a `claude/<kebab-description>` branch, then opens a PR targeting `develop`
3. **Review** — run the reviewer agent (max 2 rounds of REQUEST CHANGES):
   - BLOCKING findings must be fixed; NON-BLOCKING findings are filed as new GitHub issues
   - After round 2 the reviewer must APPROVE; remaining concerns become new issues
4. **QA** — run the qa agent; it validates build, tests, lint, and every acceptance criterion
5. **Merge** — human merges the PR; Claude never merges
6. **Update docs** — implementer updates this file (move to Completed) and `CLAUDE.md` (What's Been Completed + Known Issues)
