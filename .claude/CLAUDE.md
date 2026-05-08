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
| [#4](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/4) | Release build has minification disabled | Enhancement |
| [#6](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/6) | PhotoGallery takes full CareLog list instead of just URIs | Enhancement |
| [#7](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/7) | All reminders share one notification ID | Enhancement |
| [#8](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/8) | fallbackToDestructiveMigration should become explicit migrations | Tech debt |
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
3. **Review** (`reviewer` agent) — max 2 rounds of REQUEST CHANGES:
   - Each finding is labelled **BLOCKING** (must fix) or **NON-BLOCKING** (filed as a new GitHub issue)
   - After round 2 the reviewer must APPROVE; remaining concerns become new GitHub issues
   - **Posts full findings as a comment on the PR after every round**
4. **QA** (`qa` agent) — validates build, tests, lint, and every acceptance criterion from the spec
   - **Posts full QA output as a comment on the PR**
5. **Update docs** — implementer updates `active-plan.md` and this file to reflect completion
6. **Merge** — **human merges only**; Claude never merges a PR

After review + QA complete, the orchestrating Claude instance posts a combined summary to the user **and** to the PR comment thread.

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
| `git checkout main` | **Forbidden** — blocked by `settings.local.json` |
| `git push --force` / `git push -f` | **Forbidden** — blocked by `settings.local.json` |
| `git push origin main` / `git push origin develop` | **Forbidden** — blocked by `settings.local.json` |
| `git reset --hard` | **Forbidden** — blocked by `settings.local.json` |
| `gh pr merge` or merging PRs any other way | **Forbidden** — human merges only |

When a prompt appears for `git checkout develop`, it is intentional — approve it when the agent needs to refresh `develop` before branching.

---

## What's Been Completed

- Full Room database (PlantEntity, CareLogEntity, DAOs, migrations via `fallbackToDestructiveMigration` for v1)
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
- Sort-order controls on plant list screen: Sort IconButton (left of Settings) opens a DropdownMenu with four options (Alphabetical, Watering due, Fertilizing due, Recently added); active option highlighted bold + primary colour; Alphabetical and due-date sorts are toggleable ASC/DESC with direction indicator in label; Recently added is always newest-first (no toggle); sort applied after room filter; session-only, resets to Recently added on cold start (PR #76, issue #21)
