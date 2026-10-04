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

## Shared adaptive WATER observation (#780, technical ADR-0030; #673, technical ADR-0033)
`AdaptiveWateringObservation` is the shared adaptive WATER observation path used by `QuickLogUseCase` and `AddCareLogViewModel` (#780, technical ADR-0030). Keep dormancy, bootstrap, confidence, and adjustment writes there; the callers supply their entry-point-specific gap policy and clocks. Both measure the gap from the new log's chronological predecessor (`getLastWateringBefore`), never the globally newest pair; the form alone keeps a configured-interval fallback for a plant's first-ever watering, while a log backdated before every existing watering gets no observation (#673, technical ADR-0033).

## Same-day duplicates
**Same-day WATER/FERTILIZE duplicates are rejected**, not PRUNE/REPOT/NOTE/PHOTO/CUSTOM (nor MIST/CHECK, which no code path writes any more) — `CareLogRepository.hasLogOfTypeOnDay(plantId, careType, dayTimestampMs, excludeLogId)` is the single query (DAO-level `countLogsOfTypeOnDay`, no schema change). `QuickLogUseCase` is the one choke point for all quick-log surfaces via its `isDuplicateGuarded()` set; `AddCareLogViewModel` has its own equivalent guard since it doesn't go through that use case. `CareType.CHECK` dropped out of the guard in #738 (product ADR-0039) — nothing writes it anymore, so there is nothing left to guard; MIST was never in it and is retired too (#875, product ADR-0061). Always check *before* a paired liquid-fertilizer WATER insert, never just against the sibling insert in the same call (#509).

## Post-watering reminders (#519)
**Post-watering reminders** — every successful current-day WATER insert debounces one WorkManager alert to 30 minutes
after the latest watering; backdated/edited/duplicate-suppressed logs do not schedule. A foreground firing shows a
persisted dismissible modal; a background firing uses notification ID `-2`, preserved by daily-reminder cleanup, whose
tap applies `CARED_FOR_TODAY` only in memory (product ADR-0036, technical ADR-0026, #519).

## Off-schedule watering reason prompt (#586, #649, #738)
**Off-schedule watering asks why; the answer decides whether the model learns** (#586, product ADR-0030; late-direction mapping amended by #649, product ADR-0033) — this rule now covers the Water button only (see below for Reschedule). "On schedule" reuses `CareSchedule.GAP_AGREEMENT_TOLERANCE` (never a second notion of "close enough"), surfaced as `PlantCareStatus.isWateringOnSchedule`. The reason reuses `CareLog.wateringFeedback`, but the mapping is now **direction-specific**, not the same two values reworded: early-only `WateringReason.PLANT_NEEDED_IT` → `TOO_LATE` (shortens — a precise signal, watered before the plant was even due); late-only `WateringReason.SOIL_STILL_MOIST` → `TOO_SOON` (lengthens — a user who checked the soil informally, found it moist, and only watered once it was genuinely dry); `JUST_MY_TIMING` → `null` in both directions (excluded). A late gap **never shortens** the interval — a single retrospective observation can't localize exactly when inside an overdue window the plant went dry, so the late direction only ever holds or lengthens. `TOO_SOON` is therefore reachable on a WATER log via the late reason prompt; `JUST_RIGHT` is still never written for new logs. `WateringReasonBottomSheet` (`PlantCareStatus.isWateringGapLong`) picks both the wording *and* the two-value option set per direction — "Why was it late?" offers "Soil was still moist"/"Forgot, or no time", never "The plant needed it" ("Why now?" reads as an accusation on an overdue plant, and a late "shorten" attribution isn't precise enough to offer at all). The fifth state ("asked and declined") is **derived from timing inside `computeAdaptiveInterval()`**, never persisted and never a call-site parameter. **Reschedule is model-neutral again** (#738, product ADR-0039, superseding product ADR-0030's Reschedule-flow clause) — it writes only `Plant.wateringDueDateOverride`, never a `CareType.CHECK` log, `wateringConfidence`, an interval field, or a `watering_adjustments` row, and asks no reason prompt at all; all learning comes from the next actual watering. `CareType.CHECK` and `WateringAdjustmentTrigger.CHECK_STILL_MOIST` are retained enum constants (Room/`.yapt` deserialization safety for historical rows) that are now write-only-in-the-past — read and displayed, never written by new code.
