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
| Build | AGP 8.13.2, Kotlin 2.1.21, KSP 2.1.21-2.0.2, Gradle 8.14.5 | |
| Compose BOM | 2026.05.01 | Aligns all Compose artifact versions |

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
After saving a WATER log, `AddCareLogViewModel` queries the last two waterings to compute `actualIntervalDays`, applies `CareSchedule.computeSuggestedInterval(feedback, actualDays, currentInterval)`, and passes the result back to `PlantDetailScreen` via `savedStateHandle["suggestedWateringInterval"]`. The detail screen shows a modal `AlertDialog` with a pre-filled editable `TextField`; the user can adjust the value before tapping Apply, or dismiss to discard. See product ADR-0006 (supersedes ADR-0005). `JUST_RIGHT` produces a suggestion when `actualIntervalDays != currentInterval`; `TOO_SOON` uses `currentInterval` as the base when the user watered early (`actual < stored`) so the suggestion extends beyond the stored interval.

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

### CHANGELOG.md
Format: [Keep a Changelog](https://keepachangelog.com/). File lives at repo root alongside `README.md`.
Implementer adds entries to `[Unreleased]` in every PR (dev workflow step 5).
Human promotes `[Unreleased]` → a versioned heading when cutting a release.

---

## Architecture Decision Records (ADRs)

Decisions are documented in `docs/decisions/`:
- **`product/`** — product and UX decisions (the primary set): when features appear, what defaults are, how the app behaves
- **`technical/`** — implementation constraints and framework choices

**When to consult ADRs:**
- **Spec agent**: before interviewing the human, scan `docs/decisions/product/` for ADRs relevant to the feature area. If the request contradicts an existing ADR, name the ADR and its rationale explicitly, and ask the human to confirm the new direction before proceeding.
- **Implementer agent**: before writing code in an area covered by a technical ADR, read the relevant file. Do not refactor patterns described in technical ADRs without a superseding decision.

**When a feature contradicts an ADR:** do not implement silently against the existing decision. Surface the conflict in the spec, name the ADR, state its rationale, and wait for the human to confirm. If confirmed, implement the new behaviour and write a new ADR that supersedes the old one. Old ADRs are never edited — the history stays intact.

---

## Known Issues / Technical Debt

Tracked as GitHub issues:

| # | Description | Severity |
|---|---|---|
| [#16](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/16) | Upgrade dependencies: AGP, Kotlin, Gradle, Compose BOM, libraries | Tech debt |
| [#170](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/170) | Skip watering button: replace `TextButton` with `ExtendedFloatingActionButton` bottom-start; fix stepper dialog alignment | Enhancement |
| [#175](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/175) | BackupManager: photo cleanup on failure can delete committed-DB files if exception fires after DB transaction but before ImportSuccess | Enhancement |
| [#178](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/178) | `SkipWateringReceiver` does not check `intent.action` before processing | Enhancement |
| [#179](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/179) | Hardcoded UI strings in skip dialog (`PlantDetailScreen`) | Tech debt |

---

## Development Workflow

Every feature and bug fix follows these steps in order:

1. **Spec** (`spec` agent) — scans `docs/decisions/product/` for ADRs relevant to the feature; surfaces any contradictions to the human before proceeding; interviews the human, resolves ambiguities, posts clarifications as a comment on the GitHub issue
2. **Implement** (`implementer` agent) — reads the spec, writes code, pushes a `claude/*` branch, and returns the PR title/body as text; the **orchestrating Claude instance** opens the PR targeting `develop` via `mcp__github__create_pull_request`
3. **Review** (`reviewer` agent) — iterative rounds of review:
   - Each finding is labelled **BLOCKING** (must fix) or **NON-BLOCKING** (filed as a new GitHub issue)
   - The reviewer agent returns findings as text; the **orchestrating Claude instance** posts them:
     - BLOCKING inline comments: (1) `mcp__github__pull_request_review_write` `create` (no `event`) → (2) `mcp__github__add_comment_to_pending_review` per finding → (3) `mcp__github__pull_request_review_write` `submit_pending` with `event: COMMENT`
     - NON-BLOCKING: filed as new GitHub issues via `mcp__github__issue_write`
   - The PR review body is compact: verdict + counts only
   - **GitHub constraint:** `APPROVE` and `REQUEST_CHANGES` are both blocked when the PR author and reviewer share the same GitHub account — always use `COMMENT` event
   - After round 2, the reviewer stops and waits for the human to decide (another implementer round, manual approval, or other action)
   - **Note:** `reviewer` subagents have read-only GitHub MCP tools (`issue_read`, `pull_request_read`) so they fetch the issue + PR themselves, but they cannot post — the orchestrator must post on their behalf
4. **QA** (`qa` agent) — validates build, tests, lint, and every acceptance criterion from the spec
   - The QA agent returns a compact checklist (under 15 lines for a passing run); the **orchestrating Claude instance** posts it to the PR using `mcp__github__add_issue_comment`
   - **Note:** `qa` subagents have read-only GitHub MCP tools (`issue_read`, `pull_request_read`) so they fetch the issue + PR themselves, but they cannot post — the orchestrator must post on their behalf
5. **Update docs** — implementer updates this file, `CHANGELOG.md` (`[Unreleased]` section), and `WhatsNewContent.kt` (user-facing release notes) to reflect completion (`chore:`/docs-only PRs with no user-visible change may omit the CHANGELOG and `WhatsNewContent.kt` entries)
6. **Merge** — **human merges only**; Claude never merges a PR

After review + QA complete, the orchestrating Claude instance posts a brief summary to the user **and** to the PR comment thread using `mcp__github__add_issue_comment`.

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
| `gh issue *`, `gh pr create/view/list/diff/checks/comment/ready`, `gh api *` | **Removed** — `gh` is not installed; use `mcp__github__*` MCP tools instead |
| `mcp__github__issue_read`, `mcp__github__pull_request_read` (read-only) | Always allowed — granted to subagents in their frontmatter |
| `mcp__github__issue_write`, `mcp__github__pull_request_review_write`, `mcp__github__add_issue_comment`, `mcp__github__add_comment_to_pending_review`, `mcp__github__create_pull_request` (writes) | Orchestrator-only — subagents return text, the orchestrator posts |
| `./gradlew *` (build, test, lint) | Always allowed — no prompt |
| `git checkout develop` | Requires permission — a prompt will appear |
| `git push origin develop` | Requires permission — a prompt will appear |
| `git checkout main` | **Forbidden** — blocked by `settings.local.json` |
| `git push --force origin claude/*` | Requires permission — a prompt will appear |
| `git push --force origin main` / `git push --force origin develop` | **Forbidden** — blocked by `settings.local.json` |
| `git push origin main` | **Forbidden** — blocked by `settings.local.json` |
| `git reset --hard` | **Forbidden** — blocked by `settings.local.json` |
| Merging PRs by any means (`mcp__github__merge_pull_request`, GitHub UI, etc.) | **Forbidden** — human merges only |

When a prompt appears for `git checkout develop`, `git push origin develop`, or `git push --force origin claude/*`, it is intentional — approve when appropriate.

---

## Release Workflow

When the human asks to cut a release (e.g. "do a release", "bump to X.Y.Z", "prepare a release PR"):

1. **Determine the new version** — ask the human if not specified; follow semver (new features → MINOR bump, fixes only → PATCH bump).

2. **Update these five files** on a `claude/<kebab>` branch:
   - `version.properties` — bump `MINOR` or `PATCH` (or `MAJOR`)
   - `CHANGELOG.md` — promote `## [Unreleased]` → `## [X.Y.Z] - <today>` and add a fresh empty `## [Unreleased]` section above it
   - `WhatsNewContent.kt` — replace the previous release's content with items new in *this* release only (user-facing language; skip internal/perf/doc-only entries)
   - `README.md` — add any features in the new release that aren't already listed under Features
   - `.claude/CLAUDE.md` — add any "What's Been Completed" entries that are still missing

3. **Commit and push** to the feature branch with message `chore: bump version to X.Y.Z, promote changelog, update docs`.

4. **Create PR #1** — `claude/<branch>` → `develop` (title: `chore: release prep for X.Y.Z`). This is a docs/version-only PR.

5. **Create PR #2** — `develop` → `main` (title: `Release X.Y.Z`). Body should list all Added / Fixed / Changed from the new CHANGELOG section. Note in the body that PR #1 must be merged first.

6. **Human merges both PRs** (in order). CI builds the release APK automatically on merge to `main`.

No DB migration, no new tests needed for a docs-only release prep PR.

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
- Fix #105: correct adaptive interval for JUST_RIGHT and TOO_SOON feedback: removed unconditional `JUST_RIGHT` early return in `AddCareLogViewModel` so the actual gap is surfaced as a suggestion when it differs from the stored interval; `CareSchedule.computeSuggestedInterval` accepts optional `currentIntervalDays` — when `TOO_SOON` and `actual < stored`, uses stored as the base so the suggestion extends beyond the stored interval rather than collapsing toward the actual gap; 4 new `CareScheduleTest` cases + updated `AddCareLogViewModelTest` (6 tests total).
- Fix #135: add `imePadding()` to AddEditPlantScreen and AddCareLogScreen scrollable Column modifier chains (after `verticalScroll`, before inner `padding(16.dp)`); bottom Spacer reduced from 72 dp to 16 dp on both screens so the soft keyboard no longer obscures the Notes field
- CHANGELOG.md created at repo root using Keep a Changelog format; backfilled releases 0.1.0–0.4.2; implementer now adds `[Unreleased]` entries as part of dev workflow step 5 (issue #143)
- What's New bottom sheet (issue #147): `ModalBottomSheet` shown on first launch after each update (and fresh install); compares `BuildConfig.VERSION_CODE` vs. `LAST_SEEN_VERSION_CODE` in DataStore; content in `WhatsNewContent.kt` updated by implementer each PR; `buildConfig = true` enabled in build.gradle.kts
- Fix #136: `formatCountdown` returns `"Overdue"` (not `"Due today"`) when `diffMs < 0 && absDays == 0L` (overdue by less than 24 h), so the chip on PlantCard correctly shows red for any overdue timestamp; unit test updated to match — **superseded by fix #141**
- Replace interval suggestion Snackbar with AlertDialog (PR #150, issue #138): `PlantDetailScreen` shows a modal `AlertDialog` with pre-filled editable numeric `TextField`; Apply button disabled when field is empty or non-positive; Dismiss/back/scrim tap permanently discard suggestion; if suggested == current interval no dialog shown and suggestion cleared; `SnackbarHost` removed entirely from `PlantDetailScreen`
- Fix #159: correct `TOO_LATE` adaptive interval when user waters late (actual > stored) — `CareSchedule.computeSuggestedInterval` now clamps the base to `min(actual, stored)` for `TOO_LATE`, symmetric to the PR #149/#105 fix for `TOO_SOON`; 4 new `CareScheduleTest` cases covering actual>stored, actual==stored, actual<stored, and null currentIntervalDays
- Keep screen on toggle in Settings (issue #140): `KEEP_SCREEN_ON` DataStore key; `SettingsViewModel.keepScreenOn` StateFlow + `setKeepScreenOn()`; "Display" section in SettingsScreen with `BrightnessMedium` icon; `MainActivity` collects the flow in `setContent` and applies/clears `FLAG_KEEP_SCREEN_ON` via `LaunchedEffect`; preference round-trips through backup/restore; 2 new `SettingsViewModelTest` cases
- Fix #141: due-date comparisons normalised to calendar-day granularity — `internal fun Long.toLocalDate()` extension added to `DateUtils.kt` (shared via import by `CareSchedule` and `ReminderWorker`); `isOverdue`/`isDueSoon` and fertilizing equivalents in `CareSchedule.computeStatus()` use `LocalDate.isBefore()` / `==` instead of millisecond subtraction; `formatCountdown` uses `ChronoUnit.DAYS.between(toLocalDate(), toLocalDate())` — eliminates "overdue by <24 h" edge case (plants remain "Due today" throughout the due calendar day); `ReminderWorker.buildCareBody()` likewise uses `ChronoUnit.DAYS`; unit tests pin JVM timezone to UTC via `@Before`; supersedes the millisecond workaround in fix #136 (PR #152)
- Location suggestion chips on Add/Edit Plant screen (issue #137, PR #165): `AddEditPlantViewModel.rooms: StateFlow<List<String>>` via `plantRepository.getAllRooms().stateIn(WhileSubscribed(5000))`; `AddEditPlantScreen` renders a `FlowRow` of `SuggestionChip`s below the Location field (hidden when no rooms saved); chip tap sets field to exact stored string and hides keyboard; case-insensitive match (chip ≠ fieldText) applies `primaryContainer` tint; `getAllRooms()` stubbed in `AddEditPlantViewModelTest` and `AddEditPlantScreenTest`
- BackupManager improvements (PR #172, issues #35 #36 #38 #39 #40 #41): export uses a single `getAllLogs()` bulk query + `groupBy(plantId)` instead of N+1 per-plant calls; photo export opens `InputStream` before `putNextEntry` so unreadable URIs skip cleanly; `performImport` wrapped in try-catch deleting only files written in the current import attempt on failure; `ReminderScheduler.schedule/cancel` called after DataStore write on restore; error message when backup.json is missing changed from "not compatible File" to a readable string; all backup/restore UI strings moved to `strings.xml`
- Fix #117: watering history chart now works for infrequently-watered plants (PR #166): `computeWateringIntervals` uses the most recent watering before `rangeStartMs` as a predecessor anchor so the first in-window log gets an interval; when `inRange` is empty the last two pre-range waterings produce one interval point; `ChartContent` month loop starts from `min(rangeStartMs, earliest interval timestamp)` so pre-range points land in a visible bucket; `rememberLineCartesianLayer` configured with `LineCartesianLayer.PointProvider.single(CorneredShape.Pill)` so a single data point (2 total waterings) renders as a visible circle; 5 new unit tests added
- Skip watering stepper dialog + `wateringDueDateOverride` (PR #176, issues #168 #169): tapping "Skip watering" on plant detail opens an `AlertDialog` with a +/− stepper (range 1–7 days, default 1); confirming sets `wateringDueDateOverride: Long?` on the plant — due date pushed forward by N days from `max(nextDueAt, now)` — without touching `wateringIntervalDays`; the existing interval-extension `AlertDialog` fires immediately after so the user can optionally make the change permanent; logging a WATER event clears the override; `SkipWateringReceiver` handles the notification "Skip watering" action by pushing the override +1 day; `CareSchedule.computeStatus()` applies `nextDueAt = maxOf(computedDue, override)`; Room DB bumped to version 2 with explicit `MIGRATION_1_2` (`ALTER TABLE plants ADD COLUMN wateringDueDateOverride INTEGER`); `BackupPlant.wateringDueDateOverride` threads through export/import for round-trip fidelity; schema `2.json` committed with real Room `identityHash`
- Fix #180: `CareSchedule.daysBetween()` now uses `ChronoUnit.DAYS.between(earlierMs.toLocalDate(), laterMs.toLocalDate())` instead of millisecond integer division, eliminating the spurious "interval − 1" suggestion when the user waters on the exact due calendar day with Just Right feedback (PR #182); 2 new `CareScheduleTest` cases
- "Unassigned" filter chip on plant list: shows only plants with no room assigned; chip hidden and selection resets to "All" when all plants have rooms; single shared `getAllPlants()` Room subscription via private `allPlants` StateFlow avoids duplicate Room subscriptions; auto-fallback test added (issues #183, #184)
- Fix #144: `BackupManager.exportBackup` now writes the full ZIP to a temp file in `cacheDir` first, then streams the finished file to the SAF destination URI in a single copy; temp file deleted in `finally`; fixes broken 0 KB exports to cloud SAF providers (Google Drive, etc.); photo input-stream opener now handles bare absolute paths (`File.inputStream()`) and `file://` URIs in addition to `content://` URIs so restored photos are no longer silently skipped on re-export; 1 new `BackupManagerTest` case verifies temp file is deleted on export failure
- Dependency upgrades (PR #200, issue #16): AGP 8.7.3→8.13.2, Kotlin 2.0.21→2.1.21, KSP 2.0.21-1.0.28→2.1.21-2.0.2, Gradle 8.9→8.14.5, Compose BOM 2024.11.00→2026.05.01, Room 2.6.1→2.8.4, Lifecycle 2.8.7→2.10.0, Navigation 2.8.4→2.9.8, DataStore 1.1.1→1.2.1, WorkManager 2.10.0→2.11.2, core-ktx 1.15.0→1.18.0, activity-compose 1.9.3→1.13.0, desugar_jdk_libs 2.1.3→2.1.4, kotlinx-coroutines 1.9.0→1.10.1, kotlinx-serialization 1.6.3→1.8.1, Robolectric 4.13→4.16.1, Turbine 1.2.0→1.2.1; compileSdk 35→36
- Fix #193 (PR #201): BackupManager restore no longer loads all photo bytes into memory at once; each photo is streamed to a temp file during ZIP traversal and deleted immediately after copying to the destination, preventing OOM crashes on large backups
- Fix #195/#196 (PR #199): temp photo files cleaned up on FutureSchemaWarning dismiss (`onDismiss` callback added to `FutureSchemaWarning`, called from all dismiss paths in SettingsScreen); partial temp photo file no longer orphaned in `cacheDir` if `copyTo` throws mid-write (map entry inserted before write so outer `finally` can always reach the file)
- CI: release job now automatically creates a GitHub Release with the signed APK attached and auto-generated release notes (via `gh release create --generate-notes`) on every push to `main`; `GITHUB_TOKEN` given `contents: write`; skips creation silently if tag already exists (PR #205)
- What's New sheet shows full release history grouped by version, newest first, scrollable — `WhatsNewContent.all: List<ReleaseNotes>` replaces `current`; `LazyColumn` with `weight(1f)` keeps "Got it" button pinned; "What's New" row in SettingsScreen reopens the sheet on demand without resetting the auto-show trigger; `updateStoreOnWhatsNewDismiss` flag in NavGraph gates the DataStore write to auto-show dismissals only (PR #213, issue #212)
- Agent definitions refactored (#221, PR #222): dead `gh` CLI blocks removed from all four `.claude/agents/*.md` (subagents return findings as text, orchestrator posts via `mcp__github__*`); `reviewer.md` slimmed 202 → 108 lines (APPROVE/REQUEST_CHANGES verbs dropped, round-cap contradiction reconciled, 1‑2‑4 numbering fixed); trigger-style `description` and `model:` field added per agent (reviewer/implementer `sonnet`, qa/spec `inherit`); read-only `mcp__github__issue_read` + `mcp__github__pull_request_read` granted so subagents fetch their own inputs; `CLAUDE.md` Autonomy table updated (gh row replaced with MCP read/write split) and workflow step 2 updated to reflect implementer's push-only flow
- Liquid fertilizer mode per plant (PR #209, issue #56): `useLiquidFertilizer` toggle on Add/Edit Plant; FERTILIZE logs with Liquid type auto-create a paired WATER log (from quick-log and Add Care Log screen); Fertilizer type selector (Liquid/Solid) on Add Care Log screen; PlantCard and PlantDetail fertilizing chip shows "With watering" label for liquid-fertilizer plants; quick-fertilize button on plant list auto-creates a paired watering log; notifications append "Fertilize with watering" to watering alert instead of separate notification; DB v2→v3 via MIGRATION_2_3; backup schema v1→v2
- CI: `test` job extracted from `build` (runs `testDebugUnitTest` + `lintDebug`, uploads test-results artifact); `build` (debug APK) now depends on `test`; `release` now depends on `test` (explicit gate) and runs `testReleaseUnitTest` + `lintRelease` before `assembleRelease`; uploads release-test-results artifact (issue #84)
