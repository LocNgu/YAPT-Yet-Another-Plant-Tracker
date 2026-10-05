---
description: Seasonal repot planning, preferred repotting seasons, and the Repotting overview page
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/domain/schedule/SeasonalRepotting*.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/domain/usecase/RepotPlanReset*.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/domain/repotting/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/ui/screens/repotting/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/data/preferences/RepottingOverviewPreferences*.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/ui/screens/plantdetail/*Repot*.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/ui/screens/addplant/**/*"
  - "app/src/test/**/*Repot*.kt"
  - "app/src/test/**/repotting/**/*"
  - "app/src/androidTest/**/repotting/**/*"
---

# Repotting (product ADR-0022, product ADR-0057, product ADR-0059)

## Data model (#809)
Two independent features, both unset by default, so due dates are unchanged unless they are set:
- **One-off plan:** `Plant.repotPlanSeasonStartAt` is the target season's first day at start of day (system zone). It is stored as a timestamp, so a timezone or hemisphere change never moves the due date; the label is derived from it. `repotPlanMadeAt` records when the plan was made (`updatedAt` can't serve, since it changes on every edit).
- **Preferred seasons** for the recurring interval: `Plant.repottingSeasons` (`SeasonalFertilizing.encode`/`decode`; `null` = every season = early-out).
- Backup v21 carries all three.
- `AddEditPlantViewModel.saveEdit()` builds a fresh `Plant`: carry the plan over from `existing` (never edited there). `repottingSeasons` is saved even with the reminder off, so re-enabling it remembers the choice.

## Pure rules (`domain/schedule/SeasonalRepotting.kt`)
- **The plan wins outright** over the interval date, even when earlier, and needs no interval (unlike watering's defer-only override, product ADR-0039).
  - `resolvePlan()` derives the season and its end (the day after the season, found from the nearest season boundary to the stored timestamp; hemisphere-independent).
  - `RepotPlan.stateOn(nowDate)` → UPCOMING / IN_SEASON / SEASON_ENDED.
  - In `computeStatus()`, `nextRepottingDueAt` is the plan start. `isRepottingDueSoon` covers the whole season; `isRepottingOverdue` starts only after it ends.
  - `repottingPlanSeasonEndAt`/`repottingPlanSeason` are non-null exactly for a plan, resolved with the status's own hemisphere so copy can't disagree with the due state.
  - **Consumers never treat "past `nextRepottingDueAt`" as overdue for a plan.**
- **Preferred-season shift** (no plan): `nextPreferredDueAtMillis(raw, seasons, hemisphere, anchor = lastRepottedAt ?: createdAt)`.
  - A raw date already in a preferred season is unchanged. Otherwise (past or future) it moves to the first day of the **nearest** preferred stretch (contiguous preferred seasons, year-wrapping included; distance measured to the stretch's first day; ties go later).
  - The candidate must be at least **half the interval** after the anchor's day (`(raw − anchor) / 2` days, integer division, floored at 1). A rejected candidate falls forward to the stretch after raw, at most once. Example: repotted Jan 15 2026, 180-day interval, spring preferred → due Mar 1 **2027**.
  - **Time-stable:** the result is a pure function of raw/seasons/hemisphere/anchor, with no `nowDate`. So a shifted date can lie in the past and reads overdue until repotted, rather than jumping ahead. Accepted trade-off: a neglected plant reads overdue even in an off-season.
- `upcomingSeasons()` feeds the picker (never the current season). `repotLogClearsPlan()` → see below.

## Clearing a plan
- A newly inserted REPOT log clears the plan when its local day is on or after the plan-made day; a backdated one leaves it.
- This goes through the shared `RepotPlanReset.clearIfSuperseded()` and the column-specific `PlantDao.updateRepotPlan()`, called from `QuickLogUseCase` and `AddCareLogViewModel`.
- **Order matters:** call it *after* `WateringLifecycleReset.applyRepotReset()`, whose full-row write would otherwise restore the plan. `QuickLogUseCase.maybeApplyRepotReset()` re-reads the plant fresh first, because callers pass stale snapshots.
- Editing or deleting a REPOT log never clears or resurrects a plan.

## Surfaces
- **Notification:** "Repot planned this spring" in season; "Planned repot overdue by N days" after, counted from the season's last day (`rules/notifications.md`).
- **Care:** the task is bucketed by season state, and the tile's second line reads "Planned for spring" (`rules/care-queue.md`).
- **Plant Detail Repot tab:** the "Plan repot" button, `RepotPlanSeasonDialog` and `RepotPlanSummary` (`rules/plant-detail.md`). `setRepotPlan()`/`clearRepotPlan()` write only the plan columns, inside `plantEditMutex`.
- **Add/Edit Plant:** the shared `FertilizingSeasonsSelector` (with a `labelRes` and the screen's own last-season snackbar copy via `onLastSeasonLocked`) sits under the repotting slider, shown only while the reminder is on. It is bound to `repottingSeasons`/`toggleRepottingSeason()` (a toggle, per #804).

## Repotting overview page (#525, product ADR-0059)
- **What it is:** an audit page for active plants not repotted in a long time. It is never a sort option, Care task or notification.
- **Entry and navigation:** a Settings row ("Repotting overview") right after Plant Graveyard, nested like it (`Screen.RepottingOverview`, route `repotting_overview`, bottom bar hidden, back arrow `cd_back`, Back → the Settings tab).
- **The Settings subtitle** is a plural count for the **saved** chip ("4 plants not repotted in 2+ years" / "3 plants never repotted"; "No plants to review" for zero; `RepottingOverviewThreshold.settingsSubtitleRes()`).
- **One pipeline feeds both** the page and the subtitle, so they can't drift:
  - `observeRepottingOverview()` (`domain/repotting/`) combines active plants, `observeLastCareAtByPlant(REPOT)`, the saved threshold and a day-change flow through the pure `RepottingOverviewBuilder`.
  - `RepottingOverviewViewModel` injects `dayChange = dayChangeTicker()`. `SettingsViewModel` builds the ticker internally (Detekt parameter limit), and likewise derives `CareLogRepository`/`RepottingOverviewPreferences` from `database`/`dataStore`.
- **The chip row is the saved threshold** (`RepottingOverviewPreferences`, `YaptApplication.repottingOverviewPreferences`): a tap writes DataStore and the row reads it back. The default is 2+ yr, and the choice survives process death.
- **Layout:**
  - A Planned group on top under every chip (season + year via `repotPlanLabelRes()`; an ended plan shows "· Overdue" in `OverdueRed`).
  - Then the list under a header naming the chip. Rows show the cover, name, room and "Last repotted <`formatMonthYear`>" / "Never repotted · added …".
- **Empty states:**
  - No active plants → an "add a plant" message, chips hidden.
  - Nothing matches and nothing planned → "Nothing to repot here — nice work".
  - Nothing matches but plans exist → Planned + a one-line note.
- **Rows copy Care's tile pattern (product ADR-0056):**
  - Tap → Plant Detail on the Repot tab.
  - Long-press → a `DropdownMenu` (`repottingMenuActions()`: Repot… / Plan repot…; planned rows: Repot… / Change plan… / Clear plan), mirrored as `customActions` + `onLongClickLabel`. No inline buttons.
  - The actions reuse existing pieces: Repot… → `CareDatePickerBottomSheet` + `QuickLogUseCase.quickLog(plant, REPOT, loggedAt)`; Plan/Change → `RepotPlanSeasonDialog` → `setRepotPlan`; Clear → `clearRepotPlan`.
- **Writes and state:** every write runs through `RepottingOverviewViewModel.perform()`, one `Mutex` with a fresh re-read inside, so a repot's full-row reset can't restore a just-written plan. Rows move in place because every input is a flow. Prompt target ids are `rememberSaveable` and dropped when the plant leaves the page.
- **Tests:**
  - `RepottingOverviewScreenTest` locates rows by the clickable node's `testTag` (`repottingRowTag()`), checks group order with `positionInRoot` (the `TodayScreenTest` precedent), and asserts content via text, custom actions and selected state (#420).
  - `SettingsViewModelRepottingOverviewTest` reads the real ticker only via `first {}`, never `advanceUntilIdle()`.
