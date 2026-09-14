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

**Derived from source plus arithmetic; still not reproduced on-device.** A `@codex` second opinion
was requested on #716–#720 and answered on all five (see each issue's comments — they are worth reading
in full before implementing). It confirmed every claim in substance and corrected four of them; the
corrections are folded in below. Codex reported committing fixes for #719 and #720, but **no such
branches or PRs exist on the remote** — treat those as unwritten.

Where codex corrected me:

- **#717's threshold was wrong.** The dead zone is bases **≤ 26 days**, not "under ~23". `0.15 × |O − B| ≥ 0.5`
  needs an *integer* gap difference of 4, not 3.34, and `4 ≤ 0.15 × B` gives `B ≥ 27`. First movable base
  is 27 (verified independently).
- **#717 is not documentation-only.** ADR-0027 specifies a capped *nonzero* neutral gain and ADR-0030
  says passive learning "refines within the tolerance band". The docs describe the intent correctly; the
  implementation fails to realise it. Do not close this as a docs fix.
- **#718's ratchet framing was too broad.** The rounding residual is a sawtooth, not one sign per season.
  The ratchet is systematic only for the upward seasonal-threshold crossings that trigger these dialogs —
  which is the actual case, but state it that way.
- **#716's drift is stepped, not continuous** as the user sees it, and the curve's steepest points are
  ~Apr 6 / ~Oct 6 (a quarter-year from the day-5 peak), not the equinoxes.

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
| #719 | ~~Still-moist "(suggested)" option overshoots by `daysUntilDue` — from-today figure, due-date anchor~~ **Fixed** — `RescheduleWateringDialog`'s "(suggested)" row now calls a dedicated `now`-anchored `PlantDetailViewModel.confirmRescheduleSuggestedDays()`, distinct from the due-date-anchored `confirmRescheduleRelativeDays()` the +1/+2/+3 options still use. In the shortening subset the corrected, earlier target is provisionally inert against `CareSchedule.computeWateringDue()`'s `maxOf()` clamp — that's #720's decision space, not reverted or re-broken by this fix. |
| #718 | Applying a suggestion re-derives the base from a rounded display value, ratcheting it up | Don't fix by rounding the base; see invariants. Exposure drops once #716 lands, defect does not. Preserving a precise base while prefilling the field from the *rounded* one creates an immediate display/schedule mismatch — derive both from the same value. |
| #716 | Suggestion dialog fires on pure seasonal drift and blames the watering | Biggest product call — touches ADR-0026/0028, needs a spec pass. Fix choice interacts with #717's outcome. |
| #714 | Second same-day still-moist reschedule silently drops the date | Same function as #715. One PR is cheaper. |
| #720 | Reschedule to a date before the due date is silently ignored | Option A makes #719's target unreachable; option C unblocks it. Decide this with #719 in view. |
| #717 | Unattributed observations can't move any base ≤ 26 days | **Not docs-only** (see above). #718's fix A needs the `Double` model result this issue would add — do them together or duplicate the API change. |
| #715 | "Recent adjustments" can show an interval change that was never applied | Pairs with #714. |

## Two things the second opinion added that the issues understate

**The suggestion gate exists in three places, not one.** `QuickLogUseCase.computeSuggestion()`,
`AddCareLogViewModel`, and `PlantDetailViewModel.pendingWateringSuggestion` each independently compare an
effective-space suggestion against the stored literal. #716's fix must cover all three or introduce one
shared "did the model change base?" predicate — fixing only `QuickLogUseCase` leaves two live copies.
This is the same class of bug #631 and #674 already fixed twice by consolidating duplicated write paths.

**Dismissing a calendar-only dialog is not inert.** A dismissal raises `wateringConfidence` and writes a
`DIALOG_DISMISSAL` row, so seasonal drift produces both a misleading modal *and* bookkeeping that reads
the dismissal as confirmation the schedule is right. Higher confidence means a lower gain, so the
spurious dialogs actively slow real learning. "Just dismiss them" is not a safe workaround.

## #719 shipped; how it interacts with #720

#719's fix (now merged) lands on **A** from its own candidate list: the "(suggested)" row calls a
dedicated `now`-anchored handler, leaving +1/+2/+3 on the existing due-date anchor. This is not a
conflict with #720 the way an earlier draft of this doc framed it — nothing #719 wrote gets reverted by
whichever option #720 picks. What #720 actually decides is narrower: `CareSchedule.computeWateringDue()`'s
`maxOf(computedNextDueAt, override)` clamp still forbids an override from pulling a due date **earlier**
than the schedule-computed one, so in the subset where the model *shortens* the interval
(`1.25 × observedGap < base`), #719's corrected target (now genuinely earlier than the current due date)
is written to `wateringDueDateOverride` but has no visible effect — the clamp keeps the old, later due
date until #720 decides whether an override should ever be allowed to win that comparison, and how (option
A forbids it outright, option C allows it). Until #720 lands, that subset is strictly closer to correct
than the pre-#719 overshoot (the earlier value is provisionally inert, not actively wrong), so #719 was
safe to ship first, exactly as the issue's own corrected preamble argued.

## Maintenance

Fold the relevant parts of this file into `.claude/rules/schedule.md`,
`.claude/rules/seasonal-watering.md` or `.claude/rules/watering-transparency.md` as each issue lands —
those are the permanent homes. **Delete this file once #722 closes**; it is scaffolding for an open
cluster, not a standing rule. Per the workflow's step 5, the PR that closes a cluster issue updates
this file in the same PR.
