# Changelog

All notable changes to YAPT are documented here.

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versioning: [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The implementer agent adds entries to `[Unreleased]` in every PR (dev workflow step 5).
The human promotes `[Unreleased]` → a versioned heading when cutting a release.

---

## [Unreleased]

### Added
- **Plant List sort menu gains an "Active issues" filter** — a new `ACTIVE_ISSUES` `SortOption` value narrows the plant list to only plants with at least one active `PlantIssue` (`activeIssueCount > 0`), following the exact same filter-like-sort pattern as `BOTH_DUE`/`CARED_FOR_TODAY`. Combines with the existing room filter (AND, inherited automatically since room filtering happens upstream of `applySortOrder()`). Persists to DataStore like every other `SortOption` value. No new reactive Flow — reuses `PlantCareStatus.activeIssueCount` exactly as already computed by `buildStatus()`, so it shares the same staleness window as the `IssuePurple` badge on `PlantCard` (#617)
- **Repotting or moving a plant to a different room now resets the adaptive watering model's learned confidence, and a cold-start estimate bootstraps the model from a plant's own watering history** — a `REPOT` care log or a real `Plant.room` change (not the first time a blank room is filled in — that's data entry) resets `wateringConfidence` to 0, gated on the `adaptive_watering` flag; a repot additionally freezes live per-observation learning for 4 weeks (still logged/charted normally) since a freshly repotted plant's water use is genuinely atypical while it re-establishes. Separately, `CareSchedule.bootstrapBaseInterval()` estimates a season-neutral base interval and confidence from median de-seasonalized historical gaps (`median(gap / season)`, `confidence = min(5, gaps / 3)`) — applied once there are ≥ 3 gaps, either the first time the adaptive model evaluates a plant with existing history, or after enough history accumulates past a reset's freeze window. Both the resets and the bootstrap appear in the "Recent adjustments" list on the "Why this date?" sheet with a plain-language reason. New `wateringResetAt`/`wateringFreezeUntil` columns (`MIGRATION_12_13`, DB v12→13; `.yapt` backup schema v13→v14) ship unconditionally regardless of the flag's state (#571, technical ADR-0023)
- **Developer-mode demo dataset grows from 8 to 10 plants, to manually exercise #571** — ZZ Plant and Rubber Plant ship with a pre-adapted `wateringConfidence` (3 and 4) so logging a REPOT on the ZZ Plant, or moving the Rubber Plant to a different room, immediately shows the confidence reset (with or without the 4-week freeze, respectively) without first needing real watering history to build confidence up from scratch. Aloe Vera also gains a pre-adapted confidence (2) alongside its existing unassigned room, to demo that assigning a room for the first time does *not* reset it. The other 8 plants are unchanged and already cover the cold-start bootstrap: any of them with enough watering history and `wateringConfidence == null` bootstraps on its next WATER log once `adaptive_watering` is on (#571)

### Fixed
- **The "Water every N days?" suggestion dialog no longer compares a base-space suggestion against an effective-space "current" interval, on Plant Detail, Calendar, Plant List, and AddCareLogScreen's save flow alike** — with `adaptive_watering` and `seasonal_watering` both on, the dialog's "suggested" number was `QuickLogUseCase`'s adaptive suggestion, computed in season-neutral base space, shown directly alongside `plant.wateringIntervalDays`, which is already seasonally adjusted. Comparing the two in different unit spaces produced misleading multi-day jumps (e.g. "suggested 9, current 7" for a plant only 1 day off schedule) that were almost entirely a unit-mismatch artifact. `QuickLogUseCase.computeSuggestion()` is now the single choke point that converts the suggestion to effective space (via the same `CareSchedule.effectiveWateringIntervalDaysForDisplay()` conversion the "Why this date?" sheet already uses) and gates on that converted value — `QuickWaterSuggestion` now carries both the raw base-space `suggestedInterval` (write path, unchanged) and the new `suggestedIntervalEffective` (display/gating), so the Calendar and Plant List dialogs, which share this exact use case, are fixed by construction rather than needing their own copy of the conversion. `PlantDetailViewModel`'s own suggestions (from its quick-water/quick-fertilize actions) are converted downstream by a new `pendingWateringSuggestion` `StateFlow` that bundles the raw, effective, and current numbers into one atomically-updating value — replacing an earlier two-`StateFlow` version of this same fix that had a theoretical one-frame race where the dialog could flash an unconverted number before the converted value caught up; `PlantDetailScreen`'s now-redundant `LaunchedEffect` that used to pre-emptively clear a "stale" raw-space suggestion (a pre-existing check using the old, wrong base-vs-effective comparison) is removed, since `pendingWateringSuggestion` already suppresses the same case correctly on its own and the old effect could otherwise discard a suggestion that was genuinely different in effective space. `AddCareLogViewModel.computeSuggestedInterval()` (the AddCareLogScreen save flow, which computes its own suggestion rather than going through `QuickLogUseCase`) gains the identical effective-space gate — without it, a pure unit-mismatch artifact could reach `plant.wateringIntervalDays` ungated whenever `askBeforeChangingIntervals` is off, since that silent-apply path has no other gate to catch it. In every case the dialog no longer even appears (nor is anything silently written) when the whole apparent change was just a unit mismatch. The editable field a user can retype before tapping Apply, and the Apply/commit write path itself, are unchanged everywhere — display/gating-only fix (#620)

---

## [0.25.1] - 2026-08-30

### Changed
- **Water/Reschedule watering row and Fertilize's action button reverted to plain 16dp margins, matching every other card on Plant Detail** — #604's wide `ROW_START_INSET`/`ROW_END_INSET` (64dp/88dp, 152dp total) looked visibly broken next to every sibling card's 16dp. Instead, the pinned Edit icon button now fades out once you've scrolled substantially past the hero photo, removing the one collision risk that mattered most (Edit sits opposite the row's off-center Reschedule/Fertilize buttons); Back and the "Log care" FAB stay pinned and clickable throughout scrolling, as before. This knowingly re-accepts a narrow residual touch-collision risk with Back/FAB when the row happens to be scrolled flush against a screen edge — a deliberate visual-consistency trade-off, not an oversight (technical ADR-0022, narrows ADR-0018) (#610)
### Fixed
- **"Soil still moist" reschedule no longer silently reverts the new due date when adaptive watering is on** — `QuickLogUseCase.recordStillMoistCheck()` wrote the new `wateringDueDateOverride` in one `updatePlant()` call, then (when `adaptive_watering` was enabled and confidence changed) issued a second `updatePlant()` call built from the stale pre-write `Plant` snapshot, whose `.copy()` silently reverted the override that had just been persisted — so a plant rescheduled via the in-app Reschedule dialog or the notification's "Still moist" action kept showing as due/overdue. The override write and the confidence write are now combined into a single `updatePlant()` call built off the same up-to-date state, so this class of clobber can't recur. No DB migration, no behavior change to the adaptive model's math or to the `watering_adjustments` row it writes (#612)

---

## [0.25.0] - 2026-08-30

### Changed
- **Plant Detail's Water / Reschedule watering row is now always visible, not just when watering is due** — the row's only remaining gate is whether the plant has a watering interval set at all. Previously the row (both buttons) was omitted entirely on any day before the plant's due date; since Reschedule watering has no other entry point in the app, that made the whole reason-prompt → date-dialog flow (including the "Soil still moist" adaptive-observation path) completely unreachable while a plant wasn't yet due. Water still has the classic layout's `StatsRow` chip as a fallback, but Reschedule did not — this was a full outage of that flow, not a minor inconvenience. With the `plant_detail_tabs` flag on, the now-redundant `StatsRow` quick-log summary above the tab strip is removed (its watering chip duplicated the always-visible Water button; its fertilizing chip is replaced by a new always-visible action button under the Fertilize tab, with no "reschedule" equivalent since fertilizing has none) — the "last watered"/"last fertilized" at-a-glance text that removal dropped is restored via the tabs' per-tab insights card instead. The classic layout (flag off) is unchanged — `StatsRow` stays exactly as before. No DB migration, no behavior change to the underlying quick-log/reschedule logic itself. Recorded as product ADR-0031, amending ADR-0029/ADR-0030's due-status visibility gate without superseding either (#603)
- **The off-schedule watering reason prompt now words itself for the direction the schedule was missed** — asking *"Why now?"* is fine when you water early, but reads as an accusation once a plant is overdue, and *"Just my timing"* claims a deliberate choice that forgetting never involves. A watering whose gap has already run long now asks **"Why was it late?" — "It was dry by then" / "Forgot, or no time"**, naming forgetting outright instead of hoping you recognise yourself in an abstraction; the early case keeps **"Why now?" — "The plant needed it" / "Just my schedule"**. Both directions still record exactly the same two things — that it was about the plant, or that it wasn't — so what the adaptive model learns is completely unchanged; only the words differ. The direction comes from the same gap-versus-interval comparison that decides whether to ask at all, deliberately not from whether the plant reads as "overdue", since rescheduling moves the due date while leaving the gap long (#586)
- **Water/Reschedule watering row visual polish, on the same buttons #603 made always-visible** — with the `plant_detail_tabs` flag on, the row (and Fertilize's action button) now renders above the inline interval settings card on its tab instead of below, so it's the first thing you see. Water is now a filled, primary-colored button with a leading water-drop icon (was outlined, no icon); Reschedule watering is now an icon-only outlined button (a clock-with-plus icon, `contentDescription` only — no visible label), freeing up the row's width for Water. Purely visual — no behavior change to either action. Because this row (and Fertilize's action button) is now the first item under its tab, it can scroll flush against any edge of the screen's own permanently-pinned Back/Edit/FAB buttons; both rows now carry wider leading and trailing insets (`WateringDueActions.kt`'s `ROW_START_INSET`/`ROW_END_INSET`) so a flush-scrolled tap can never silently land on one of those pinned buttons instead (#603, #604)

---

## [0.24.0] - 2026-08-28

### Changed
- **Plant Detail's Custom Reminders and Active Issues cards moved into their own tabs, behind a collapsible tab row** — with the `plant_detail_tabs` flag on, the "Custom reminders" and "Active issues" cards are no longer always-visible; they're now their own tabs (Reminders, Issues), appended after Water/Fertilize/Repot/Photo. Since 6 tabs don't fit one row at each tab's current width, the tab row now starts **collapsed** (today's 4 tabs, pixel-identical), with a small chevron toggle to expand and reveal the 2 new tabs on a wrapped second row — no shrinking, no horizontal scrolling. A small badge appears on the toggle, but only while collapsed, when there's an active issue or an overdue custom reminder, so collapsing the row can't silently hide something urgent. Collapsing while viewing a now-hidden tab returns you to the Water tab. The classic layout (flag off) is unaffected — those cards stay always-visible there exactly as before. Purely a UI reorganization; no behavior change to reminders/issues themselves, no DB migration (#590)
- **Plant Detail's watering-due surface narrowed to two actions — Water / Reschedule watering — with a reason prompt whenever the action is off schedule** — replaces the three-action row (#508) shipped days earlier. "Did water go in, or not?" is a fact, not a judgement, so you no longer have to work out *why* you are deferring in order to pick a button; the app asks afterwards, and only when it needs to. Watering on schedule (within the same ±15% tolerance the app already uses to decide the schedule matched reality) prompts for nothing and logs immediately. Watering early or late asks **"Why now?" — "The plant needed it" / "Just my timing"**; rescheduling always asks **"Why put it off?" — "Soil still moist" / "I can't right now"**. The answer alone decides whether the observation reaches the adaptive watering model: timing cannot tell "the plant was thirsty" from "I was away", so the app stops guessing. **Still moist is no longer a top-level button** — it is now the "Soil still moist" answer, writing the same `CareType.CHECK` log through the same code path as before, so nothing about its data model changed. Also fixes the flat 1-day Still-moist deferral that could never clear "due" for a plant overdue by two or more days: the deferral is now derived from the interval the model lands on after the observation (the date picker opens on it in-app; the notification applies it directly), and **how long you reschedule for never affects what the app learns**. Declining to answer, or dismissing a prompt, records no signal at all — including through the passive gap-learning channel, so a pre-emptive holiday watering marked "just my timing" can no longer quietly shorten a plant's interval. With `check_reminders` on, the reminder notification now offers a fixed **Watered · Still moist · Not now**, the same three however overdue the plant is. No Room migration and no backup schema change — the reason reuses the existing watering-feedback field (product ADR-0030, supersedes ADR-0029) (#586)
- **Fertilizing interval slider extended from a 90-day max to 180 days** — supports long-term/slow-release fertilizers with feeding intervals beyond three months, on both the Add/Edit Plant screen and Plant Detail's Fertilize tab inline setting. The watering interval slider (1-60 days) is unaffected. No range constraint existed on `fertilizingIntervalDays` elsewhere (schedule computation, reminders, backup), so this is a pure UI-bound change with no DB migration (#588)
- **Plant Detail's watering-due surface reworked into three actions: Water / Still moist / Reschedule watering** — replaces the old single "Skip watering" button (both the classic layout and the Water tab) with a row of three, each meaning exactly one thing. **Water** opens the same feedback sheet the watering stat chip already uses. **Still moist** ("I checked; not ready yet") is now reachable in-app for the first time, not just from the #570 check-reminder notification — it routes through the same `QuickLogUseCase.recordStillMoistCheck()` the notification action calls, so both produce identical logs and identical effects on the adaptive model. **Reschedule watering** (renamed from "Skip watering", including the matching notification action label) replaces the 1-7-day stepper with **Today** (disabled when already due today, enabled when overdue) / **+1 day** / **+2 days** / **+3 days** / **Custom date…** (a date picker that excludes past dates); every option still only pushes `wateringDueDateOverride` — never the stored interval or the adaptive model's confidence — and, unlike the previous skip flow, no longer triggers the "apply this as your new interval?" follow-up dialog afterward. Removed the unused, dead-code duplicate `SkipWateringReceiver` under `worker/` that had directly mutated the watering interval (product ADR-0029, supersedes ADR-0007) (#508)

### Added
- **"Why this date?" watering transparency sheet, with an "Ask before changing intervals" setting** — a new sheet, reachable from the Plant Detail Water tab's inline settings card, shows exactly how the next watering date was derived: base interval + "learned from N waterings", the seasonal multiplier (when `seasonal_watering` is on and the plant isn't pinned), the effective interval, last watered, and a labelled confidence indicator ("still learning" → "dialed in") — plus a "Recent adjustments" log of the last few automatic corrections (date, trigger, before → after), backed by a new `watering_adjustments` table rather than a `CareLog` replay, since dialog dismissals and manual edits change the model's state without ever logging a care event. With `adaptive_watering` off, the sheet shows only the plain interval — nothing invented. A new "Ask before changing intervals" setting (main Settings screen, visible only while `adaptive_watering` is on; default **on**) controls whether applying a suggestion still shows today's confirmation dialog — off applies it silently with an undo Snackbar, with every change (silent or dialog-confirmed) appearing in the adjustments log. Also fixes a bug in the already-shipped adaptive + seasonal watering models: applying a suggested interval while `seasonal_watering` was on previously updated only the plain interval, leaving the seasonal base stale so the actual due date silently never moved; every adaptive-model call site now reads the live season-neutral base instead of a value that only updated on a manual edit. Room DB migrated to v12 (new `watering_adjustments` table, ships unconditionally regardless of `adaptive_watering`'s state); round-trips through backup/restore (backup schema v13) (#572)
- **"Check" reminders with a Still-moist action (developer mode, off by default)** — a new `check_reminders` feature flag reframes the watering reminder notification from an instruction ("Water Monstera") to a prompt ("Check Monstera") with two actions: **Watered** and **Still moist**. Still moist is a single tap, no screen shown — it records the observation as a new `CareType.CHECK` care-log entry (reusing the existing pipeline, no new table or backup fields needed) and pushes the next check out by a day, same default as the existing Skip-watering action; when the `adaptive_watering` flag (#568) is also on, it feeds the observation into that model's confidence tracking. CHECK entries show up in a plant's care history like any other logged action, but are excluded from the watering-history chart's markers. The watering feedback control on the Add Care Log screen and the quick-water sheet collapses from a 3-way "How was the soil?" chip to a single optional "Plant was dry / stressed" flag — nothing is pre-selected, and existing logs with the old Still wet/Just right values are unaffected. `CareSchedule.computeAdaptiveInterval()` (#568) now accepts a nullable feedback value so a WATER log with the flag left untouched still moves the learned interval toward the observed gap, at a capped, slower rate than an explicit answer would. Off by default and gated entirely behind the flag — flag off is byte-for-byte identical to today's behavior. No Room migration, no backup schema change (#570)
- **Seasonal watering curve preview chart** — a small chart now sits directly under the amplitude picker in Settings, and in the Plant Detail Water tab's inline settings card next to "Pin interval" (#569/#578), showing the computed `season(date)` multiplier across the year so the effect of Off/Mild/Standard/Strong is visible before committing to a setting, rather than just a label. Redraws live as you tap between amplitudes; a dashed guideline + dot mark today's position on the curve; a caption surfaces the inferred hemisphere ("Based on your device's timezone — Northern hemisphere, peak in January"). The Plant Detail variant grays the curve out with an inline note when the plant is pinned, since a pinned plant's due dates ignore the curve entirely. Visualization only — no change to due-date computation. Only visible while the `seasonal_watering` flag is on (#579)
- **Computed seasonal watering factor (developer mode, off by default)** — a new `seasonal_watering` feature flag stretches each plant's watering interval in winter and compresses it in summer using a computed cosine curve (`effectiveInterval = round(base × season(date))`), rather than learning per-month values from user data — the curve needs no training data, has no month-boundary discontinuities, and can't be permanently skewed by one unusual heatwave. A new global amplitude setting (Off / Mild / Standard (default) / Strong) on the main Settings screen — visible only while the flag is on — controls how strongly winter/summer stretch the interval; hemisphere is derived from the device's timezone, no location permission needed. Per-plant "Pin interval" switch (Add/Edit Plant and Plant Detail's Water tab) opts a plant out of the curve entirely. Migrating existing plants de-seasonalizes each one's stored interval to migration day, so every plant's *effective* interval is unchanged the moment the update ships, regardless of what month that happens to be. When both this and the adaptive-watering flag (#568) are on, the observed watering gap is de-seasonalized before it's fed into the confidence-weighted learning step, so a seasonal swing isn't misread as a permanent change in the plant's thirst. Room DB migrated to v11 (new `Plant.wateringBaseIntervalDays`/`pinIntervalToBase` columns, ship unconditionally regardless of flag state); round-trips through backup/restore (backup schema v12) (#569)
- **Adaptive watering interval (developer mode, off by default)** — a new `adaptive_watering` feature flag replaces the watering interval suggestion's fixed ±1-day nudge with a multiplicative correction (target = observed gap × 1.25 still-wet / 1.00 just-right / 0.82 too-dry) whose step size shrinks as a per-plant confidence counter (0-5) rises — fast to adapt early, calmer once the schedule proves itself. Confidence rises only from the observed watering gap matching the predicted interval or from dismissing the suggestion dialog (capped at 3), never from the feedback chip's value alone; it falls when two-or-more corrections in a row point the same direction. Editing the interval on Add/Edit Plant is a full reset; retyping the number inside the suggestion dialog before applying is a smaller correction, not a reset. Every result is clamped to 1-180 days and to ±40% per step. Off by default and gated entirely behind the flag — flag off is byte-for-byte identical to today's behavior. Room DB migrated to v10 (new `Plant.wateringConfidence` column, ships unconditionally regardless of flag state); round-trips through backup/restore (backup schema v11) (#568)

---

## [0.23.0] - 2026-08-19

### Added
- **Plant issues** — track an ongoing pest/disease/health problem on a plant as a status with a start date, distinct from the recurring custom-reminders feature (#232). Plant Detail's new always-visible "Active issues" card lets you report an issue (free-text name, e.g. "Spider mites") and shows how many days it's been ongoing for each active one; a plant can have multiple simultaneous active issues. Reporting an issue can optionally also create a linked treatment `CustomReminder` in the same step (a "set a treatment reminder" toggle with a name + plain-days interval) — the two stay independent afterwards, so resolving or deleting the issue never touches the linked reminder. Mark an issue resolved from its row (confirm dialog, optional free-text resolution note). The plant list card shows a new purple bug badge (icon + count when more than one) for plants with active issues — deliberately a different color from the green/orange/red due-status badges, since this is a health-problem axis, not a care-due axis. No notifications — this is a passive visual status only. Room DB migrated to v9 (new `plant_issues` table); round-trips through backup/restore (backup schema v10; older backups restore issues as empty) (#564)

---

## [0.22.0] - 2026-08-18

### Added
- **Custom reminders** — each plant can now have an unbounded number of free-text, recurring reminders (Plant Detail → new "Custom reminders" card), for anything not covered by a built-in care type — disease/pest treatments ("apply neem oil every 7 days") as well as anything else you want to track. Add/edit/delete a reminder and mark it done from the card; marking one done writes a visible journal entry (linked back to that reminder's name) and resets its schedule, same as watering/fertilizing/repotting. Interval is set in plain days (no months toggle). `ReminderWorker` includes overdue/due-today custom reminders in the daily notification, joined with the existing " · " separator; no per-reminder icon or notification category. Room DB migrated to v8 (new `custom_reminders` table plus a `CareLog.customReminderId` column); round-trips through backup/restore (backup schema v9; older backups restore reminders as empty and log links as unset) (#232)

### Fixed
- Watering and fertilizing can no longer be logged twice for the same plant on the same calendar day. A stray double-tap on a quick-log button (PlantCard, Plant Detail stat chips, Calendar day sheet, or bulk multi-select) now silently no-ops with an "Already watered/fertilized today" snackbar instead of creating a duplicate `CareLog`; bulk actions skip only the already-logged plants and say so in the summary snackbar (e.g. "Watered · 3 of 5 plants (2 already logged today)"). Liquid-fertilizer plants that were already watered today can still be fertilized — the paired watering that ADR-0008 normally auto-inserts is suppressed instead of double-counting. The Add Care Log screen (including edit mode) enforces the same rule with an inline error on Save rather than a dialog or a disabled button; editing a log's own note/photo on the same day is unaffected, but moving its date or care type onto a day that already has that type is rejected. No schema change — enforced at the repository/use-case layer, not a DB constraint (#509)
- The "How was the soil?" quick-water bottom sheet's feedback chips (Still wet / Just right / Too dry) now behave like every other deselectable chip group in the app: tapping the already-selected chip clears it instead of doing nothing. "Log" stays enabled either way — logging with no chip selected records the watering with no feedback, same as the full Add Care Log screen already allows (#549)

---

## [0.21.0] - 2026-08-10

### Added
- **Repotting reminder**: each plant can now have its own repotting interval (Add/Edit Plant → toggle with a slider, alongside watering and fertilizing). It's set in **months** (3–36, default 12), since repotting cadence is a seasons-and-years thing rather than a day-precise one. When set, an overdue/due-today repotting is included in the daily care notification, and logging a Repot care event resets the schedule. For a plant that has never been repotted, the first reminder is anchored to the plant's creation date plus one interval, so a newly added plant isn't flagged immediately (see product ADR-0022). Room DB migrated to v7; the interval round-trips through backup/restore (backup schema v8; older backups restore it as unset). A misting reminder was also built but deliberately dropped before release — misting does little for humidity and can encourage fungal leaf spot, so the app won't schedule it; you can still log a Mist care event manually. Free-text custom reminders remain a separate follow-up (#232)
- Settings → Reminders now has a **Notify for fertilizing** toggle. When turned off, the daily reminder no longer notifies for a plant whose *only* due care is fertilizing — but a plant that is also watering-due still gets its full reminder, fertilizing line included. Defaults to on (no change for existing users) and round-trips through backup/restore (backup schema v7) (#223)
- **Developer mode**: tapping the Settings → About version row 5 times unlocks a hidden **Developer** section at the bottom of Settings, with an AOSP-style tap countdown ("You are 2 taps away…", "…1 tap away…", "Developer mode enabled"). The section has a master on/off switch and four read-only build-info rows (version name/code, build type, Room DB version, device API level). Available in both debug and release builds, defaults off, and is not included in backup/restore. No feature flags or debug actions yet — this ships the unlock gesture and shell only (#520)
- Developer mode now has a **feature flag registry**: a generic, registry-driven flag list renders below the build-info rows (title, description, and a switch per flag, persisted immediately via DataStore). The registry ships **empty** — no flags are available yet — so the flag area shows "No feature flags in this build" for now; the first real flag arrives with the first experimental feature. Turning developer mode off resets every flag to its registry default. Not included in backup/restore (#521)
- Developer mode's Developer section now has two **debug actions**: "Reset What's New seen state" (clears the stored last-seen version so the What's New sheet auto-shows on the next launch) and "Run reminder check now" (runs the daily reminder check immediately instead of waiting for the scheduled time — it posts real notifications, and shows an explanatory Snackbar instead of a crash when notification permission is denied). Neither writes to the plant database or needs a confirmation dialog (#522)
- Developer mode's Debug actions now include **Seed demo plants** and **Remove demo plants** — the only two debug actions that touch the plant database, so both sit behind a confirmation dialog. Seeding inserts a fixed 8-plant demo set (all named with a `[Demo] ` prefix) with back-dated care-log history covering every card/list/calendar state — overdue, due today, due soon, not scheduled, never-watered, unassigned room, and a liquid-fertilizer plant — and reports the inserted count via Snackbar. Re-seeding is idempotent: it replaces the existing demo set rather than stacking duplicates. Removal hard-deletes every `[Demo] `-prefixed plant (including archived ones) and its care history, and reports the removed count via Snackbar. No Room migration — demo plants are identified purely by name prefix, so DB stays at v7 (#523)
- Plant Detail tabs now let you adjust a plant's schedule **inline**: change the watering interval on the Water tab, and the fertilizing interval and liquid-fertilizer mode on the Fertilize tab — no need to open the Edit screen. Changes save immediately and the tab's stats/chart update right away; the Edit screen stays the place to change a plant's name, room, notes, and photo (#436)
- Each Plant Detail tab now shows a small **insights** summary: the Water/Fertilize/Repot tabs show how many times you've done that care and the average interval between events (Repot also shows when it was last done), and the Photo tab shows the photo count and the first/latest photo dates (#436)

### Changed
- Plant Detail now organises care into per-action tabs — **Water · Fertilize · Repot · Photo** — shown below the plant photo. The watering-history chart lives under the Water tab (with recent misting), the photo gallery under the Photo tab, and each of Fertilize/Repot shows its own care history; Prune and Note stay in the full care-history list below the tabs. The quick-log stat chips and the **+** log button are unchanged. First step of the larger Plant Detail restructure (#436)
- The Plant Detail per-action tabs (#436) now ship **behind a feature flag**, off by default. Turn it on via Settings → (tap the version row 5 times) → Developer → **Plant Detail tabs** to switch between the new tabbed layout and the classic single-page Plant Detail at any time (#436)
- Dependencies: the watering-history chart library (Vico) moved from 2.0.0 to 2.5.2, and the calendar library from 2.7.0 to 2.10.1 — both within their existing API lines, so no user-visible change. Vico **3.x is deliberately not adopted**: it is a Kotlin Multiplatform rewrite that drops the `vico.core.*` packages and swaps AndroidX Compose for JetBrains Compose Multiplatform, which would mean rewriting the whole chart component. Dependabot is now configured to skip Vico major versions so it stops proposing that migration weekly (#515 #518)
- Build tooling: Gradle wrapper 9.6.1 → 9.7.0, KSP 2.3.10 → 2.3.11, `desugar_jdk_libs` 2.1.4 → 2.1.5, and CI's `actions/setup-java` 5.6.0 → 5.7.0. No user-visible change (#517 #536 #537 #539)

### Fixed
- Plant Detail: the watering-history chart's axis labels, axis lines, and guidelines were rendered in white on the light chart surface when the app's in-app theme was set to **Light** while the device's system theme was **Dark**. Vico's default chart theme derives those colours from `isSystemInDarkTheme()` rather than the app's Light/Dark/System toggle (#139), so a forced-Light app on a Dark device drew dark-theme (white) chart chrome. The chart now provides an M3-derived Vico theme (`ProvideVicoTheme(rememberM3VicoTheme())`) so its colours follow the app's actual Material theme in both light and dark modes
- Settings: rapid taps on the version row (developer-mode unlock countdown), the Developer mode switch, backup export/import results, and the new debug actions could each post a Snackbar on the same host at once, so one message could dismiss or immediately supersede another before it was readable. All Settings snackbar messages now go through a single ordered queue with one collector, so a newer message still replaces an older one but never races it (#522)

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
