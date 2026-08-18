# YAPT – Yet Another Plant Tracker

Offline-first Android app for houseplant care. No cloud, no accounts, no telemetry. Users log care
events (water/fertilize/prune/repot/note/photo); the app surfaces overdue reminders and adapts
watering intervals from the user's own feedback.

## Tech Stack
- Kotlin · Jetpack Compose + Material 3 (nature palette) · MVVM + Repository
- Room (SQLite, offline-first) · DataStore (prefs) · WorkManager + NotificationManager · Coil 2
- Compose Navigation (type-safe `Screen` sealed class) · manual DI via `YaptApplication` lazy singletons (no Hilt)
- Build: AGP 9.3.1, Kotlin plugins 2.4.10, KSP 2.3.11, Gradle 9.7.0, Compose BOM 2026.06.01; compileSdk 37 / targetSdk 35 / minSdk 26

## Commands
```bash
./gradlew compileDebugKotlin compileDebugUnitTestKotlin compileDebugAndroidTestKotlin   # compile
./gradlew testDebugUnitTest        # unit tests (flag also builds the release suite — see rules/ci-build.md)
./gradlew lintDebug                # Android lint
./gradlew detekt                   # static analysis (add autoCorrect=true locally to auto-fix formatting)
```
Prefer `-q` and grep for failures over dumping full build logs. Cloud/session build setup: `.claude/rules/ci-build.md`.

## Architecture
```
data/{db,entity,repository}   Room DAOs, @Entity, repos (entity↔domain mapping — UI never touches entities)
domain/{model,schedule,usecase,…}  Plant/CareLog/CareType…; CareSchedule (pure logic); QuickLogUseCase (shared quick-log)
notification/                 channel creation + POST_NOTIFICATIONS helper (NotificationPermission)
ui/{components,navigation,screens,theme}   5 screens; Screen sealed class; NavGraph
util/                         DateUtils, ImageUtils
worker/                       ReminderWorker, ReminderScheduler, BootReceiver
```
- Manual DI: `YaptApplication` builds DB + repositories as lazy singletons; `NavGraph` passes them into each ViewModel's inner `Factory`.
- Every ViewModel has an inner `Factory`; screens obtain it via `viewModel(factory = …)`.

## Conventions (beyond what the linter enforces)
- **StateFlow** for UI state; **SharedFlow** for one-shot events. Always `collectAsStateWithLifecycle()` (never `collectAsState()`).
- **Enums stored as String** in Room — read with `runCatching { Enum.valueOf(...) }.getOrDefault(fallback)`, never plain `.valueOf()`. Display strings/icons live in `ui/util/EnumResources.kt`, not on the enum.
- **Dates** — `DateUtils.formatRelative()` for all display; never compute `(now-ts)/86_400_000` inline. Calendar-day comparisons via `Long.toLocalDate()` (technical ADR-0013).
- **Same-day WATER/FERTILIZE duplicates are rejected**, not PRUNE/REPOT/NOTE/PHOTO/MIST — `CareLogRepository.hasLogOfTypeOnDay(plantId, careType, dayTimestampMs, excludeLogId)` is the single query (DAO-level `countLogsOfTypeOnDay`, no schema change). `QuickLogUseCase` is the one choke point for all four quick-log surfaces; `AddCareLogViewModel` has its own equivalent guard since it doesn't go through that use case. Always check *before* a paired liquid-fertilizer WATER insert, never just against the sibling insert in the same call (#509).
- **No `libs.versions.toml`** — versions inlined in `app/build.gradle.kts`; the Compose BOM governs Compose artifacts.
- **DataStore delegate** (`val Context.settingsDataStore by preferencesDataStore(...)`) must be declared at **file top-level** in `YaptApplication.kt`, never inside a class — required by the AndroidX DataStore API (technical ADR-0009).
- **Room migrations are mandatory** — explicit `Migration`s only, hard-crash if one is missing (`fallbackToDestructiveMigration` is never used). Any schema change ships with a `Migration` and a committed schema JSON in `app/schemas/` (technical ADR-0002).
- **All UI strings in `strings.xml`** — no hardcoded strings in Compose. `cd_back` is the canonical back-button description.
- **Room schema** exported to `app/schemas/` via KSP — commit schema JSON when bumping DB version. `PlantDatabase.DB_VERSION` is the single source (also feeds `@Database(version=…)`), so the two can't drift.
- **Compose UI tests assert user-visible semantics** (contentDescription/stateDescription/text/actionable), **never** tree structure (child counts, testTag topology). A testTag never merges past a clickable/merged ancestor. If a fix is about announcements, assert the announcement — not the topology (#420).
- **Two-strikes rule** — after two failed fix attempts on the same test, stop pushing variants. Re-derive the mechanism from framework source/docs (or a minimal repro), and reconsider whether the test asserts the wrong thing (structure vs. contract) (#420).
- Palette: SageGreen `#6B8F71`, WarmCream `#F5F0E8`, EarthBrown `#795548`; status OkGreen/WarnOrange/OverdueRed in `Color.kt`.

## Architecture Decision Records
Decisions live in `docs/decisions/{product,technical}/`. **Consult the relevant ADR before working in a covered area; never refactor a pattern a technical ADR describes without a superseding decision.** When a PR records a significant new product/technical decision, write a new ADR from `docs/decisions/template.md` (Status `accepted`, numbered sequentially). If a request contradicts an ADR, name it and its rationale and get human confirmation first; the only permitted edit to a finalized ADR is its Status line → `superseded by [ADR-XXXX](file.md)`.

## Development Workflow
**Issue-first (always):** on any feature request or bug report, first create a GitHub issue via `mcp__github__issue_write`, share the link, and wait for explicit go-ahead before writing any code, branch, or PR.

**Model & cost:** run the orchestrator on **Sonnet for routine issues**; switch to Opus only for genuinely hard, cross-system reasoning. Subagents are model-pinned in their frontmatter (`spec`/`implementer`/`reviewer`/`qa` → Sonnet), so the pipeline stays off Opus by default.

**The full pipeline below is the default.** A narrow fast-path exception exists for a change that is **both** mechanical **and** confined to a single file (a typo, a string tweak, a *pinned-version* dependency bump, a comment, a doc edit — never a logic change, and never an unpinned/range dependency version): that case **skips step 1 (Spec) and step 4 (QA)** only. Step 3 (Review) still always runs — CI passing is not a substitute for a review pass, since e.g. an unpinned new dependency compiles green but is a BLOCKING reviewer finding. If a change doesn't clearly meet *both* conditions, run the full pipeline — the fast-path is the exception, not the default to justify skipping steps from. Don't add explicit "double-check your work" or re-verification steps beyond this — the model self-corrects; verification lives in CI and the review round, not in duplicated passes.

1. **Spec** (`spec` agent) — scans product ADRs, interviews the human, posts clarifications on the issue; appends a `## Suggested sub-tasks` split when scope spans 3+ shippable layers. Skipped on the fast-path.
2. **Implement** (`implementer` agent) — writes code, pushes a `claude/*` branch, returns the PR title/body as text; the **orchestrator** opens the PR targeting `develop` (pre-authorized — no need to ask). Merging still requires a human.
3. **Review** (`reviewer` agent, read-only — can't post) — findings tagged **BLOCKING** / **NON-BLOCKING (SMALL|LARGE)**; the orchestrator posts them. Each round is a fresh standalone review; max 2 rounds, then wait for the human. Self-review must use `event: COMMENT` (APPROVE/REQUEST_CHANGES are blocked for the same account). For NON-BLOCKING, the orchestrator asks the human (recommend in-PR fix for SMALL, new issue for LARGE) before acting. **Never skipped**, including on the fast-path.
4. **QA** (`qa` agent, read-only) — validates the **acceptance criteria CI doesn't already cover**; build/tests/lint are the CI gate, not re-run here. Orchestrator posts the checklist. Skipped on the fast-path — when skipped, the orchestrator goes straight to human review once step 3 approves and CI is green (there is no QA `NEXT: human | action: merge PR <N>` signal to wait for in that case).
5. **Update docs** — implementer updates this file, `CHANGELOG.md` `[Unreleased]`, and `WhatsNewContent.kt` **in the feature PR, before merge** (`chore:`/docs-only PRs may omit the CHANGELOG + What's New entries).
6. **Merge** — **human only**; Claude never merges.

**Auto-review on green CI:** after opening the PR, `subscribe_pr_activity`; whenever new commits land **and** that PR's CI is green, auto-launch the next reviewer round (still capped at 2). If CI is red, diagnose and re-kick rather than reviewing.

**Comment cadence** — one comment per phase, in order: spec→issue (`add_issue_comment`); each review round→PR inline review (`pull_request_review_write` + `add_comment_to_pending_review`); QA→PR; summary→PR.

Full release-cutting steps: `.claude/rules/release.md`.

## Git Workflow
One branch and one PR per change — never mix unrelated work. Always branch from freshly-fetched `origin/develop`:
```bash
git fetch origin develop && git checkout -b claude/<kebab-desc> origin/develop
```
PR targets `develop`. Return to an up-to-date `develop` before starting anything new. `gh` is not installed — use `mcp__github__*` tools.

## Permissions (hard rules enforced by `settings.local.json`)
| Action | Permission |
|---|---|
| Read files · read-only git · `add`/`commit`/`stash`/`cherry-pick` · checkout/push `claude/*` · `./gradlew *` | Allowed, no prompt |
| `mcp__github__*` **writes** (issue/PR/review/comment/create_pr) | **Orchestrator only** — subagents return text, orchestrator posts |
| `git checkout develop` · `git push origin develop` · `git push --force origin claude/*` | Prompts — approve when appropriate |
| `git checkout main` · `git push origin main` · force-push main/develop · `git reset --hard` | **Forbidden** — blocked mechanically |
| Merging PRs by any means | **Forbidden** — human only |

## Pointers (load on demand — path-scoped rules load only when you touch matching files)
- `.claude/rules/schedule.md` — CareSchedule status + adaptive-interval rules
- `.claude/rules/notifications.md` — ReminderWorker, composer, notification toggles
- `.claude/rules/backup.md` — `.yapt` export/import + backup schema versions
- `.claude/rules/chart.md` — Vico watering-history chart internals
- `.claude/rules/plant-detail.md` — per-action tabs, inline settings, insights, feature flags
- `.claude/rules/dev-mode.md` — developer mode, feature-flag registry, debug actions
- `.claude/rules/ci-build.md` — AGP 9 toolchain, Detekt, cloud/session build setup
- `.claude/rules/release.md` — release-cutting workflow
- **Feature history** → `CHANGELOG.md` + `docs/decisions/` + `git log`. Not duplicated here.
