---
description: "Logging care — shared adaptive WATER observation, same-day duplicate guard, reason prompt, post-watering reminder"
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/domain/usecase/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/ui/screens/addcarelog/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/domain/model/WateringReason*.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/data/repository/CareLogRepository*.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/data/db/CareLogDao*.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/ui/components/ReasonBottomSheets.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/ui/screens/plantdetail/WateringReasonGate.kt"
  - "app/src/test/**/usecase/**/*"
  - "app/src/test/**/addcarelog/**/*"
  - "app/src/androidTest/**/WateringReason*.kt"
---

# Care logging

## Shared adaptive WATER observation (#780, technical ADR-0030; sole call site per technical ADR-0037)
- `AdaptiveWateringObservation` is the one adaptive WATER path, and `QuickLogUseCase` is the **only** `observe()` call site (technical ADR-0037). Add Care Log is edit-only and calls just `feedbackForLog()`, the write-time dormancy feedback strip, on every save; it has no `dataStore`/`wateringAdjustmentRepository`, no suggestion on `Event.Saved` and no `NavGraph` handoff. Dormancy, bootstrap, confidence and adjustment writes live in the shared class; callers supply only their clocks (`rules/watering-transparency.md`).
- **Away mode exclusion (#897, product ADR-0065):** `AdaptiveWateringObservation` takes an optional `AwayPeriodRepository` (`QuickLogUseCase` and `AddCareLogViewModel` pass it; the default `null` means no trips). One `GapExclusion(dormancy, away, awayPeriods)` is computed per gap. When `AwayPeriods.spansAway(periods, previous, loggedAt)` holds, the gap is observed with `frozen = true` and `suppressConfidenceTransition = true` and **no feedback**: the base doesn't move, a `null` confidence stays `null`, and one `AWAY_EXCLUDED` row (`before == after`) is written. The trigger precedence is `DORMANCY_EXCLUDED` > `AWAY_EXCLUDED` > `FROZEN_POST_REPOT` > `WATER_NOT_ATTRIBUTED` > the feedback triggers. The dormancy exit decrement stays dormancy-only, so an away gap never writes `DORMANCY_EXIT`. When the history bootstrap wins, the triggering observation still gets its `AWAY_EXCLUDED` row (or `DORMANCY_EXCLUDED` when both apply), and the bootstrap drops away-spanning gaps anywhere in history. `feedbackForLog()` strips feedback for away-spanning gaps too, which covers Add Care Log edits that move a log into a trip. Away mode writes no plant row, so this adds no caller-snapshot write (#872).
- The gap is measured from the new log's **chronological predecessor** (`getLastWateringBefore`, #673), never the globally newest pair. No predecessor means no observation, which includes a log backdated before every existing watering. There is no gap-source parameter and no first-watering configured fallback.

## Same-day duplicates
- **Only WATER and FERTILIZE** are rejected on a day that already has one. CHECK and MIST are never written, so they aren't guarded.
- `CareLogRepository.hasLogOfTypeOnDay(plantId, careType, dayTimestampMs, excludeLogId)` is the single query (DAO `countLogsOfTypeOnDay`).
- `QuickLogUseCase.isDuplicateGuarded()` covers every quick-log surface; `AddCareLogViewModel` has its own equivalent guard for edits (excluding the edited row).
- Check *before* a paired liquid-fertilizer WATER insert, not just against the sibling insert in the same call (#509).

## Sitter visit waterings (#899, product ADR-0065)
- `QuickLogUseCase.logSitterVisitWaterings(plants, visitDay)` is the confirm step's write: one transaction, `quickWaterWithReasonInternal(plant, reason = null, loggedAt, schedulePostWateringReminder = false)` per plant, never `onWaterLogged`, so there is no reason prompt and no post-watering reminder (the owner isn't there). The log is dated `min(noon of visitDay, nowProvider())` and the same-day duplicate guard applies per plant. A gap touching a trip is already `AWAY_EXCLUDED` by slice 2.
- **Slice 5 (#900)** reuses the same call for the after-the-trip prompt on Care and for Settings (`ConfirmVisitViewModel`): ticked plants only, logs dated on the visit day, duplicates skipped and reported (`PlanVisitEvent.Confirmed(logged, skipped)`), no reason prompt, no post-watering reminder, every observation `AWAY_EXCLUDED`. "The visit didn't happen" writes no log. Details: `rules/away-mode.md`.
- **Before you leave** uses the unchanged `bulkLog(plants, WATER)` (Plant List's path; the owner is home, feedback `null`).

## Post-watering reminder (#519, product ADR-0036)
Every successful **current-day** WATER insert debounces one alert to 30 minutes after the latest watering. Backdated, edited and duplicate-suppressed logs don't schedule. Details: `rules/notifications.md`.

## Off-schedule watering asks why (#586, product ADR-0030; #649, product ADR-0033)
- **When to ask:** "on schedule" reuses `GAP_AGREEMENT_TOLERANCE` (`PlantCareStatus.isWateringOnSchedule`); never add a second notion of "close enough". Off schedule, the Water action shows `WateringReasonBottomSheet`, whose direction comes from `isWateringGapLong` and decides both the wording and the options.
- **Answers map to `CareLog.wateringFeedback`, by direction:**

  | Direction | Prompt | Answer → feedback |
  |---|---|---|
  | Early | "Why now?" | "The plant needed it" (`PLANT_NEEDED_IT`) → `TOO_LATE` (shortens) |
  | Late | "Why was it late?" | "Soil was still moist" (`SOIL_STILL_MOIST`) → `TOO_SOON` (lengthens) |
  | Either | — | "Just my timing" / "Forgot, or no time" (`JUST_MY_TIMING`) → `null` (excluded) |

- **Gates skip an excluded gap** (dormancy product ADR-0044, away product ADR-0065), each through one derived property so no surface can miss a clause: Care reads `WateringTaskAction.skipsReasonPrompt` (`TodayQueueInput.awayPeriods` -> `isAwaySpanning`), Plant List and Calendar read `PlantCareStatus.skipsWateringReasonPrompt`, and Plant Detail checks the *picked* date against its own predecessor (`isChosenDateAwaySpanning` in `WateringReasonGate.kt`, with `PlantDetailViewModel.awayPeriods()` read next to `previousWateringBefore`), so a backdated pick is judged on the gap it really closes. Every other prompt decision is unchanged.
- **A late gap never shortens:** one retrospective observation can't tell when the plant went dry, so "The plant needed it" is never offered late. `JUST_RIGHT` is never written for new logs.
- "Asked and declined" is **derived inside `computeAdaptiveInterval()`** from timing, never persisted or passed in.
- **Reschedule is model-neutral** (#738, product ADR-0039): it writes only `wateringDueDateOverride`, asks nothing, and all learning comes from the next real watering.
