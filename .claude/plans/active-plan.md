# YAPT – Active Plan

## Status: Feature development phase (as of 2026-05-10)

---

## Completed

- [x] Plant library (add / edit / delete with cover photo)
- [x] Care logging (WATER, FERTILIZE, PRUNE, MIST, REPOT, NOTE, PHOTO)
- [x] Adaptive watering intervals (feedback → AlertDialog suggestion with editable TextField; PR #150, issue #138)
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
- [x] Fix #4: enable R8 minification (`isMinifyEnabled = true`) and resource shrinking (`isShrinkResources = true`) on the release build type; add WorkManager Worker/CoroutineWorker keep rules to proguard-rules.pro (PR #106)
- [x] Fix #6: PhotoGallery parameter changed from `List<CareLog>` to `List<String>` (photoUris); call site derives URIs via `mapNotNull { it.photoUri }` (PR #107)
- [x] Watering history chart (PR #108, issue #18): bar chart visualization of watering intervals with time range selector (1/3/6/12 months), last watering date and average interval statistics, Material 3 theme integration using SageGreen and OkGreen; 8 unit tests for interval computation; Vico 2.0.0 M3 Compose dependency added
- [x] Fix #8: remove `fallbackToDestructiveMigration()` — Room hard-crashes on missing migration path; `1.json` baseline committed; future schema changes must ship with explicit `Migration` objects (PR #123)
- [x] Fix #135: add `imePadding()` to AddEditPlantScreen and AddCareLogScreen so the soft keyboard no longer obscures the Notes field; bottom Spacer reduced from 72 dp to 16 dp on both screens

---

## Upcoming features (GitHub issues)

| # | Feature | Priority |
|---|---|---|
| [#32](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/32) | Next watering / fertilizing countdown on plant detail | P1 |
| ~~[#21](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/21)~~ | ~~Sort and filter options on plant list~~ | P2 — merged |
| ~~[#18](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/18)~~ | ~~Watering history line chart in plant detail~~ | P2 — merged |
| [#29](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/29) | Larger plant images on overview and detail screens | P2 |
| [#31](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/31) | Snooze fertilizing reminder until next watering / auto-sync | P2 |
| [#33](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/33) | Skip watering / "too soon" action without logging a watering | P2 |

---

## Open bug / tech debt issues

| # | Description | Severity |
|---|---|---|
| [#7](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/7) | All reminders share one notification ID | Enhancement |
| ~~[#8](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/8)~~ | ~~fallbackToDestructiveMigration → explicit migrations~~ | ~~Tech debt~~ — merged PR #123 |
| [#9](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/9) | No unit tests for CareSchedule | Testing |
| [#16](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/16) | Upgrade dependencies: AGP, Kotlin, Gradle, Compose BOM, libraries | Tech debt |

---

## Workflow (for every new feature or bug fix)

1. **Spec** — run the spec agent; it interviews the human and posts clarifications as a comment on the GitHub issue
2. **Implement** — run the implementer agent; it reads the spec and writes code on a `claude/<kebab-description>` branch, then opens a PR targeting `develop`
3. **Review** — run the reviewer agent (iterative rounds of REQUEST CHANGES):
   - BLOCKING findings posted as inline PR review comments; NON-BLOCKING findings filed as new GitHub issues
   - After round 2 the reviewer escalates to the human with a recommendation instead of auto-approving
4. **QA** — run the qa agent; it validates build, tests, lint, and every acceptance criterion (compact checklist comment)
5. **Merge** — human merges the PR; Claude never merges
6. **Update docs** — implementer updates this file (move to Completed) and `CLAUDE.md` (What's Been Completed + Known Issues)
