---
description: Open defect cluster in the adaptive-watering / reschedule path — shared model, invariants, traps
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/domain/usecase/QuickLogUseCase.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/domain/usecase/WateringLifecycleReset.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/domain/schedule/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/ui/screens/plantdetail/PlantDetail*Actions.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/ui/screens/plantdetail/WateringDueActions.kt"
---

# Open defect cluster: adaptive watering + reschedule (#722)

Seven defects found in one analysis pass, filed as #714–#720 and tracked by **[#722](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/722)**.
They fix separately but share a handful of root causes, and some fixes interact. Read this before
starting any of them.

**Status lives in #722's sub-issue list, not here.** This file deliberately carries no checkboxes:
GitHub already knows what is closed, and a second copy would rot. What this file carries is the part
that does *not* change when an issue closes — the model, the invariants, and the traps.

**Everything below was derived from source plus arithmetic. Nothing was reproduced on-device, and a
`@codex` second opinion was still outstanding when the cluster was filed.** Verify before implementing;
treat each issue as a strong hypothesis, not a spec. #717 in particular may be intended behaviour with
overstated docs rather than a bug.

## Three structural facts behind all seven

**1. There are two interval numbers, and they drift apart by design-omission.**
`Plant.wateringBaseIntervalDays` (`REAL`, season-neutral) is what the model reasons about.
`Plant.wateringIntervalDays` (`Int`, effective) is what the UI shows and what comparisons test against.
The due date is computed from the *base* × today's season; the literal field is only rewritten on a
manual edit, a suggestion apply, the #571 bootstrap, or the #702 graduation fixup. Between those events
the season keeps moving and the literal does not. Any code comparing "the interval" against something
must state which of the two it means.

**2. There are two date anchors, and only one direction of travel.**
`suggestedStillMoistDeferralDays()` reasons **from today** (`newBase − observedGap`).
`confirmRescheduleRelativeDays()` anchors **to the due date** (`maxOf(nextWateringDueAt, now)`).
These coincide only when the plant is overdue. Separately, `computeWateringDue()` resolves
`maxOf(computedNextDueAt, override)`, so a reschedule can **only ever push a plant later** — an earlier
override is silently discarded, and there is currently no way to express "come back sooner", even when
the model concludes exactly that.

**3. Rounding happens at three boundaries, and the error is amplified by the season.**
The `REAL` base is rounded to `Int` going into `computeAdaptiveInterval()`, rounded again by
`clampStep()` coming out, and rounded a third time for effective display. Deriving a base back from a
rounded effective value multiplies the rounding error by `1/season` — up to ±0.77 days at the July
trough. `.claude/rules/schedule.md`'s accepted "quantization artifact" note covers only the per-step
clamp, not this round-trip.

## Invariants a fix must not break

- `wateringIntervalDays` is **effective-space** at every read site (#620/#626/#644 settled this three
  times — do not re-litigate it).
- `wateringBaseIntervalDays` is deliberately unrounded at rest (`.claude/rules/seasonal-watering.md`).
  Don't "tidy" it to an `Int`.
- Pinned plants (`pinIntervalToBase`) and amplitude Off keep `newInterval` as a literal and leave the
  stored base untouched (#584 review round 2).
- The reason answer, never the deferral length, decides what the model learns (#586, product ADR-0030).
- `suggestedStillMoistDeferralDays()` and `recordStillMoistAdaptiveObservation()` must keep sharing
  `computeStillMoistAdaptiveInterval()` so preview and write can't diverge (#586).
- A late gap never shortens the interval (#649, product ADR-0033).

## The seven, and how they interact

| Issue | One line | Interaction to watch |
|---|---|---|
| #719 | Still-moist "(suggested)" option overshoots by `daysUntilDue` — from-today figure, due-date anchor | Correct target can fall before the due date, where #720's `maxOf()` discards it. Fixable alone; #720 completes it. |
| #718 | Applying a suggestion re-derives the base from a rounded display value, ratcheting it up | Don't fix by rounding the base; see invariants. Exposure drops once #716 lands, defect does not. |
| #716 | Suggestion dialog fires on pure seasonal drift and blames the watering | Biggest product call — touches ADR-0026/0028, needs a spec pass. Fix choice interacts with #717's outcome. |
| #714 | Second same-day still-moist reschedule silently drops the date | Same function as #715. One PR is cheaper. |
| #720 | Reschedule to a date before the due date is silently ignored | Option A makes #719's target unreachable; option C unblocks it. Decide this with #719 in view. |
| #717 | Unattributed observations can't move any base under ~23 days | May be docs-only. Decide that first — it changes whether #716's fix has anything to show. |
| #715 | "Recent adjustments" can show an interval change that was never applied | Pairs with #714. |

## Maintenance

Fold the relevant parts of this file into `.claude/rules/schedule.md`,
`.claude/rules/seasonal-watering.md` or `.claude/rules/watering-transparency.md` as each issue lands —
those are the permanent homes. **Delete this file once #722 closes**; it is scaffolding for an open
cluster, not a standing rule. Per the workflow's step 5, the PR that closes a cluster issue updates
this file in the same PR.
