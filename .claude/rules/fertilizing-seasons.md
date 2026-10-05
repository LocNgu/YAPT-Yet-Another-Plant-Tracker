---
description: "Fertilizing active seasons — SeasonalFertilizing, season chip selector, FilterChip colors"
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/domain/schedule/SeasonalFertilizing*.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/ui/components/FertilizingSeasonsSelector*.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/ui/components/YaptFilterChipColors*.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/ui/screens/addplant/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/ui/screens/plantdetail/PlantDetailScheduleSettingsActions.kt"
  - "app/src/test/**/*SeasonalFertilizing*.kt"
  - "app/src/androidTest/**/FertilizingSeasonsSelector*.kt"
---

# Fertilizing active seasons (#795, product ADR-0049; #813, product ADR-0051; #814, product ADR-0052)

## Model
- **Data:** one `fertilizingIntervalDays` plus a per-plant active-season set, `Plant.fertilizingSeasons: Set<FertilizingSeason>` (default all four). It is stored as comma-separated names in `PlantEntity.fertilizingSeasons: String?` (`null` = every season). `SeasonalFertilizing.encode()`/`decode()` is the one normalizer, shared by the repository and `BackupManager`.
- **Due date:** `computeFertilizingDue()` computes the raw date, then `SeasonalFertilizing.nextActiveDueAtMillis(raw, activeSeasons, hemisphere, nowDate)` moves it out of inactive seasons. A plant is never due during an inactive season and becomes due on the first day of re-entry, not "overdue by 3 months". The branch-by-branch rule is in `rules/schedule.md`.
- The hemisphere comes from `SeasonalWatering.currentHemisphere()`. There is no learning, confidence or history; dormancy is a separate, additional pause.

## `FertilizingSeasonsSelector` chip row (`ui/components/`)
Shared by Add/Edit Plant, Plant Detail's Fertilize tab and (with `labelRes`) Add/Edit's repotting seasons.
- **It reports a toggle of the tapped season, never a full replacement set (#804).** A full set built from a stale `selected` snapshot let a second fast tap discard the first.
- **The last selected chip stays enabled and selected (#813).** Tapping it wiggles, buzzes (`HapticFeedbackType.Reject`) and calls `onLastSeasonLocked` (never `onToggle`). The screen then shows "At least one season must stay active. To stop fertilizing, turn off the fertilizing reminder." through `SnackbarHostState.showSnackbarOnce()` (`ui/util/SnackbarExtensions.kt`), so repeats don't queue.
- **Styling:**
  - Selected chips get a `primaryContainer` fill via `yaptFilterChipColors()`. **Every `FilterChip` in the app must pass it** (#814); `DarkColorScheme` also sets `secondaryContainer`/`onSecondaryContainer`.
  - There is no checkmark `leadingIcon`; the outline that only unselected chips have is the non-color cue.
  - The current season's chip has a decorative trailing dot. Its "Current season" wording lives in the chip's `stateDescription` for TalkBack.
- **Applying the toggle:**
  - Add/Edit applies it to local form state (synchronous, so it can't race).
  - Plant Detail's `toggleFertilizingSeason()` (`PlantDetailScheduleSettingsActions.kt`) re-reads the plant inside `plantEditMutex` and applies it there. If that would empty the set (two fast taps on the last two chips), it emits `Event.FertilizingSeasonToggleRejected` → the same snackbar, without a wiggle.
