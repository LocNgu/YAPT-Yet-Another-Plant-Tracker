---
description: CareSchedule status computation and adaptive watering-interval rules
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/domain/schedule/**/*"
  - "app/src/test/**/schedule/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/util/DateUtils.kt"
---

# CareSchedule rules

Pure business logic. Calendar-day comparisons via `Long.toLocalDate()` — never millisecond division
(technical ADR-0013). `daysBetween()` uses `ChronoUnit.DAYS`.

## computeStatus()
- **Watering** — never-watered plant with an interval set is **due today** (`nextWateringDueAt = now`,
  `isDueSoon = true`), stays due-today (never drifts overdue) until the first WATER log; an existing
  `wateringDueDateOverride` still wins via `maxOf()`.
- **Fertilizing** — never-fertilized plant with an interval becomes due at
  `createdAt + FIRST_FERTILIZE_GRACE_DAYS` (30, named const), then overdue by normal date math.
- **Repotting** — first-due for a never-repotted plant is `createdAt + interval` (private generic
  `extendedCareDueAt()`), so a newly added plant isn't flagged immediately. Populates
  `nextRepottingDueAt`/`isRepottingOverdue`/`isRepottingDueSoon`/`lastRepottedAt` (all defaulted, existing
  callers unaffected). See product ADR-0022 (#232).
- **Custom reminders** — unbounded per plant, so unlike repotting they're a `List<CustomReminderStatus>`
  (`PlantCareStatus.customReminderStatuses`), not scalar fields. `computeStatus()` takes a `customReminders:
  List<CustomReminder> = emptyList()` param; each reminder reuses `extendedCareDueAt()` independently, but
  anchored to **the reminder's own `createdAt`**, not the plant's — reminders are commonly added long after
  plant creation, so a fresh reminder must not be flagged overdue immediately (#560 follow-up). See technical
  ADR-0019 (#232).
- No interval configured → "Not scheduled".

## computeSuggestedInterval() — adaptive watering (product ADR-0006)
- `JUST_RIGHT` suggests when `actualIntervalDays != currentInterval`.
- `TOO_SOON` uses `currentInterval` as base when the user watered early (`actual < stored`).
- `TOO_LATE` clamps to `min(actual, stored)`.
- Final result `.coerceAtLeast(1)` — two same-day waterings (`actual == 0`) can't yield a 0-day suggestion (#446).
- Flow: after a WATER log, `AddCareLogViewModel` computes `actualIntervalDays` from the last two waterings and
  passes the result back via `savedStateHandle["suggestedWateringInterval"]`; the detail screen shows a modal
  editable `AlertDialog` (product ADR-0006, supersedes product ADR-0005).

## DateUtils.formatRelative()
Calendar-day (`ChronoUnit.DAYS.between`) so "Last: X days ago" reflects calendar days, not a rolling 24h window
(#351). History list + Graveyard show exact dates (e.g. "Jun 10, 2026") for events > 14 days old; PlantCard chips
and Detail stats always show the relative form (#387).

## Convention reminder
Suspend `buildStatus()` runs inside a `combine {}` block — `List.map {}` takes a non-suspend lambda, so it uses a
`for` loop with `mutableListOf`. Don't refactor to `.map {}` (technical ADR-0003).
