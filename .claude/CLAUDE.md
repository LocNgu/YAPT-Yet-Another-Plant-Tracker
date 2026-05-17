# YAPT – Yet Another Plant Tracker

## Purpose & Target Users

An offline-first Android app for houseplant owners who want to track care history without cloud accounts, subscriptions, or data collection. Users log waterings, fertilizing, pruning, and other care events; the app surfaces overdue reminders and suggests adjusted watering intervals based on the user's own feedback.

---

## Tech Stack

| Layer | Choice | Why |
|---|---|---|
| Language | Kotlin | Standard Android |
| UI | Jetpack Compose + Material 3 | Declarative, nature-themed palette |
| Architecture | MVVM + Repository | Clear separation, testable ViewModels |
| Database | Room (SQLite) | Offline-first, type-safe queries |
| DI | Manual (Application singletons) | No Hilt — keeps the build simple |
| Navigation | Compose Navigation | Type-safe routes via `Screen` sealed class |
| Images | Coil 2 | Async image loading in Compose |
| Reminders | WorkManager + NotificationManager | Survives process death |
| Preferences | DataStore | Async, coroutine-friendly settings |
| Build | AGP 8.7.3, Kotlin 2.0.21, KSP 2.0.21-1.0.28 | |
| Compose BOM | 2024.11.00 | Aligns all Compose artifact versions |

---

## Folder Structure

```
app/src/main/kotlin/com/yapt/planttracker/
├── data/
│   ├── db/           PlantDao, CareLogDao, PlantDatabase (singleton)
│   ├── entity/       PlantEntity, CareLogEntity  (Room @Entity classes)
│   └── repository/   PlantRepository, CareLogRepository
│                     (entity ↔ domain mapping via extension functions)
├── domain/
│   ├── model/        Plant, CareLog, CareType, WateringFeedback,
│   │                 PlantCareStatus
│   └── schedule/     CareSchedule  (pure business logic — overdue + adaptive interval)
├── notification/     NotificationHelper (channel creation)
├── ui/
│   ├── components/   PlantCard, CareLogItem, PhotoGallery, StatsRow, PlantPhoto
│   ├── navigation/   Screen (sealed class), NavGraph (NavHost)
│   ├── screens/      plantlist/, addplant/, plantdetail/, addcarelog/, settings/
│   └── theme/        Color.kt, Theme.kt, Type.kt
├── util/             DateUtils, ImageUtils
└── worker/           ReminderWorker, ReminderScheduler, BootReceiver
```

---

## Architecture Decisions

### Manual DI via Application singletons
`YaptApplication` creates `PlantDatabase`, `PlantRepository`, and `CareLogRepository` as lazy properties. `NavGraph` receives the Application instance and passes repositories into each ViewModel factory. No Hilt to avoid annotation processing complexity on a small app.

### ViewModel Factory pattern
Every ViewModel has an inner `Factory` class. Compose screens obtain them via `viewModel(factory = Vm.Factory(...))`. This keeps the injection explicit and doesn't require a DI framework.

### Entity ↔ Domain mapping
`PlantRepository` and `CareLogRepository` contain `toEntity()` / `toDomain()` extension functions. The UI never touches Room entities directly.

### Suspend in Flow `combine` block
`PlantListViewModel.buildStatus()` is a `suspend` function called inside a `combine {}` block. Kotlin's `List.map {}` takes a non-suspend lambda, so the code uses a `for` loop with `mutableListOf` to accumulate results. Do not refactor to `.map {}` without making it suspendable.

### Adaptive watering interval
After saving a WATER log, `AddCareLogViewModel` queries the last two waterings to compute `actualIntervalDays`, applies `CareSchedule.computeSuggestedInterval(feedback, actualDays)`, and passes the result back to `PlantDetailScreen` via `savedStateHandle["suggestedWateringInterval"]`. The detail screen shows a Snackbar with an "Apply" action.

### DataStore delegate
`val Context.settingsDataStore by preferencesDataStore(name = "settings")` is declared at **file top-level** in `YaptApplication.kt` (not inside the class). This is required by the AndroidX DataStore API.

### WorkManager scheduling
`ReminderScheduler.schedule(context, hour, minute)` computes an `initialDelay` to the next occurrence of the user's configured time, then enqueues a 24-hour `PeriodicWorkRequest` with policy `REPLACE` (so changing the time takes effect immediately). `BootReceiver` uses `goAsync()` to read stored preferences before rescheduling.

---

## Patterns & Conventions

- **StateFlow** for UI state; **SharedFlow** for one-shot events (interval suggestions)
- **`collectAsStateWithLifecycle()`** in all Compose screens (not `collectAsState()`)
- **Room schema location** exported to `app/schemas/` via KSP arg — commit schema JSON files when bumping DB version
- **Nature-themed palette**: SageGreen `#6B8F71` primary, WarmCream `#F5F0E8` background, EarthBrown `#795548` tertiary; status colours OkGreen / WarnOrange / OverdueRed defined in `Color.kt`
- **`DateUtils.formatRelative()`** for all date display — never compute `(now - ts) / 86_400_000` inline
- **Enums stored as String** in Room — use `runCatching { Enum.valueOf(...) }.getOrDefault(fallback)` when reading, not plain `.valueOf()`
- **No libs.versions.toml** — dependency versions are inlined in `app/build.gradle.kts`; the Compose BOM handles Compose artifact versions

---

## Known Issues / Technical Debt

Tracked as GitHub issues:

| # | Description | Severity |
|---|---|---|
| [#7](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/7) | All reminders share one notification ID | Enhancement |
| [#16](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/16) | Upgrade dependencies: AGP, Kotlin, Gradle, Compose BOM, libraries | Tech debt |
| [#35](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/35) | BackupManager: photo files written before Room transaction (orphaned on failure) | Enhancement |
| [#36](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/36) | BackupManager export: N+1 Flow query per plant | Enhancement |
| [#38](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/38) | Backup error message grammar ("not compatible File") | Enhancement |
| [#39](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/39) | Hardcoded UI strings in backup/restore | Enhancement |
| [#40](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/40) | Dangling zip-path URI on partial photo export failure | Enhancement |
| [#41](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/41) | ReminderWorker not rescheduled after backup restore | Enhancement |

---

## Development Workflow

Every feature and bug fix follows these steps in order:

1. **Spec** (`spec` agent) — interviews the human, resolves ambiguities, posts clarifications as a comment on the GitHub issue
2. **Implement** (`implementer` agent) — reads the spec, writes code, opens a PR targeting `develop`
3. **Review** (`reviewer` agent) — iterative rounds of REQUEST CHANGES:
   - Each finding is labelled **BLOCKING** (must fix) or **NON-BLOCKING** (filed as a new GitHub issue)
   - BLOCKING findings are posted as **inline PR review comments** on the relevant lines
   - The PR review body is compact: verdict + counts only
   - After round 2, the reviewer **does not auto-approve** — it stops, posts a recommendation, and waits for the human to decide (another implementer round, manual approval, or other action)
4. **QA** (`qa` agent) — validates build, tests, lint, and every acceptance criterion from the spec
   - **Posts a compact checklist comment on the PR** (under 15 lines for a passing run)
5. **Update docs** — implementer updates `active-plan.md`, this file, `CHANGELOG.md` (`[Unreleased]` section), and `WhatsNewContent.kt` (user-facing release notes) to reflect completion
6. **Merge** — **human merges only**; Claude never merges a PR

After review + QA complete, the orchestrating Claude instance posts a brief summary to the user **and** to the PR comment thread.

## Git Workflow

**One branch and one PR per feature or bug fix.** Never mix unrelated changes on the same branch.

- Branch off `develop`: `git checkout -b claude/<kebab-description> origin/develop`
- All commits for the task go on that branch
- PR targets `develop`
- Go back to `develop` before starting anything new

Branch naming convention: `claude/<kebab-case-description>`
Examples: `claude/fix-reminder-scheduler`, `claude/in-place-apk-upgrade`, `claude/fix-5-enum-valueof`

---

## Autonomy & Permission Model

Agents and Claude operate under this permission model to minimise interruptions during normal feature-branch work. `settings.local.json` enforces the hard rules mechanically; instructions cover the rest.

| Action | Permission |
|--------|-----------|
| Read any file | Always allowed — no prompt |
| Read-only git (`status`, `log`, `diff`, `show`, `fetch`, `branch`, `remote`) | Always allowed — no prompt |
| `git add`, `git commit`, `git stash`, `git cherry-pick` | Always allowed — no prompt |
| `git checkout claude/*` / `git checkout -b claude/*` | Always allowed — no prompt |
| `git push origin claude/*` (any push to a `claude/` branch) | Always allowed — no prompt |
| `gh issue *`, `gh pr create/view/list/diff/checks/comment/ready`, `gh api *` | Always allowed — no prompt |
| `./gradlew *` (build, test, lint) | Always allowed — no prompt |
| `git checkout develop` | Requires permission — a prompt will appear |
| `git push origin develop` | Requires permission — a prompt will appear |
| `git checkout main` | **Forbidden** — blocked by `settings.local.json` |
| `git push --force origin claude/*` | Requires permission — a prompt will appear |
| `git push --force origin main` / `git push --force origin develop` | **Forbidden** — blocked by `settings.local.json` |
| `git push origin main` | **Forbidden** — blocked by `settings.local.json` |
| `git reset --hard` | **Forbidden** — blocked by `settings.local.json` |
| `gh pr merge` or merging PRs any other way | **Forbidden** — human merges only |

When a prompt appears for `git checkout develop`, `git push origin develop`, or `git push --force origin claude/*`, it is intentional — approve when appropriate.

---

## What's Been Completed

- Full Room database (PlantEntity, CareLogEntity, DAOs, explicit migrations required — hard-crash on missing migration path, baseline schema `1.json` committed)
- PlantRepository + CareLogRepository with domain mapping
- All domain models: Plant, CareLog, CareType, WateringFeedback, PlantCareStatus
- CareSchedule: `computeStatus()` and `computeSuggestedInterval()` pure functions
- All five screens: PlantList, AddEditPlant, PlantDetail, AddCareLog, Settings
- All reusable components: PlantCard, CareLogItem, PhotoGallery, StatsRow, PlantPhoto, CareTypeSelector
- WorkManager daily reminder with correct time alignment (post PR review fix)
- BootReceiver that honours stored reminder time
- DataStore preferences for notifications toggle + reminder time
- Nature-themed Material 3 dark/light theme
- Android PhotoPicker integration with `takePersistableUriPermission`
- GitHub Actions CI/CD (debug APK on push, release APK on main; triggers on `main`, `develop`, and `claude/**`)
- Consistent debug keystore committed to repo — in-place APK upgrades work across local and CI builds (PR #17)
- Settings time picker — reminder hour/minute configurable via Material 3 TimePicker dialog (PR #15, issue #10)
- Custom date on care log entry + edit existing care log entries (PR #28, issue #20)
- Fertilizing reminders fixed: include fertilizing-overdue plants in ReminderWorker (PR #24, issue #2)
- StatsRow uses `DateUtils.formatRelative` for last-fertilized display (PR #25, issue #3)
- CareType / WateringFeedback `valueOf()` guarded with `runCatching` against unknown DB values (PR #23, issue #5)
- README
- Local backup and restore — export/import `.yapt` ZIP via SAF, optional photo inclusion, settings round-trip, Room transaction wrapping full DB replace, and forward-compatibility warning dialog (PR #34, issue #22); new dep `kotlinx-serialization-json:1.6.3`; converter script at `scripts/convert_third_party_log.py`
- Default watering-feedback chip pre-selected to JUST_RIGHT on new WATER logs; resets to JUST_RIGHT when switching care type back to WATER (PR #42, issue #30)
- Fix #37: after successful restore, navigate to PlantList (clearing back-stack) and show a Snackbar with plant and log count
- Phase 1 unit tests: 18 CareSchedule tests + 11 DateUtils tests; `gradle-wrapper.jar` added to repo; JaCoCo coverage enabled on debug builds (PR #52, issue #46)
- Fix NavGraph `StateFlowValueCalledInComposition` lint error by wrapping `savedStateHandle` read/write in `LaunchedEffect`; fix `PermissionImpliesUnsupportedChromeOsHardware` by adding `<uses-feature camera required="false">` to AndroidManifest; CI now runs `testDebugUnitTest` + `lintDebug` on every push and PR (including PRs to `develop`) (PR #54, issue #53)
- Tests #9c: ViewModel unit tests for all 5 ViewModels (PlantList, PlantDetail, AddCareLog, AddEditPlant, Settings) using MockK + coroutines-test + turbine; MainDispatcherRule added (PR #62, issue #48); new test deps: mockk:1.13.12, coroutines-test:1.9.0, turbine:1.2.0
- Tests #9e: BackupManager instrumented integration tests — 9 androidTest cases covering export/import round-trips (with/without photos), empty DB, future schema warning + proceed, corrupt ZIP, missing backup.json, zip-slip containment, settings round-trip, and photo SHA-256 integrity (PR #67, issue #50); no new dependencies required
- Tests #9f: Compose/UI instrumented screen tests for all 5 screens using MockK + real ViewModels + createComposeRule(); SettingsScreenTest uses PreferenceDataStoreFactory with TemporaryFolder; no Thread.sleep anywhere (PR #70, issue #51)
- Countdown labels on plant list cards and compound StatChip on plant detail screen: `DateUtils.formatCountdown` returns "In X days" / "Due today" / "Overdue by X days"; PlantCard chips show countdown when a due date exists with OkGreen/WarnOrange/OverdueRed colour coding; StatsRow StatChip redesigned with icon + label header and `next:`/`last:` lines; "Fert" abbreviation fixed to "Fertilizing"; room chip removed from PlantCard; "Total logs" chip replaced by inline grey count next to "Care History" heading; 7 `formatCountdown` unit tests added (PR #74, issues #32 #55)
- Sort-order controls on plant list screen: Sort IconButton (left of Settings) opens a DropdownMenu with four options (Alphabetical, Watering due, Fertilizing due, Recently added); active option highlighted bold + primary colour; Alphabetical and due-date sorts are toggleable ASC/DESC with direction indicator in label; Recently added is always newest-first (no toggle); sort applied after room filter; default is Alphabetical A→Z; sort choice persists across restarts via DataStore (SORT_OPTION + SORT_ASCENDING keys) (PR #76, issue #21)
- Quick water/fertilize buttons on plant list: each PlantCard has compact WaterDrop + Spa IconButtons (20dp, onSurfaceVariant tint) in the bottom-right; tapping calls `PlantListViewModel.quickLog()` which inserts a CareLog (WATER: JUST_RIGHT feedback, FERTILIZE: no feedback) and emits a `SharedFlow<String>` Snackbar event; status badges refresh reactively via Room Flow; card body click still navigates to PlantDetail (PR #82, issue #19)
- CI: instrumented tests now run on PRs when `app/src/main/**`, `app/src/androidTest/**`, or `app/build.gradle.kts` change (path-filtered via `dorny/paths-filter`); concurrency group auto-cancels stacked runs on the same PR; always run on direct push to main/develop (PR #93, issue #87)
- Release build minification enabled: `isMinifyEnabled = true` and `isShrinkResources = true` on the release build type; WorkManager `Worker`/`CoroutineWorker` keep rules added to `proguard-rules.pro` (PR #106, issue #4)
- Fix #6: `PhotoGallery` parameter changed from `photoLogs: List<CareLog>` to `photoUris: List<String>`; `CareLog` import removed; null guard inside `items {}` removed; `PlantDetailScreen` derives URIs via `mapNotNull { it.photoUri }` at the call site (PR #107)
- Watering history chart on plant detail screen (PR #110, issue #18; supersedes unmerged PR #108): Vico line chart showing average days-between-watering per calendar month; time range chips 1M/3M/6M/12M/All with `TWELVE_MONTHS` default; statistics row shows last watering date and average interval. Empty months are omitted from the line series (no flat y=0 segment, and NaN y-values crash `LineCartesianLayer.updateMarkerTargets`); `CartesianLayerRangeProvider.fixed(0, totalMonths-1)` keeps every month in the chart's coordinate space so labels render for empty months too. Labels live in Vico's `ExtraStore` inside the same `runTransaction` as the data to keep label/data atomically consistent (duplicate "MMM" labels across year boundaries fall back to "MMM yy"). Horizontal scroll is enabled with `initialScroll = Scroll.Absolute.End` and `autoScroll = Scroll.Absolute.End` gated by an `AutoScrollCondition` that compares `maxX` of old vs new models, so the chart re-snaps to the right edge on range switches and new waterings but not on unrelated CareLog emissions. Empty state shows when no intervals exist (i.e. fewer than 2 watering logs). 11 unit tests for `computeWateringIntervals`. New dependency: `com.patrykandpatrick.vico:compose-m3:2.0.0`.
- Larger plant images (PR #119, issue #29): PlantCard photo is a 90 dp wide edge-to-edge strip filling card height, left corners rounded 12 dp. PlantDetailScreen uses a Box overlay pattern (no Scaffold): 280 dp hero bleeds behind status bar with scrim, transparent TopAppBar replaced by overlaid back/edit buttons with dark pill containers, `Surface(colorScheme.background)` root restores dark-mode text color, FAB/Snackbar anchored with `navigationBarsPadding()`.
- Unique notification IDs per plant (issue #7): `ReminderWorker` posts one notification per overdue/due-soon plant (ID = `plant.id.toInt()`); cancels all plant notifications at start of each run before re-posting; title = plant name, body = care items joined with " · " (e.g. "Watering overdue by 2 days · Fertilizing due today"); tapping deep-links to PlantDetailScreen via `MainActivity` intent extra `plantId`; `MainActivity` reads the extra in `onCreate` (when `savedInstanceState==null`) and `onNewIntent`, passes to `YaptNavGraph` which navigates via `LaunchedEffect`. No `NOTIFICATION_ID = 1001` constant — removed.
- "Water + Fertilize due" filter in sort dropdown (PR #121, issue #78): new `BOTH_DUE` SortOption filters the plant list to only plants where both watering AND fertilizing are due or overdue (`isOverdue || isDueSoon` for each); results sorted by `nextWateringDueAt` ascending (watering urgency takes priority); empty state shows "No plants need both watering and fertilizing right now." instead of the default copy; no direction toggle; persists via existing `SORT_OPTION` DataStore key with no new keys or DB changes.
- Fix #8: replaced `fallbackToDestructiveMigration()` with hard-crash behavior — Room now throws at startup if a DB version bump ships without an explicit `Migration` object; `app/schemas/.../1.json` baseline committed; future migrations must be registered via `.addMigrations(...)` before bumping `version` in `PlantDatabase`
- Fix #135: add `imePadding()` to AddEditPlantScreen and AddCareLogScreen scrollable Column modifier chains (after `verticalScroll`, before inner `padding(16.dp)`); bottom Spacer reduced from 72 dp to 16 dp on both screens so the soft keyboard no longer obscures the Notes field
- What's New bottom sheet (issue #147): `ModalBottomSheet` shown on first launch after each update (and fresh install); compares `BuildConfig.VERSION_CODE` vs. `LAST_SEEN_VERSION_CODE` in DataStore; content in `WhatsNewContent.kt` updated by implementer each PR; `buildConfig = true` enabled in build.gradle.kts
- Fix #136: `formatCountdown` returns `"Overdue"` (not `"Due today"`) when `diffMs < 0 && absDays == 0L` (overdue by less than 24 h), so the chip on PlantCard correctly shows red for any overdue timestamp; unit test updated to match
