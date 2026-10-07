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
- The gap is measured from the new log's **chronological predecessor** (`getLastWateringBefore`, #673), never the globally newest pair. No predecessor means no observation, which includes a log backdated before every existing watering. There is no gap-source parameter and no first-watering configured fallback.

## Same-day duplicates
- **Only WATER and FERTILIZE** are rejected on a day that already has one. CHECK and MIST are never written, so they aren't guarded.
- `CareLogRepository.hasLogOfTypeOnDay(plantId, careType, dayTimestampMs, excludeLogId)` is the single query (DAO `countLogsOfTypeOnDay`).
- `QuickLogUseCase.isDuplicateGuarded()` covers every quick-log surface; `AddCareLogViewModel` has its own equivalent guard for edits (excluding the edited row).
- Check *before* a paired liquid-fertilizer WATER insert, not just against the sibling insert in the same call (#509).

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

- **A late gap never shortens:** one retrospective observation can't tell when the plant went dry, so "The plant needed it" is never offered late. `JUST_RIGHT` is never written for new logs.
- "Asked and declined" is **derived inside `computeAdaptiveInterval()`** from timing, never persisted or passed in.
- **Reschedule is model-neutral** (#738, product ADR-0039): it writes only `wateringDueDateOverride`, asks nothing, and all learning comes from the next real watering.
