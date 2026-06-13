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

**When a feature contradicts an ADR:** do not implement silently against the existing decision. Surface the conflict in the spec, name the ADR, state its rationale, and wait for the human to confirm. If confirmed, implement the new behaviour and write a new ADR that supersedes the old one. The superseded ADR's **Status** line is updated to `superseded by [ADR-XXXX](filename.md)` — this single-line metadata update is the only permitted edit to a finalized ADR; all other content stays intact.

---

## Known Issues / Technical Debt

Tracked as GitHub issues:

| # | Description | Severity |
|---|---|---|
| [#170](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/170) | Skip watering button: replace `TextButton` with `ExtendedFloatingActionButton` bottom-start; fix stepper dialog alignment | Enhancement |
| [#175](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/175) | BackupManager: photo cleanup on failure can delete committed-DB files if exception fires after DB transaction but before ImportSuccess | Enhancement |

---

## Development Workflow

Every feature and bug fix follows these steps in order:

1. **Spec** (`spec` agent) — scans `docs/decisions/product/` for ADRs relevant to the feature; surfaces any contradictions to the human before proceeding; interviews the human, resolves ambiguities, posts clarifications as a comment on the GitHub issue
2. **Implement** (`implementer` agent) — reads the spec, writes code, pushes a `claude/*` branch, and returns the PR title/body as text; the **orchestrating Claude instance** opens the PR targeting `develop` via `mcp__github__create_pull_request`
3. **Review** (`reviewer` agent) — iterative rounds of review:
   - Each finding is labelled **BLOCKING** (must fix) or **NON-BLOCKING**; the reviewer also tags each NON-BLOCKING finding as **SMALL** (localised, ≤ a few lines, no design risk) or **LARGE** (cross-cutting, architectural, or requires its own spec)
   - The reviewer agent returns findings as text; the **orchestrating Claude instance** posts them:
     - BLOCKING inline comments: (1) `mcp__github__pull_request_review_write` `create` (no `event`) → (2) `mcp__github__add_comment_to_pending_review` per finding → (3) `mcp__github__pull_request_review_write` `submit_pending` with `event: COMMENT`
     - NON-BLOCKING: the orchestrator **asks the human** for each finding (or grouped by recommendation) before acting — it states its recommendation ("fix in this PR" for SMALL, "new issue" for LARGE) and waits for the human's decision; then either hands the fix to the implementer in the current PR or files a new GitHub issue via `mcp__github__issue_write`
   - **Each reviewer round is posted as a fresh, standalone PR review — never combined with a previous round's findings**
   - The PR review body is compact: verdict + counts only
   - **GitHub constraint:** `APPROVE` and `REQUEST_CHANGES` are both blocked when the PR author and reviewer share the same GitHub account — always use `COMMENT` event
   - After round 2, the reviewer stops and waits for the human to decide (another implementer round, manual approval, or other action)
   - **Note:** `reviewer` subagents have read-only GitHub MCP tools (`issue_read`, `pull_request_read`) so they fetch the issue + PR themselves, but they cannot post — the orchestrator must post on their behalf
4. **QA** (`qa` agent) — validates build, tests, lint, and every acceptance criterion from the spec
   - The QA agent returns a compact checklist (under 15 lines for a passing run); the **orchestrating Claude instance** posts it to the PR using `mcp__github__add_issue_comment`
   - **Note:** `qa` subagents have read-only GitHub MCP tools (`issue_read`, `pull_request_read`) so they fetch the issue + PR themselves, but they cannot post — the orchestrator must post on their behalf
5. **Update docs** — implementer updates this file, `CHANGELOG.md` (`[Unreleased]` section), and `WhatsNewContent.kt` (user-facing release notes) to reflect completion (`chore:`/docs-only PRs with no user-visible change may omit the CHANGELOG and `WhatsNewContent.kt` entries)
6. **Merge** — **human merges only**; Claude never merges a PR

**Comment cadence** — the orchestrator posts each phase as its own separate comment, in order. Never bundle multiple phases or rounds into one comment:

| Phase | Where | Tool |
|---|---|---|
| Spec clarifications | GitHub issue | `mcp__github__add_issue_comment` |
| Each reviewer round | PR (inline review) | `pull_request_review_write` + `add_comment_to_pending_review` |
| QA result | PR | `mcp__github__add_issue_comment` |
| Workflow summary | PR | `mcp__github__add_issue_comment` |

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
   - `WhatsNewContent.kt` — prepend a new `ReleaseNotes` entry with the new `versionCode` and `versionName`; **`WhatsNewContentTest` will fail at CI if this is skipped** (the test asserts `all.first().versionName == BuildConfig.VERSION_NAME`)
   - `README.md` — add any features in the new release that aren't already listed under Features
   - `.claude/CLAUDE.md` — add any "What's Been Completed" entries that are still missing

3. **Commit and push** to the feature branch with message `chore: bump version to X.Y.Z, promote changelog, update docs`.

4. **Create PR #1** — `claude/<branch>` → `develop` (title: `chore: release prep for X.Y.Z`). This is a docs/version-only PR.

5. **Create PR #2** — `develop` → `main` (title: `Release X.Y.Z`). Body should list all Added / Fixed / Changed from the new CHANGELOG section. Note in the body that PR #1 must be merged first.

6. **Human merges both PRs** (in order). CI builds the release APK automatically on merge to `main`.

No DB migration, no new tests needed for a docs-only release prep PR.

---

## What's Been Completed

**Architecture & Data**
- Room DB v5 (PlantEntity, CareLogEntity, PlantPhotoEntity); explicit migrations — hard-crash if migration missing (`fallbackToDestructiveMigration` removed); schemas exported to `app/schemas/`; baseline `1.json` committed; MIGRATION_3_4 creates `plant_photos` table and seeds rows from `coverPhotoUri` (#290); MIGRATION_4_5 adds unique index on (plantId, uri) in plant_photos and deduplicates existing rows (#301)
- PlantRepository + CareLogRepository + PlantPhotoRepository with entity↔domain mapping; UI never touches Room entities directly
- `GalleryPhoto(uri, timestamp)` projection type in `domain/model/` — used by PlantDetailViewModel to merge plant photos and care-log photos via `Flow.combine`; `.distinctBy { it.uri }` prevents duplicate-key crash in LazyRow (#290)
- Domain models: Plant, CareLog, CareType, WateringFeedback, PlantCareStatus; enums stored as String in Room with `runCatching { Enum.valueOf(...) }.getOrDefault(fallback)` deserialization; `CareType` and `WateringFeedback` are plain Kotlin enums — display strings and icons live in `ui/util/EnumResources.kt` extension functions (#276)
- DataStore preferences (notification toggle, reminder time, sort option, keep-screen-on, last-seen version code); delegate declared at file top-level in `YaptApplication.kt`
- Manual DI via `YaptApplication` lazy singletons; ViewModel `Factory` inner classes; no Hilt
- Nature-themed Material 3 dark/light theme; Android PhotoPicker with `takePersistableUriPermission`

**Domain & Scheduling**
- `CareSchedule.computeStatus()`: calendar-day comparisons via `Long.toLocalDate()` in `DateUtils.kt`; `isOverdue`/`isDueSoon` use `LocalDate.isBefore()` / `==` — no millisecond division (see technical ADR-0013)
- `CareSchedule.computeSuggestedInterval()`: JUST_RIGHT suggests when actual ≠ current; TOO_SOON uses `currentInterval` as base when actual < stored; TOO_LATE clamps to `min(actual, stored)`; `daysBetween()` uses `ChronoUnit.DAYS` — no spurious "interval − 1" suggestion
- `CareSchedule.computeWateringIntervals()`: calendar-month intervals for chart; predecessor-anchor so infrequent waterers get data points for in-window logs

**Screens & Navigation**
- All 5 screens: PlantList, AddEditPlant, PlantDetail, AddCareLog, Settings; all reusable components: PlantCard, CareLogItem, PhotoGallery, StatsRow, PlantPhoto, CareTypeSelector
- Sealed `Screen` class; `savedStateHandle` for cross-screen data (e.g. `suggestedWateringInterval`); reads/writes wrapped in `LaunchedEffect` to avoid `StateFlowValueCalledInComposition`
- `collectAsStateWithLifecycle()` everywhere; `imePadding()` on AddEditPlant and AddCareLog scrollable columns so keyboard never obscures Notes field
- Location suggestion chips on AddEditPlant: `FlowRow` of `SuggestionChip`s below Location field; tapping fills field and hides keyboard; case-insensitive match highlights chip (#137)
- In-app camera capture: `PhotoSourceBottomSheet` lets user choose "Take photo" or "Choose from gallery"; runtime CAMERA permission with rationale dialog on first denial and Settings deep-link on permanent denial; Snackbar error on devices without a camera (#134); shared `rememberCameraPhotoState` + `CameraPhotoDialogs` composable extracted to avoid duplicating ~80-line camera block across AddEditPlantScreen and AddCareLogScreen (#293)

**Care Features**
- Care logging: Water, Fertilize, Prune, Mist, Repot, Note, Photo; custom dates; edit existing log entries (#20)
- Adaptive watering interval: modal `AlertDialog` with editable TextField shown after WATER logs (see product ADR-0006); JUST_RIGHT, TOO_SOON, TOO_LATE all produce correct suggestions; default feedback pre-selected to JUST_RIGHT; quick-water opens `WaterFeedbackBottomSheet` (JUST_RIGHT pre-selected, 2-tap happy path) and fires interval suggestion AlertDialog from PlantListScreen (see product ADR-0015, #126)
- Liquid fertilizer mode: `useLiquidFertilizer` flag on plants (DB v3, MIGRATION_2_3); all FERTILIZE paths (AddCareLog, quick-log) auto-insert a paired WATER log at the same timestamp; combined quick-water-fertilize button on PlantCard opens `WaterFeedbackBottomSheet` before committing logs (title "Water & fertilize [plant]?"); `quickLiquidFertilizeWithFeedback()` in ViewModel fires interval suggestion; backup schema v2 (see product ADR-0008, ADR-0017, #56, #344)
- Skip watering: `wateringDueDateOverride: Long?` on plants (DB v2, MIGRATION_1_2); stepper dialog (1–7 days) pushes `max(nextDueAt, now)` forward without touching interval; override clears on next WATER log (AddCareLog and quickLog paths); follow-up interval AlertDialog lets user make it permanent (see product ADR-0007, #168 #169 #210)
- Watering feedback labels: "Still wet" (TOO_SOON), "Just right" (JUST_RIGHT), "Too dry" (TOO_LATE); enum values and DB unchanged; question text "What did you find?" (see product ADR-0009, #161)
- Photo care log validation: save FAB disabled (0.38f alpha, click-blocked) when `CareType.PHOTO` is selected and no photo is attached; inline error hint shown below photo picker (#305)

**Plant List UI**
- Room filter chips + "Unassigned" chip (shows plants with no room; auto-resets to "All" when all plants have rooms; single `allPlants` StateFlow subscription) (#183 #184)
- "Water + Fertilize due" filter — both care types due/overdue, sorted by watering urgency (#78)
- Sort controls: 4 options (Alphabetical, Watering due, Fertilizing due, Recently added); ASC/DESC toggle on applicable sorts; DataStore-persisted (#21)
- Countdown chips on PlantCard: `DateUtils.formatCountdown()` → "In X days" / "Due today" / "Overdue by X days"; OkGreen / WarnOrange / OverdueRed (#32 #55); liquid-fert fertilizing chip shows "Due with next watering" when overdue/due-soon, else standard countdown (#267)
- Quick water/fertilize icon buttons on each PlantCard; `PlantListViewModel.quickLog()` emits `SharedFlow<String>` Snackbar event (#19); liquid-fertilizer quick-log button has `contentDescription` for screen readers (#251)
- Larger PlantCard photo: 90 dp wide edge-to-edge strip filling card height, left corners 12 dp rounded (#29)

**Plant Detail UI**
- Hero photo: 280 dp, bleeds behind status bar; Box overlay pattern (no Scaffold); overlaid back/edit buttons with dark pill containers; `Surface(colorScheme.background)` root for correct dark-mode text colour (#29); tapping the hero `AsyncImage` opens `FullScreenPhotoViewer` at the cover photo URI; placeholder (no cover photo) has no clickable modifier (#307)
- StatChip: icon + label header with `next:` / `last:` lines for watering and fertilizing
- Watering history chart: Vico `LineCartesianLayer`; calendar-month buckets; 5 time ranges (1M/3M/6M/12M/All); predecessor-anchor so infrequent waterers see data; single point (2 total waterings) renders as circle; autoscroll to right on range change / new log; empty state when < 2 logs; `now` keyed on `wateringLogs` so a freshly-logged watering appears immediately without navigating away (#18 #117 #114)
- Care history collapses to 5 most recent logs by default; `AssistChip` with animated 0°/180° chevron expands/collapses the full list; chip hidden when ≤ 5 logs; expanded state resets on screen open (#253)
- Per-plant photo gallery: unified scrollable `PhotoGallery` combining `plant_photos` rows and care-log photos (sorted newest first); `FullScreenPhotoViewer` Dialog opens on tap; adding a photo in AddEditPlant appends to `plant_photos` and updates `coverPhotoUri` to the newest; existing cover photos migrated automatically on DB upgrade (#290); saving a Photo care log entry updates the plant's cover photo to the attached image (#304)
- Full-screen photo viewer: `HorizontalPager` replaces single `AsyncImage`; swipe left/right navigates all gallery photos; opens at tapped index; "2 / 5" position indicator shown when > 1 photo (#308); trash icon in viewer + long-press on gallery thumbnail delete individual photos; care-log photo deletion preserves the log entry; cover falls back to next most-recent photo (#306)

**Notifications & Reminders**
- `ReminderWorker` (WorkManager, REPLACE policy): daily at user-configured time; one notification per overdue/due-soon plant (ID = `plant.id.toInt()`); cancels all plant notifications before re-posting; body = care items joined with " · " (#7)
- Liquid-fertilizer plants: "Fertilize with watering" appended to watering alert — no separate fertilizing notification (#56)
- Deep-link: notification tap → `MainActivity` intent extra `plantId` → `YaptNavGraph` navigates to PlantDetailScreen (#7)
- `SkipWateringReceiver` handles "Skip watering" notification action (+1 day override); guards on `intent.action` before processing (#178)
- `BootReceiver` reschedules using stored time; uses `goAsync()`
- Default reminder time (hour=9, minute=0) written to DataStore on first launch in `YaptApplication.onCreate()` so `SettingsViewModel.setNotificationsEnabled()` and `BootReceiver` `?: 9` fallbacks never silently re-anchor the schedule (#356)

**Backup & Restore**
- `.yapt` ZIP export/import via SAF; optional photo inclusion; settings round-trip; forward-compatibility warning dialog (#22); backup schema v3 includes `plantPhotos: List<BackupPlantPhoto>`; old v2 backups deserialize with `plantPhotos = emptyList()` for forward compat (#290)
- Export: ZIP assembled in `cacheDir` temp file first, then streamed to SAF destination — prevents broken 0 KB exports to cloud providers (see technical ADR-0014, #144)
- Restore: photos streamed to `cacheDir` temp files (never loaded into memory) — prevents OOM; temp files tracked in map before copy so `finally` always cleans up (#193 #195 #196)
- Single bulk `getAllLogs()` query (not N+1 per plant); unreadable photo URIs silently skipped; ReminderScheduler called with restored time; navigate to PlantList + Snackbar on success (#36 #37 #40 #41)
- All backup/restore UI strings in `strings.xml` (#39)

**Settings**
- Reminder time picker: Material 3 TimePicker dialog; hour + minute DataStore-persisted (#10)
- Keep screen on toggle: `FLAG_KEEP_SCREEN_ON` applied in `MainActivity` via `LaunchedEffect`; round-trips through backup (#140)
- "What's New" row: reopens the history sheet at any time without resetting the auto-show trigger (#212)

**What's New**
- Auto-shown on first launch after version bump (`BuildConfig.VERSION_CODE > LAST_SEEN_VERSION_CODE` in DataStore); `buildConfig = true` in `build.gradle.kts` (#147)
- Full release history in `WhatsNewContent.all: List<ReleaseNotes>`; sorted by `versionCode` descending at render time; scrollable `LazyColumn`; "Got it" button pinned; `versionCode: Int` field on `ReleaseNotes` guarantees sort order (see product ADR-0010, #212 #219)

**Tests**
- Unit tests: 18 CareSchedule + 11 DateUtils; ViewModel tests for all 5 VMs (MockK + coroutines-test + turbine, `MainDispatcherRule`); JVM timezone pinned to UTC in `@Before`; `AddCareLogViewModelTest` covers `wateringDueDateOverride` clear on WATER log save and uses `advanceUntilIdle()` in edit-mode tests for explicit sync; `PlantListViewModelTest` covers `quickLog` else-branch and `toggleSort` direction cycling and `applySortOrder` ordering for all sort options; `BackupSerializerTest` asserts `encodeDefaults = true` emits explicit null keys; `fullRoot()` sets every non-null field of `BackupPlant`, `BackupCareLog`, `BackupSettings`, and `BackupRoot` to representative non-null values so future nullable additions are caught by the round-trip test (#288); `SettingsViewModelTest` defaults tests stub non-default values to prove DataStore mapping path; `PlantPhotoDaoTest` (Robolectric, 8 cases) covers insert+query, multi-photo desc order, cascade delete, duplicate IGNORE returns -1L; `MigrationTest3To4` (Robolectric, 2 cases) verifies MIGRATION_3_4 seeds plant_photos from coverPhotoUri and skips null URIs; `MigrationTest4To5` (Robolectric, 2 cases) verifies MIGRATION_4_5 preserves all rows when no duplicates and keeps min-id row when duplicates exist; `WhatsNewContentTest` (plain JVM) asserts `WhatsNewContent.all.first().versionName == BuildConfig.VERSION_NAME` — fails at CI if a release bumps the version without prepending a new entry (#46 #48 #59 #63 #64 #77 #187 #218 #265 #290 #301 #303)
- Instrumented BackupManager tests: 9 cases (round-trips with/without photos, empty DB, future-schema warning, corrupt ZIP, missing backup.json, zip-slip, settings, photo SHA-256) (#50)
- Compose screen tests for all 5 screens: `createComposeRule()`, MockK, no `Thread.sleep` (#51); `PlantDetailScreenTest` includes cover-photo tap tests: tapping hero photo opens viewer, tapping placeholder does nothing (#307); delete-photo confirmation dialog tests: long-press thumbnail shows dialog, confirm deletes, dismiss cancels (#306); `AddEditPlantScreenTest` and `AddCareLogScreenTest` each have 5 new camera path tests (bottom sheet on photo button tap, gallery option visible, no-camera Snackbar, permission rationale dialog, permanent-denial settings dialog) (#294)

**CI/CD**
- `test` job (unit tests + lintDebug) gates both `build` (debug APK) and `release` jobs; release also runs `testReleaseUnitTest` + `lintRelease` (#84)
- Instrumented tests on PRs via path-filter (`app/src/main/**`, `app/src/androidTest/**`, `app/build.gradle.kts`); concurrency group auto-cancels stacked PR runs (#87)
- GitHub Release auto-created on push to `main` with signed APK + auto-generated notes; `--target SHA` anchors tag to the exact triggering commit (#205)
- Debug keystore committed to repo for in-place upgrades between local and CI APKs (#17)
- Release build: `isMinifyEnabled = true`, `isShrinkResources = true`; ProGuard rules for WorkManager workers and Room DAOs (#4)
- Redundant `ANDROID_HOME` env overrides removed from all Gradle steps; deprecated `Icons.Filled.Notes` replaced with `Icons.AutoMirrored.Filled.Notes`; `@Suppress("DEPRECATION")` on `statusBarColor`; `@OptIn(ExperimentalCoroutinesApi::class)` added to ViewModel test classes (#336)

**Process & Docs**
- CHANGELOG.md at repo root (Keep a Changelog format); `[Unreleased]` → versioned heading on release (#143)
- ADRs in `docs/decisions/product/` (product/UX decisions) and `technical/` (implementation constraints); spec agent scans before interviewing; implementer reads before coding in covered areas
- All UI strings in `strings.xml` — no hardcoded strings in Compose screens (#91 #154 #158 #215 #248 #220 #272 #275); shared `SettingsItemRow` private composable extracted in `SettingsScreen.kt`; `cd_back` is the canonical back-button content description
- Agent definitions in `.claude/agents/`: spec, implementer, reviewer, qa; subagents return findings as text, orchestrator posts via `mcp__github__*`; comment cadence table in CLAUDE.md (#221 #242)
- NON-BLOCKING reviewer findings now tagged SMALL/LARGE; orchestrator asks human with a recommendation before fixing in-PR or filing a new issue; `reviewer.md` updated to emit SMALL/LARGE tags and recommended action per finding (PR #259)
- README at repo root with Features list, build instructions, CI/CD badge, project structure
