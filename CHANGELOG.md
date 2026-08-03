# Changelog

All notable changes to YAPT are documented here.

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning: [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The implementer agent adds entries to `[Unreleased]` in every PR (dev workflow step 5).
The human promotes `[Unreleased]` → a versioned heading when cutting a release.

---

## [Unreleased]

### Added
- **Repotting reminder**: each plant can now have its own repotting interval (Add/Edit Plant → toggle with a slider, alongside watering and fertilizing). It's set in **months** (3–36, default 12), since repotting cadence is a seasons-and-years thing rather than a day-precise one. When set, an overdue/due-today repotting is included in the daily care notification, and logging a Repot care event resets the schedule. For a plant that has never been repotted, the first reminder is anchored to the plant's creation date plus one interval, so a newly added plant isn't flagged immediately (see product ADR-0022). Room DB migrated to v7; the interval round-trips through backup/restore (backup schema v8; older backups restore it as unset). A misting reminder was also built but deliberately dropped before release — misting does little for humidity and can encourage fungal leaf spot, so the app won't schedule it; you can still log a Mist care event manually. Free-text custom reminders remain a separate follow-up (#232)
- Settings → Reminders now has a **Notify for fertilizing** toggle. When turned off, the daily reminder no longer notifies for a plant whose *only* due care is fertilizing — but a plant that is also watering-due still gets its full reminder, fertilizing line included. Defaults to on (no change for existing users) and round-trips through backup/restore (backup schema v7) (#223)

---

## [0.20.1] - 2026-07-30

### Fixed
- CI: the release job's `Run release unit tests` step (`gradle testReleaseUnitTest`) was failing on `main` after the AGP 9 toolchain bump, because AGP 9.0 defaults `android.onlyEnableUnitTestForTheTestedBuildType` to `true` and no longer creates unit-test tasks for the release build type. Restored the pre-AGP-9 behaviour via `gradle.properties` so `testReleaseUnitTest` exists again; no user-facing change (#496)

---

## [0.20.0] - 2026-07-29

### Changed
- Build toolchain upgraded to **Android Gradle Plugin 9.3.1, Gradle 9.6.1, Kotlin 2.3.10, and KSP 2.3.10** (from AGP 8.13.2 / Gradle 8.14.5 / Kotlin 2.1.21). Newer AndroidX libraries require it — `androidx.core:core-ktx` 1.19.0 mandates AGP 9.1.0+ — and this bump also pulls in lifecycle 2.11.0, Compose BOM 2026.06.01, kotlinx-coroutines/serialization 1.11.0, and MockK 1.14.11. No user-facing behaviour changes; this is a developer/build-tooling update (#201)
- Developer tooling: added **Detekt** (with the `detekt-formatting` plugin, wrapping ktlint's formatting rules) for automated code-style and code-smell enforcement. Runs on every PR via a new `Run Detekt` step in the `test` CI job and fails the build on new violations. Configuration lives in `config/detekt/detekt.yml` (formatting rules on; the noisy `FunctionNaming` and `MagicNumber` rules are disabled as false-positives for `@Composable`/test naming and Compose `dp`/`sp` literals). Existing non-auto-fixable smells are frozen in `config/detekt/baseline.xml` so only newly-introduced issues fail. All auto-fixable formatting was applied across the codebase in the same change; no runtime behaviour changed (#85)

### Added
- Settings → **Appearance** (new section at the top): a **Theme** segmented control to choose **Light**, **System**, or **Dark**. "System" (follow the device) is the default and preserves the previous behaviour; the choice applies app-wide immediately and persists across launches and through backup/restore (backup schema v6) (#139)
- Backup/restore now includes the **Photo reminder** ON/OFF setting (Settings → Reminders), so moving to a new device no longer silently resets it to the default. Backup schema bumped to v5; older backups without the field restore it to the default (off) (#480)
- Full-screen photo viewer now shows each photo's exact capture/log date (e.g. "Jun 10, 2026") as a labelled chip near the bottom, below the "N / M" page indicator. The date is shown for every photo — including when there's only a single photo and the position indicator is hidden — and reads from the photo's own timestamp so swiping updates it per page. Grid thumbnails are unchanged (#445)
- Settings → Reminders: new "Combine reminders" toggle lets you get a single digest notification ("3 plants need care") instead of one notification per overdue/due-soon plant. Default is off (one per plant, unchanged behaviour); the combined notification opens the Plants list and doesn't offer the per-plant "Skip watering" action. Round-trips through backup/restore (backup schema bumped to v4) (#474)

### Fixed
- Bulk multi-select actions (bulk care logging, "Move to Graveyard", and its undo) now apply as a single atomic database transaction. Previously each selected plant was written one at a time, so a process kill mid-action could leave the batch partially applied (e.g. 3 of 5 plants archived); the change is invisible in normal use but prevents that inconsistent state (#448)

---

## [0.19.0] - 2026-07-23

### Changed
- Add Care Log: selecting the **Photo** care type now reveals **Take photo** and **Choose from gallery** buttons inline, right in the photo section, so a single tap goes straight to the camera or the system picker — no intermediate pop-up sheet and no separate add-photo tap. Other care types (where a photo is an optional attachment) keep the compact add-photo icon that opens the source sheet. The redundant "a photo is required" hint was dropped — the two prominent buttons make the requirement self-evident, and the Save button stays disabled until a photo is added. The camera permission rationale / settings deep-link behaviour is unchanged (#443)

### Fixed
- Full-screen photo viewer no longer reveals a thin strip of the PlantDetail screen under the status/notification bar. Everything behind the viewer is now painted solid black (including the status-bar area, with light status-bar icons on top), so it reads as one deliberate full-dark overlay; the close/delete buttons stay clear of the status bar (#444)

---

## [0.18.0] - 2026-07-21

### Added
- Plant list sort dropdown: new "Cared for today" entry (below the existing sort options) that filters the list to only plants with at least one care log recorded today (`loggedAt.toLocalDate() == today`, any care type — Water, Fertilize, Prune, Mist, Repot, Note, or Photo). Rows are ordered by most-recent care-log timestamp; the ASC/DESC toggle acts on that recency (DESC = most-recently-cared first, ASC = earliest-in-the-day first). The selection persists in DataStore alongside the other sort options, room filter chips still apply on top, no date-group headers are shown, and an empty state ("No plants cared for yet today") appears when nothing has been cared for today. No new database column or migration — computed from existing `care_logs` (#415)
- Plant list: tap and hold a plant to enter multi-select mode. A checkbox indicator appears on every plant card (cards keep the same height as normal) and the top bar switches to a contextual bar showing the selected count, a clear button, and a select-all action. A compact bulk action sheet slides up from the bottom immediately and stays up while you keep selecting, offering the bulk care actions as a horizontally-scrollable chip row (Water, Fertilize, Prune, Mist, Repot — mirroring the Add Care Log selector) plus an inline "Move to Graveyard", each applied to every selected plant. The Plants/Calendar bottom navigation is hidden while selecting so the sheet and the list above it get more room. Bulk care logs directly with sensible defaults (watering uses "Just right" feedback) and skips the per-plant interval-suggestion and photo-reminder dialogs; bulk "Move to Graveyard" asks for confirmation and shows an undo snackbar.

### Fixed
- `CareSchedule.computeSuggestedInterval()` no longer suggests a 0-day watering interval when two waterings land on the same calendar day (`JUST_RIGHT` branch). The result is now clamped to a minimum of 1 day for all feedback branches (#446)
- Watering history chart: displayed watering intervals are now floored at 1 day, so two waterings less than 24h apart no longer produce a sub-1 "Average interval" (e.g. "0.5 days") or a fractional chart point. The interval y-axis now shows whole-number, de-duplicated "Nd" labels instead of repeated truncated values like "0d" appearing twice (#446)

---

## [0.17.0] - 2026-07-15

### Added
- Plant Detail: the Watering and Fertilizing stat chips are now tappable to quick-log care in place, so common care actions no longer require opening the separate Add Care Log screen. Tapping the Watering chip opens the water-feedback bottom sheet (soil-state question) and logs a watering; tapping the Fertilizing chip logs a fertilizing directly for regular plants, or opens the combined "Water & fertilize" sheet for liquid-fertilizer plants (paired water+fertilize, ADR-0008/ADR-0017). Reuses the shared `QuickLogUseCase` and the interval-suggestion / photo-reminder dialogs already on the screen (photo reminder suppressed while the suggestion dialog is showing, matching PlantListScreen). The `+` FAB and full Add Care Log screen are unchanged for other care types (#434)

### Fixed
- Calendar: liquid-fertilizer plants in the day sheet now show a water-only quick-log button alongside the combined water+fertilize button, mirroring the plant list card. Previously the only quick action was water+fertilize, forcing a fertilizing log even on a day when only watering was due.

### Changed
- The quick-log water/fertilize buttons are now a single shared `QuickLogButtons` component used by both the plant list card and the calendar day sheet, so the two surfaces stay identical in appearance and behaviour. The calendar day-sheet buttons adopt the plant card's styling (vertical stack, combined water+fertilize button with a `+`).

---

## [0.16.0] - 2026-07-15

### Changed
- `CareSchedule`: a plant with a watering interval configured but no watering history is now reported as due today (instead of "Not scheduled"), so it shows the due-today treatment on the plant list, the daily reminder run, and the calendar; a `wateringDueDateOverride` (skip watering) still takes precedence. A plant with a fertilizing interval configured but never fertilized becomes due 30 days after the plant was added (`createdAt + 30d`), giving newly acquired plants a grace period before feeding, then follows normal overdue math thereafter. Plants with no interval configured remain "Not scheduled". The plant list chip still shows "Never watered" / "Never fertilized" until the first corresponding log exists — only the due-status/sort/calendar/notification treatment changes, not the chip wording (#428, supersedes #391)

---

## [0.15.1] - 2026-07-14

### Fixed
- Calendar: plants with liquid fertilizer enabled no longer show a standalone fertilize entry — since fertilizing happens together with watering (ADR-0008/ADR-0017), these plants now contribute to the Calendar only via their watering due date, and the day sheet no longer sorts them into "Overdue" solely because fertilizing is overdue (#423)

---

## [0.15.0] - 2026-07-13

### Added
- Calendar tab with month view showing plants due per day, with quick-log from the day sheet (#414)

### Fixed
- Photo reminder: taking a photo from the reminder dialog now also creates a `PHOTO` care log entry (not just a `plant_photos` row). The photo shows up in the plant's care history and as a marker on the watering-history chart, matching the "Add photo care log" flow (#416)

---

## [0.14.0] - 2026-07-10

### Added
- Plant list: date-group dividers (Overdue / Today / Tomorrow / dated / Later / Not scheduled) when sorting by Watering due, Fertilizing due, or Both due; toggling ASC/DESC reverses the whole group sequence, including Not scheduled moving to the front on ASC. Alphabetical and Recently added stay flat (#399)

### Changed
- Watering history chart line is now a smooth cubic (Catmull-Rom) curve through the watering points instead of straight zig-zag segments (#125)
- Photo reminder now also triggers after using the quick water/fertilize buttons on the plant list — not just when opening Plant Detail; the reminder still respects the Settings toggle, the 30-day interval, and the once-per-session-per-plant rule (shared with Plant Detail), and its "Take photo" button launches the in-app camera and saves to the plant's gallery (#407)

### Fixed
- Plant Detail (and every other screen with a back arrow): rapidly double-tapping the back button no longer leaves the app on a blank white screen. `popBackStack()` calls are now guarded on the owning back stack entry's lifecycle so a second same-frame tap no-ops instead of popping a second entry off the stack (#408)

---

## [0.13.0] - 2026-07-03

### Added
- Watering events now appear as individual water-drop icons on the watering history chart; the chart line connects each individual watering at day-level precision (instead of monthly averages) so each icon sits exactly on the line (#362 #366)
- Tapping a care event marker icon on the watering history chart now opens a popup showing the care type and the date(s) of the event(s) at that position (#363)
- Photo reminder: optional global toggle in Settings that prompts you to take a photo when a plant hasn't been photographed in 30 days; shown once per session per plant when opening Plant Detail; "Take photo" button launches in-app camera and saves to the plant's photo gallery (closes #233)

### Fixed
- Reminders now fire at the user-configured time instead of always at 09:00; `MainActivity` was calling `ReminderScheduler.schedule()` without hour/minute on every launch, resetting the periodic work to the 9:00 default

---

## [0.12.1] - 2026-06-20

### Fixed
- BackupManager: skip photo file cleanup after a successful DB transaction to prevent dangling URI references (fixes #175)
- build.gradle.kts: add file-existence guard for release signing config; revert `?: error(...)` env-var fallbacks to `?: ""` so `assembleDebug` never breaks on machines that have `release.keystore` but no env vars set (fixes #129)
- Skip watering stepper dialog: the +/− row now fills the dialog width with centred layout, visually balanced with the Cancel/Confirm action row (#170)
- Confirmed `quickLog()` clears `wateringDueDateOverride` on all WATER paths (direct quick-water, paired WATER for liquid-fert, `quickLiquidFertilizeWithFeedback`) — fix was previously landed in #264 and #345 (#211)

### Changed
- Care history list and Plant Graveyard now show an exact date (e.g. "Jun 10, 2026") for events older than 14 days; the PlantCard "last watered/fertilized" chips and Plant Detail stats always show relative days (#387)
- WateringHistoryChart: extract hardcoded date format patterns to named constants (fixes #109)
- Skip watering button on Plant Detail is now an `OutlinedButton` below the watering stats row; previously a plain `TextButton` (#170)
- Extracted `BackupManagerInterface` from `BackupManager` and injected it into `SettingsViewModel` constructor (default remains the real `BackupManager`) to enable unit-testing with a fake; `importBackup()` now keeps `isBackupInProgress` true while the `FutureSchemaWarning` dialog is shown; added 5 `SettingsViewModelTest` cases covering all `isBackupInProgress` state transitions (#372)
- Refactored `clusterMarkersByCx` in `WateringHistoryChart.kt` to use a named `PositionedMarker(cx, marker)` data class instead of `Pair<Float, CareEventMarker>`, improving readability (#359)

---

## [0.12.0] - 2026-06-15

### Added
- Plant Graveyard: deleting a plant now moves it to an archive; restore or permanently delete archived plants from Settings → Plant Graveyard (#329)

### Fixed
- Navigation is now blocked during backup export and import; a non-dismissable progress dialog prevents leaving the Settings screen mid-operation, avoiding corrupt exports and incomplete restores (#365)

---

## [0.11.0] - 2026-06-13

### Added
- Care event markers on the watering history chart: per-care-type Material icons drawn inside the chart (via Vico `Decoration` API) at the bottom of the plot area, positioned at day-level precision within each month column; same-day events stack vertically; markers scroll with the chart and update with time-range chip changes (#231)
- Care event icons now stack when logged on consecutive days (proximity-based clustering groups icons within 14 dp of each other, not just exact same-day events) (#355)

### Fixed
- Reminder could fire at 09:00 instead of the user-configured time on installs where the time picker was never explicitly confirmed; default reminder time is now written to DataStore on first launch so rescheduling paths always use the correct hour (#356)
- "Last: x days ago" on watering and fertilizing chips (PlantDetail, PlantList cards, care-log history) now uses calendar-day comparison instead of a rolling 24-hour window, so a late-evening care event correctly shows "Yesterday" the following morning (#351)
- Watering chart now updates immediately when a new watering is logged while the detail screen is open (#114)

### Changed
- CI: opt into Node.js 24 for all actions via `FORCE_JAVASCRIPT_ACTIONS_TO_NODE24=true` before June 16 deadline (#336)
- CI: remove redundant `ANDROID_HOME` env override from Gradle steps
- Code: replace deprecated `Icons.Filled.Notes` with `Icons.AutoMirrored.Filled.Notes`
- Code: suppress deprecated `statusBarColor` API in Theme.kt
- Tests: add `@OptIn(ExperimentalCoroutinesApi::class)` to ViewModel test classes
- Combined quick-water-fertilize button on liquid-fertilizer PlantCards now opens a feedback bottom sheet before logging, matching the standalone quick-water button behaviour; adaptive interval suggestion fires after save (#344)

---

## [0.10.0] - 2026-06-11

### Added
- Delete individual photos from the plant gallery: long-press a thumbnail or tap the trash icon in the full-screen viewer; deleting a care-log photo preserves the log entry (#306)

### Changed
- Saving a Photo care log entry now updates the plant's cover photo to the attached image (#304)
- Quick-water button on plant cards now opens a feedback bottom sheet (Still wet / Just right / Too dry) with Just right pre-selected; tapping Log in the default state still requires only 2 taps. Feedback other than Just right can now be recorded without opening the Add Care Log screen, and the adaptive watering interval suggestion fires from this path too (#126).

### Fixed
- Adding the same photo URI twice in Add/Edit Plant no longer shows a duplicate thumbnail (#317)
- `BackupSerializerTest.fullRoot()` now includes all `BackupPlant` fields so future nullable additions are caught by the round-trip test (#288)

---

## [0.9.0] - 2026-06-08

### Added
- Photo gallery: delete individual photos from the full-screen viewer; a confirmation dialog removes the photo from the care log entry (the log is preserved; the photo file stays on the device); if the deleted photo was the cover, the cover falls back to the next most-recent gallery photo (#306)
- Tapping the cover photo on Plant Detail opens the full-screen photo viewer (#307)
- Full-screen photo viewer now supports swipe left/right to navigate all gallery photos; opens at the tapped photo's index; shows a "2 / 5" position indicator when there are multiple photos (#308)
- Per-plant photo gallery: adding a photo in AddEditPlant now appends to a gallery instead of replacing the cover; Plant Detail shows a unified scrollable gallery of plant and care-log photos sorted by date, with a full-screen viewer on tap; backup/restore includes all gallery photos (#290)
- In-app camera capture for plant and care log photos; tapping the photo button shows a bottom sheet with "Take photo" and "Choose from gallery"; runtime CAMERA permission requested with rationale dialog on first denial and Settings deep-link on permanent denial; graceful Snackbar error on devices without a camera (#134)
- `AddEditPlantScreenTest` and `AddCareLogScreenTest`: 5 new Compose screen tests per screen covering camera paths — bottom sheet on photo button tap, gallery option visible, no-camera Snackbar, permission rationale dialog, permanent-denial settings dialog (#294)
- Robolectric migration test for MIGRATION_3_4 verifies plant_photos seeding from coverPhotoUri (#303)

### Fixed
- Photo care log entry: save button is now disabled (dimmed) until a photo is attached; an inline error hint is shown below the photo picker when `CareType.PHOTO` is selected and no photo has been chosen (#305)
- Unique constraint on `(plantId, uri)` in `plant_photos` prevents duplicate gallery entries; DB v4→5 (#301)

### Changed
- Extracted shared camera/permission/file-cleanup logic into `rememberCameraPhotoState` + `CameraPhotoDialogs`; `AddEditPlantScreen` and `AddCareLogScreen` each call the shared composable instead of duplicating the ~80-line camera block (#293)
- `BackupSerializerTest`: add assertion that `encodeDefaults = true` emits explicit `null` keys in serialized JSON, guarding against silent regression if the setting is ever removed (#59)
- `SettingsViewModelTest`: fix defaults tests to stub non-default DataStore values (`false`/`21`/`30`) so assertions can only pass if the DataStore mapping path was actually followed; remove untestable `null`-key tests where the fallback default equals the `stateIn` initial value (#63)
- `AddCareLogViewModelTest`: add `advanceUntilIdle()` before assertions in the edit-mode tests so synchronisation is explicit and not reliant on `UnconfinedTestDispatcher` eagerness (#64)
- `ReminderWorker.buildCareBody()`: move all five hardcoded notification body strings to `strings.xml`; overdue counts now use `R.plurals` resources consistent with existing plurals patterns (#281)

---

## [0.8.1] - 2026-06-04

### Added
- Care history on plant detail screen collapses to 5 most recent logs by default; a chevron chip below the list expands to show all logs and collapses back, with animated rotation (#253)

### Changed
- All hardcoded UI strings moved to `strings.xml`; extracted shared `SettingsItemRow` composable in SettingsScreen (#220, #272)
- Move quick-log else-branch Snackbar message to `strings.xml` (#248)
- `WateringFeedback` and `CareType` domain enums are now plain Kotlin enums; `displayName`, `emoji`, `icon` moved to `ui/util/EnumResources.kt` extension functions; all display strings routed through `strings.xml` (#276)
- `strings.xml`: remove 12 duplicate/redundant keys introduced in #274; update all call sites to canonical keys; rename `settings_back_content_description` → `cd_back` (#275)
- Reviewer NON-BLOCKING findings now tagged SMALL/LARGE; orchestrator asks human with a recommendation before filing a new issue or fixing in-PR (#259)

### Fixed
- WhatsNewSheet: "Got it" button is now always visible at the bottom of the sheet even when many release entries are present; `LazyColumn` constrained with `Modifier.weight(1f)` so it cannot push the button off-screen (#214)
- PlantCard fertilizing chip for liquid-fertilizer plants now shows a time-based label instead of static "With watering": shows "Due with next watering" when due/overdue, or the regular countdown ("In X days") when not yet due (#267)

---

## [0.8.0] - 2026-06-02

### Added
- Per-plant liquid fertilizer toggle on Add/Edit Plant screen; FERTILIZE logs with Liquid type auto-create a paired WATER log at the same timestamp (#56)
- Fertilizer type selector (Liquid / Solid chips) on the Add Care Log screen, pre-selected from plant default (#56)
- PlantCard and PlantDetail fertilizing chip shows "With watering" label for liquid-fertilizer plants; quick-fertilize button on the plant list card auto-creates a paired watering log for liquid-fertilizer plants (#56)
- Reminder notifications: liquid-fertilizer plants show "Fertilize with watering" in the watering alert instead of a standalone fertilizing notification (#56)
- What's New sheet now shows the full release history, grouped by version, newest first, and is scrollable (#212)
- "What's New" row in Settings — reopens the release history sheet at any time without affecting the auto-show trigger (#212)

### Changed
- Watering feedback chips reframed around observable plant/soil state: "Still wet" (was "Too soon"), "Just right" (unchanged), "Too dry" (was "Too late"); feedback question changed to "What did you find?" (issue #161)
- Move hardcoded quick-log content descriptions and Snackbar messages to strings.xml (issue #91)
- Move hardcoded SettingsScreen section-header strings to strings.xml (issue #158)
- Move hardcoded interval-suggestion AlertDialog strings to strings.xml (issue #154)
- WhatsNewSheet: move hardcoded UI strings (title, dismiss button, section headings) to strings.xml (issue #215)
- WhatsNewContent: enforce newest-first ordering by adding `versionCode` field to `ReleaseNotes` and sorting at render time (issue #219)
- CI: gate release job on `test` job (unit tests + lint); release job now also runs `testReleaseUnitTest` and `lintRelease` before producing the APK (#84)
- WateringHistoryChart: remove unreachable `coerceAtLeast(0)` on totalMonths (issue #113)
- Agent definitions in `.claude/agents/` refactored (#221, PR #222): removed dead `gh` CLI blocks; `reviewer.md` slimmed; trigger-style `description` and `model:` field added per agent; subagents granted read-only MCP GitHub tools
- BackupManager: add comment explaining why `CURRENT_SCHEMA_VERSION` was not bumped when `wateringDueDateOverride` was added (issue #188)

### Fixed
- `quickLog()` now clears `wateringDueDateOverride` after logging WATER (and liquid-fertilizer auto-paired WATER), matching `AddCareLogViewModel` behaviour (issue #210)
- PlantCard: add accessibility contentDescription to liquid-fertilizer quick-log button (issue #251)
- `SkipWateringReceiver.onReceive()` now guards on `intent.action == ACTION_SKIP_WATERING` before processing, consistent with `BootReceiver` convention (issue #178)
- Hardcoded strings in the skip-watering stepper dialog and button moved to `strings.xml`; day count uses a proper `pluralStringResource` resource (issue #179)
- "What's New" row title and subtitle in `SettingsScreen` moved from hardcoded literals to `strings.xml` entries (`settings_whats_new_title`, `settings_whats_new_subtitle`) (issue #216)
- CI: `gh release create` now passes `--target "${{ github.sha }}"` so the release tag is anchored to the exact main commit that triggered the push, not the default branch HEAD; fixes incorrect release notes when the repo's default branch is `develop`

---

## [0.7.2] - 2026-05-25

### Changed
- CI: release job now automatically creates a GitHub Release with the signed APK attached and auto-generated release notes on every push to `main`

---

## [0.7.1] - 2026-05-25

### Changed
- Dependency upgrades: AGP 8.7.3 → 8.13.2, Kotlin 2.0.21 → 2.1.21, KSP 2.0.21-1.0.28 → 2.1.21-2.0.2, Gradle 8.9 → 8.14.5, Compose BOM 2024.11.00 → 2026.05.01, Room 2.6.1 → 2.8.4, Lifecycle 2.8.7 → 2.10.0, Navigation 2.8.4 → 2.9.8, DataStore 1.1.1 → 1.2.1, WorkManager 2.10.0 → 2.11.2, core-ktx 1.15.0 → 1.18.0, activity-compose 1.9.3 → 1.13.0, desugar_jdk_libs 2.1.3 → 2.1.4, kotlinx-coroutines 1.9.0 → 1.10.1, kotlinx-serialization 1.6.3 → 1.8.1, Robolectric 4.13 → 4.16.1, Turbine 1.2.0 → 1.2.1; compileSdk bumped 35 → 36 (#16)

### Fixed
- BackupManager: restore no longer loads all photo bytes into memory at once; each photo is now streamed to a temp file during ZIP traversal and deleted immediately after copying to the destination, preventing OOM crashes on large backups (#193)
- BackupManager: temp photo files are now cleaned up when the user cancels the FutureSchemaWarning dialog; `onDismiss` callback added to `FutureSchemaWarning` and called from all dismiss paths in SettingsScreen (#195)
- BackupManager: partial temp photo file no longer orphaned in `cacheDir` if `copyTo` throws mid-write; map entry is inserted before the write so the outer `finally` can always reach the file (#196)
- BackupManager: export with photos to cloud SAF destinations (e.g. Google Drive) no longer produces a broken 0 KB ZIP; the full ZIP is now written to a local temp file first, then streamed to the destination URI in a single copy; photos restored from a previous import (stored as bare absolute paths) are now opened via `FileInputStream` so they are no longer silently skipped during re-export (#144)

---

## [0.7.0] - 2026-05-24

### Added
- Location suggestion chips on Add/Edit Plant screen: previously used room names appear as tappable chips below the Location field; tapping fills the field with the exact stored string; chips with a case-insensitive match to the current field text are highlighted (#137)
- Skip watering: tap "Skip watering" on the plant detail screen to push the next due date forward 1–7 days via a stepper dialog; the app then asks whether to permanently extend the watering interval (#168, #169)
- `wateringDueDateOverride` column on plants: when set, the effective due date is `max(computed, override)`; cleared automatically when a watering is logged (#169)
- "Unassigned" filter chip on plant list: shows only plants without a room assigned; chip is hidden and selection resets to "All" when all plants have rooms; single shared `getAllPlants()` Room subscription via private `allPlants` StateFlow; auto-fallback test added (issues #183, #184)

### Fixed
- Fix #180: `CareSchedule.daysBetween()` now uses calendar-day arithmetic (`ChronoUnit.DAYS.between`) instead of millisecond division, eliminating the spurious "interval − 1" suggestion when watering exactly on the due day with Just Right feedback.
- Watering history chart no longer shows "not enough data" or a blank area for infrequently-watered plants: predecessor outside the range window is used to anchor the first in-window interval; when no waterings fall inside the window the last two pre-range waterings produce an interval; single data points (2 total waterings) now render as a visible circle dot via Vico `PointProvider` (#117)
- Backup error message when importing a file without backup.json is now readable — was "not compatible File" (#38)
- Reminder schedule now updates to the restored time immediately after importing a backup (#41)
- Orphaned photo files are cleaned up when a backup restore fails mid-import (#35)
- Unreadable photos are silently skipped during export instead of producing malformed zip entries (#40)
- Backup export now fetches all care logs in a single query instead of one per plant (#36)
- Backup & Restore UI strings moved to strings.xml (#39)

### Changed
- CLAUDE.md dev workflow: reviewer step now correctly names `mcp__github__issue_write` for NON-BLOCKING findings; documents the 3-step inline comment API flow; notes that `APPROVE` and `REQUEST_CHANGES` are both blocked on same-account PRs — use `COMMENT` event; step 5 clarifies that `chore:`/docs-only PRs may omit CHANGELOG and `WhatsNewContent.kt` entries (#173, #174)

---

## [0.6.0] - 2026-05-20

### Added
- What's New bottom sheet — shown on first launch after each update, summarising changes for that version (issue #147)
- Interval suggestion shown as an editable AlertDialog instead of a Snackbar — tap Apply or adjust the value before confirming (issue #138)
- `CHANGELOG.md` — feature history now tracked per release (issue #143)
- MIT License file and README license section
- Keep screen on toggle in Settings — screen stays awake while the app is in the foreground; preference persists and round-trips through backup/restore (issue #140)

### Changed
- Keystores managed via GitHub Actions secrets; release signing set up in CI (issue #127)

### Fixed
- Overdue plants always show "Overdue" (not "Due today") when the due date has passed, regardless of time of day (issue #136)
- Adaptive watering interval suggestions (JUST_RIGHT / TOO_SOON) now correctly reflect the actual vs. stored gap (issue #105)
- TOO_LATE feedback: suggestion base is clamped to the stored interval when the user waters late, keeping the suggestion within the expected range (issue #159)
- StatChip no longer shows a "next:" prefix when care is overdue (issue #151)
- Soft keyboard no longer obscures the Notes field on AddEditPlant and AddCareLog screens (issue #135)
- Due-date comparisons now use calendar-day granularity — a plant watered at 08:00 no longer goes overdue at 08:01 on day 7 (issue #141)

---

## [0.4.2] - 2026-05-14

### Fixed
- Room now hard-crashes at startup if a DB schema version bump ships without an explicit `Migration` object; `1.json` baseline schema committed (issue #8)

---

## [0.4.1] - 2026-05-14

### Added
- "Water + Fertilize due" combined filter in the sort dropdown — shows only plants where both watering and fertilizing are due or overdue (issue #78)
- Unique per-plant notification IDs; tapping a reminder deep-links directly to that plant's detail screen (issue #7)

---

## [0.4.0] - 2026-05-13

### Added
- Larger plant images — 90 dp edge-to-edge thumbnail on list cards; 280 dp hero image bleeding behind the status bar on the detail screen (issue #29)

---

## [0.3.0] - 2026-05-11

### Added
- Watering history line chart on the plant detail screen (Vico); time-range chips 1M / 3M / 6M / 12M / All; auto-scrolls to the latest data (issue #18)

### Changed
- Release builds now enable R8 minification and resource shrinking (issue #4)

### Fixed
- Photo gallery refactored to accept a URI list instead of a `CareLog` list (issue #6)

---

## [0.2.0] - 2026-05-08

### Added
- Version management — `version.properties` file and `bump-version` GitHub Actions workflow
- Sort-order controls on the plant list: Alphabetical, Watering due, Fertilizing due, Recently added — persisted via DataStore (issue #21)
- Countdown labels on plant list cards and the detail screen: "In X days", "Due today", "Overdue by X days" with colour coding (issues #32, #55)
- Quick Water and Fertilize buttons on each plant card (issue #19)
- "Water + Fertilize due" filter in sort dropdown (issue #78)

---

## [0.1.0] - 2026-04-30

### Added
- Plant library — add, edit, and delete plants with a cover photo
- Care logging — WATER, FERTILIZE, PRUNE, MIST, REPOT, NOTE, PHOTO types with timestamps and optional notes
- Adaptive watering interval — the app suggests adjusted intervals based on user feedback (Too soon / Just right / Too late) after each watering
- Daily care reminders via WorkManager — survives process death; configurable time in Settings
- Settings screen — notifications toggle, daily reminder time picker
- Nature-themed Material 3 light/dark theme
- Local backup and restore — export and import a `.yapt` ZIP via SAF with optional photo inclusion (issue #22)
- Photo gallery per plant; care history timeline
- DataStore preferences for all user settings
- GitHub Actions CI/CD — debug APK on every push; release APK on push to main
- In-place APK upgrade support via a committed debug keystore (issue #17)
- Custom care log dates and the ability to edit existing log entries (issue #20)
- Default watering-feedback chip pre-selected to "Just right" on new WATER logs (issue #30)
- Post-restore navigation: after a successful restore the app navigates to the plant list and shows a count Snackbar (issue #37)
- Phase 1 unit tests: `CareSchedule` and `DateUtils`; JaCoCo coverage enabled (issue #46)
- ViewModel unit tests for all five screens using MockK + Turbine (issue #48)
- BackupManager instrumented integration tests (issue #50)
- Compose / UI screen tests for all five screens (issue #51)
- CI instrumented tests on PRs when relevant files change (issue #87)
- Instrumented tests run on PRs that touch app source or test source (issue #87)
