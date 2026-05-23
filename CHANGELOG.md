# Changelog

All notable changes to YAPT are documented here.

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning: [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The implementer agent adds entries to `[Unreleased]` in every PR (dev workflow step 5).
The human promotes `[Unreleased]` → a versioned heading when cutting a release.

---

## [Unreleased]

### Added
- Location suggestion chips on Add/Edit Plant screen: previously used room names appear as tappable chips below the Location field; tapping fills the field with the exact stored string; chips with a case-insensitive match to the current field text are highlighted (#137)
- Skip - too soon action on watering notifications and plant detail screen: extends the watering interval by 1 day without logging a care event; Snackbar confirms the new interval (#33)
- "Unassigned" filter chip on plant list: shows only plants without a room assigned; chip is hidden and selection resets to "All" when all plants have rooms; single shared `getAllPlants()` Room subscription via private `allPlants` StateFlow; auto-fallback test added (issues #183, #184)

### Fixed
- Watering history chart no longer shows "not enough data" or a blank area for infrequently-watered plants: predecessor outside the range window is used to anchor the first in-window interval; when no waterings fall inside the window the last two pre-range waterings produce an interval; single data points (2 total waterings) now render as a visible circle dot via Vico `PointProvider` (#117)

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
