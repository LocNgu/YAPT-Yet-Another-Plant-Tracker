# YAPT – Yet Another Plant Tracker

Offline-first Android app for houseplant care. No cloud, no accounts, no telemetry. Users log care
events (water/fertilize/prune/repot/note/photo); the app surfaces overdue reminders and adapts
watering intervals from the user's own feedback.

> **Keep this file lean — it loads into every session.** Only repo-wide rules belong here, one or two
> lines each. Feature/screen detail goes in a path-scoped `.claude/rules/*.md` (add one with `paths:`
> frontmatter if none fits); history goes in `CHANGELOG.md`, ADRs and `git log`, never here.

## Tech Stack
- Kotlin · Jetpack Compose + Material 3 (nature palette) · MVVM + Repository
- Room (SQLite, offline-first) · DataStore (prefs) · WorkManager + NotificationManager · Coil 2
- Compose Navigation (type-safe `Screen` sealed class) · manual DI via `YaptApplication` lazy singletons (no Hilt)
- Build: AGP 9.4.1, Kotlin plugins 2.4.20, KSP 2.3.12, Gradle 9.7.1, Compose BOM 2026.09.00; compileSdk 37 / targetSdk 35 / minSdk 26

## Commands
```bash
./gradlew compileDebugKotlin compileDebugUnitTestKotlin compileDebugAndroidTestKotlin   # compile
./gradlew testDebugUnitTest        # unit tests (flag also builds the release suite — see rules/ci-build.md)
./gradlew lintDebug                # Android lint
./gradlew detekt                   # static analysis (add autoCorrect=true locally to auto-fix formatting)
python3 tools/check-adr-numbering.py   # ADR numbering + citation check (CI-enforced, no JDK needed)
python3 -m unittest discover -s tools -p 'test_*.py'   # tests for that check
```
Prefer `-q` and grep for failures over dumping full build logs. Cloud/session build setup: `.claude/rules/ci-build.md`.

## Architecture
```
data/{db,entity,repository}   Room DAOs, @Entity, repos (entity↔domain mapping — UI never touches entities)
domain/{model,schedule,today,usecase,…}       CareSchedule; TodayQueueAggregator; shared quick-log
notification/                 channel creation + POST_NOTIFICATIONS helper (NotificationPermission)
ui/{components,navigation,screens,theme}   screens; Screen sealed class; NavGraph
util/                         DateUtils, ImageUtils, DayChangeTicker
worker/                       ReminderWorker, ReminderScheduler, BootReceiver
```
- Manual DI: `YaptApplication` builds DB + repositories as lazy singletons; `NavGraph` passes them into each ViewModel's inner `Factory`.
- Every ViewModel has an inner `Factory`; screens obtain it via `viewModel(factory = …)`.
- **Care vs. Today:** the user-facing name is **Care** (tab, top bar, copy, changelog); internal identifiers (`Screen.Today`, route `today`, `Today*` classes) deliberately keep "Today". Care is the start destination; root tabs are Care · Plants · Calendar · Settings — so Plants may not be on the back stack — never `getBackStackEntry(Screen.PlantList.route)` unguarded (#836). Details: `rules/navigation.md`, `rules/care-queue.md`.
- Due tasks come only from `TodayQueueAggregator` (via `TodayCareRepository`); UI code never derives them.
- Quick-log surfaces go through `QuickLogUseCase`; adaptive WATER learning lives in `AdaptiveWateringObservation` (`rules/care-logging.md`).

## Conventions (beyond what the linter enforces)
- **StateFlow** for UI state; **SharedFlow** for one-shot events. Always `collectAsStateWithLifecycle()` (never `collectAsState()`).
- **Enums stored as String** in Room — read with `runCatching { Enum.valueOf(...) }.getOrDefault(fallback)`, never plain `.valueOf()`. Display strings/icons live in `ui/util/EnumResources.kt`, not on the enum.
- **Retired enum constants stay** — `CareType.CHECK`/`MIST` and `WateringAdjustmentTrigger.CHECK_STILL_MOIST` are kept for reading historical rows/backups; no new CHECK/MIST rows are created (an existing MIST log stays editable).
- **Dates** — relative-date display only via the composable `relativeDateText()` (`ui/util/RelativeDateText.kt`, strings from resources); never compute `(now-ts)/86_400_000` inline. Calendar-day comparisons via `Long.toLocalDate()` (technical ADR-0013). Advance by N days via `Long.plusCalendarDays()`, never `+ TimeUnit.DAYS.toMillis(n)` (DST, technical ADR-0034); the one exception is the REPOT freeze window in `WateringLifecycleReset`, a genuine duration documented in place.
- **A `combine()` that reads "today" needs `dayChangeTicker()` as an input** — nothing else emits at midnight (#550). Constructor-inject it as `Flow<LocalDate>` defaulting to `dayChangeTicker()`; tests pass a non-real ticker and **never `advanceUntilIdle()` against the real one** (it hangs). Details: `rules/day-change.md`.
- **Two watering-interval numbers** — `wateringBaseIntervalDays` (season-neutral `Double`, never rounded at rest) and `wateringIntervalDays` (effective, only rewritten on discrete events). For today's effective interval call `CareSchedule.effectiveWateringIntervalDaysForDisplay()`, never the stored literal. Details: `rules/seasonal-watering.md`.
- **Same-day WATER/FERTILIZE duplicates are rejected** (only those two types) via `CareLogRepository.hasLogOfTypeOnDay()`; check *before* any paired insert. Details: `rules/care-logging.md`.
- **Plant Detail plant-row writes** take `plantEditMutex` and re-read the plant inside it (never `plant.value`); the mutex is not reentrant. Details: `rules/plant-detail.md`.
- **Integer interval sliders use the shared `SteppedSlider`** (`ui/components/SteppedSlider.kt`, product ADR-0048).
- **Every `FilterChip` passes `colors = yaptFilterChipColors()`** (product ADR-0052).
- **New camera surfaces use `rememberCameraPhotoState()`**, never their own launcher; never eager-delete a photo file — `OrphanPhotoSweeper` reclaims them (`rules/photos.md`).
- **No `libs.versions.toml`** — versions inlined in `app/build.gradle.kts`; the Compose BOM governs Compose artifacts.
- **DataStore delegate** (`val Context.settingsDataStore by preferencesDataStore(...)`) is declared at **file top-level** in `YaptApplication.kt`, never inside a class (technical ADR-0009).
- **Room migrations are mandatory** — explicit `Migration`s only, hard-crash if one is missing, never `fallbackToDestructiveMigration`; every schema change ships a `Migration` and the KSP-exported schema JSON in `app/schemas/` (technical ADR-0002). `PlantDatabase.DB_VERSION` is the single version source. New `Plant` columns must also be carried over in `AddEditPlantViewModel.saveEdit()`.
- **All UI strings in `strings.xml`** — no hardcoded strings in Compose. `cd_back` is the canonical back-button description.
- **Compose UI tests assert user-visible semantics** (contentDescription/stateDescription/text/actionable), **never** tree structure (child counts, testTag topology). A testTag never merges past a clickable/merged ancestor. A fix about announcements asserts the announcement (#420).
- **Two-strikes rule** — after two failed fix attempts on the same test, stop pushing variants; re-derive the mechanism from framework source/docs or a minimal repro, and check whether the test asserts structure instead of contract (#420).
- **Robolectric tests run against `TestYaptApplication`** — its class name is load-bearing; new app-start work goes in `launchAppStartWork()`; never `@Config(application = YaptApplication::class)`. Details: `rules/ci-build.md`.
- Palette: SageGreen `#6B8F71`, WarmCream `#F5F0E8`, EarthBrown `#795548`; status OkGreen/WarnOrange/OverdueRed in `Color.kt`. `IssuePurple` is a separate axis (plant health, not care-due) — never reuse due-status colors for it (technical ADR-0020).

## Architecture Decision Records
- Decisions live in `docs/decisions/{product,technical}/`. **Consult the relevant ADR before working in a covered area**; never refactor a pattern a technical ADR describes without a superseding decision. If a request contradicts an ADR, name it and its rationale and get human confirmation first.
- A significant new decision gets a new ADR from `docs/decisions/template.md` (Status `accepted`, next number in its folder).
- The two folders number **independently** — cite as `product ADR-NNNN` / `technical ADR-NNNN` whenever the number exists in both (CI checks this).
- Finalized ADRs are not rewritten: only Status-line supersession/amendment notes and non-substantive corrections. Full rules (amendment format, collisions, in-folder citations): `rules/adr.md`.

## Development Workflow
**Issue-first (always):** on any feature request or bug report, first create a GitHub issue via `mcp__github__issue_write`, share the link, and wait for explicit go-ahead before writing any code, branch, or PR.

**Model & cost:** orchestrator on **Sonnet for routine issues**, Opus only for genuinely hard cross-system reasoning. Subagents are model-pinned in their frontmatter.

1. **Spec** (`spec` agent) — clarifying questions, then clarifications posted on the issue. Skipped only on the fast-path (mechanical **and** single-file change).
2. **Implement** (`implementer` agent) — pushes a `claude/*` branch; the orchestrator opens the PR against `develop`.
3. **Review** (`reviewer` agent) — **never skipped**, runs in parallel with CI; one combined fix round (CI + BLOCKING + SMALL); review is capped at two rounds total (`pipeline.md`).
4. **QA** (`qa` agent) — only when the reviewer lists "needs a device" items.
5. **Update docs** in the feature PR: `CHANGELOG.md` `[Unreleased]`, `WhatsNewContent.unreleased` (never `all`), and the relevant `.claude/rules/*.md` (this file only for repo-wide rules). `chore:`/docs-only PRs may skip the changelog/What's New.
6. **Merge** — **human only**; Claude never merges.

**Before orchestrating, read `.claude/pipeline.md`** — fast-path criteria, review-on-push and CI-line format, fix-round limits, resuming the implementer, comment cadence. Release steps: `.claude/rules/release.md`.

## Git Workflow
One branch and one PR per change — never mix unrelated work. Always branch from freshly-fetched `origin/develop`:
```bash
git fetch origin develop && git checkout -b claude/<kebab-desc> origin/develop
```
PR targets `develop`. Return to an up-to-date `develop` before starting anything new. `gh` is not installed — use `mcp__github__*` tools.

## Permissions (enforced by `.claude/settings.json`; editing it: `rules/permissions.md`)
- `mcp__github__*` **writes** are **orchestrator only** — subagents return text, the orchestrator posts.
- Allowed: reads, read-only git, `add`/`commit`/`stash`/`cherry-pick`, checkout/push `claude/*`, `./gradlew *`. Prompts: checkout/push `develop`, force-push `claude/*`.
- **Forbidden:** anything touching `main`, force-pushing `develop`, branch deletion/rename, `git reset --hard`, `git stash drop`/`clear`, `find`'s action primaries, reading secret files, and **merging PRs by any means**.

## Pointers — `.claude/rules/` (path-scoped: each loads automatically when you touch matching files)
- Domain: `schedule.md` (CareSchedule, adaptive interval, dormancy) · `seasonal-watering.md` · `fertilizing-seasons.md` · `repotting.md` (plans + overview) · `care-logging.md` (quick-log, duplicates, reason prompt, post-watering reminder) · `day-change.md`
- Screens: `care-queue.md` · `plant-list.md` · `plant-detail.md` · `watering-transparency.md` ("Why this date?") · `chart.md` · `navigation.md` · `dev-mode.md`
- Infra: `notifications.md` · `backup.md` (incl. backup-rule XMLs) · `photos.md` · `ci-build.md` · `release.md` · `adr.md` · `permissions.md`
- **Feature history** → `CHANGELOG.md` + `docs/decisions/` + `git log`. `docs/overview/` — standalone HTML architecture map + adaptive-watering math reference.
