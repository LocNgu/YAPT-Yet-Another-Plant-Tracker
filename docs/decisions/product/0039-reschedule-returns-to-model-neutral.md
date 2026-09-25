# Product ADR-0039: Reschedule returns to model-neutral

**Status**: accepted

**Date**: 2026-09-15

**Supersedes**: [ADR-0030](0030-off-schedule-actions-ask-why.md)'s *Reschedule-flow* clause only —
its "Reschedule" row in the "Resolved mapping" table, the reason prompt it introduced for
Reschedule, and the `CareType.CHECK`/confidence/`watering_adjustments` writes that prompt drove.
ADR-0030's **watering** reason prompt (the Water button's "Why now?"/"Why was it late?" flow) and
[ADR-0033](0033-late-watering-reason-never-shortens.md)'s direction-specific mapping on that same
watering flow are **unaffected and stand exactly as written**. ADR-0030's own header records the
justification for coupling the two buttons under one rule as symmetry: "One rule, both buttons:
off-schedule actions get asked why; the answer decides whether it counts. **That symmetry is why
this is better than the three-action split rather than merely simpler than it.**" This ADR
dissolves that symmetry rather than extending it — a considered asymmetry, not an oversight. The
two buttons no longer share one rule because they no longer do the same kind of thing: Water logs
something that happened; Reschedule only moves a date.

**Amends**: [ADR-0027](0027-check-reminders-still-moist-action.md) — it introduced both the
notification's "Still moist" action and the `CareType.CHECK` concept, both of which this decision
substantially retires. Per the precedent ADR-0030 already set (it carries its own "Amends
ADR-0027" line without touching ADR-0027's Status), **ADR-0027's Status line is not edited here.**

## Context

A reschedule ("Soil still moist" via `RescheduleReasonBottomSheet`) currently teaches the adaptive
model: it writes a `CareType.CHECK` log, updates `Plant.wateringConfidence`, and writes a
`watering_adjustments` row (`CHECK_STILL_MOIST`), via
`QuickLogUseCase.recordStillMoistAdaptiveObservation()`. This full history and the concrete defect
below are filed on [#738](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/738);
this ADR records the decision made there.

### 1. The reschedule prompt is redundant with the watering prompt

ADR-0030's own root-cause analysis is that timing alone doesn't reveal intent: a late watering
might mean "the plant coped fine" or "I was busy." That is correct, and it is already solved at
watering time — `WateringReasonBottomSheet` asks why, and the answer decides whether the
observation counts. By the time a gap is actually measured, the app has already asked the one
question that matters, about an event that actually happened. The reschedule prompt asks the same
question again, in advance, about a watering that hasn't occurred yet and may never happen on the
rescheduled date at all. This is the largest weight in this decision: the watering prompt is
strictly the better place to ask, because it is asked about something real.

### 2. The day-8 defect: an early "still moist" check taught the model backwards

`recordStillMoistAdaptiveObservation()` feeds the observation in as `feedback = TOO_SOON`, which
computes `target = observedGap × TOO_SOON_TARGET_MULTIPLIER` (1.25). On a 14-day plant at
confidence 3 (gain 0.28):

| Check day | target | raw new base | result |
|---|---|---|---|
| day 10 | 12.5 | `14 + 0.28 × (12.5 − 14)` = 13.58 | rounds to 14 — no change |
| **day 8** | 10 | `14 + 0.28 × (10 − 14)` = 12.88 | rounds to **13 — shortens** |

Checking early and reporting "soil is still moist" made the model conclude the plant needs water
**sooner**. The user said "leave it longer"; the model heard "shorten it." This is the missing
mirror of ADR-0033's rule that a late gap never shortens: `1.25 × observedGap` is a sensible
dry-out estimate only at or past the due date, and before it, "still moist" is the expected,
uninformative outcome — wet soil on day 8 of a 14-day plant is evidence of nothing, yet the
estimator read it as a reason to pull the interval down. `CareScheduleAdaptiveTest`'s `an early
TOO_SOON observation still shortens the base (#738 ADR-0039 pinned defect)` pins this arithmetic
permanently, as a property of `CareSchedule.computeAdaptiveInterval()` itself (which is otherwise
unchanged by this ADR) rather than of the call site being removed.

### 3. The framing only ever fitted one direction

"Why put it off?" presupposes deferral. It reads naturally for pushing a date later and
incoherently for the case where the model's own conclusion is "come back sooner" — exactly the
situation #720 is stuck reasoning about (which this decision simplifies but does not
resolve — see "Interaction with #714/#719/#720/#737" below).

### Considered and rejected

**V2 — journal-only.** Keep the reason prompt and the `CareType.CHECK` log; drop only the
confidence update and the `watering_adjustments` row. Rejected: there is no product need for the
journal entry ("I have no use to keep it in journal history" — repo owner). With nothing left to
route to, the prompt would ask a question whose answer changes nothing at all, which is a worse
UX than removing the prompt outright.

**V3 — patch the estimator.** Keep ADR-0030 as written and add the ADR-0033 mirror: an early
still-moist check never shortens the base either. Rejected: narrower than it looks. It leaves the
reschedule→model channel in place, so a future variant of the same defect remains possible in that
channel, and it addresses neither problem 1 (redundancy) nor problem 3 (framing) above — only
problem 2.

## Decision

**A reschedule writes `Plant.wateringDueDateOverride` and nothing else.** Never a `CareType.CHECK`
log, never `wateringConfidence`, never `wateringIntervalDays`/`wateringBaseIntervalDays`, never a
`watering_adjustments` row. The reason prompt (`RescheduleReasonBottomSheet`/`RescheduleReason`) is
removed entirely — tapping Reschedule opens the date dialog directly, with no intermediate "why"
step. All learning comes from the next actual watering, which already has its own reason prompt at
the moment the gap is genuinely measured.

This is ADR-0029's "Reschedule unconditionally inert to the adaptive model" posture returning **in
substance** — but not by reverting ADR-0030's UI. The two-button Water / Reschedule watering row
(`WateringDueActionsRow`) stays exactly as ADR-0030 shaped it; only Reschedule's *internal* prompt
step is removed, collapsing back to a plain date-move action.

### Notification

The reminder notification's "Still moist" action (`StillMoistReceiver`) is **dropped, not
reworked** — rescheduling now requires opening the app and using Plant Detail. This is not a
regression that needs a replacement snooze rule in this ADR: the notification's remaining "Not
now" action (`SkipWateringReceiver`) is already model-neutral — it writes only
`wateringDueDateOverride`, per ADR-0007 — so deferring from the notification remains possible
without any learning side effect. The notification's action set narrows from three (Watered /
Still moist / Not now) to two (Watered / Not now).

**One caveat, deliberately recorded rather than glossed.** "Not now" computes
`(wateringDueDateOverride ?: now) + 1 day`, which anchors to the *existing override* when one is
set, not to now. On a plant carrying a stale override in the past, one tap advances that stale
date by a day and can leave the plant still overdue — e.g. an override of Sep 11 tapped on Sep 16
becomes Sep 12. This is a **pre-existing defect in `SkipWateringReceiver`, not introduced by this
ADR** — it is tracked as [#741](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/issues/741). It matters here only because dropping "Still moist" makes
"Not now" the sole notification-level deferral, so that defect should be fixed for the remaining
action to be a dependable snooze. An earlier draft of this ADR asserted the opposite — that "Not
now" anchors to now and therefore always clears "due" — which was wrong; the correction is kept
visible here because the false invariant would otherwise have guided the follow-up work.

**Update (#741, #745):** fixed. `SkipWateringReceiver.skipWatering()` now computes
`maxOf(wateringDueDateOverride ?: now, now) + 1 day`, so a stale past override can no longer be
advanced to a date that is still in the past. The arithmetic quoted above and the closing "should be
fixed" clause describe the pre-#745 state; see `.claude/rules/notifications.md` for the current
behavior.

### The "(suggested)" deferral row

Removed, along with its source `suggestedStillMoistDeferralDays()` and
`PlantDetailViewModel.confirmRescheduleSuggestedDays()` (#719's shipped handler). It existed only
to preview what the model would learn from a reschedule; with nothing being learned, there is
nothing left for it to preview. The remaining +1/+2/+3/Custom date options are unaffected.

### Existing `CareType.CHECK` data

New CHECK logs stop being written, but nothing already on disk is deleted:

- `CareType.CHECK` and `WateringAdjustmentTrigger.CHECK_STILL_MOIST` **stay as enum constants**,
  along with their display strings (`care_type_check`, `adjustment_trigger_check_still_moist`).
  Both are persisted as Strings and read back via `runCatching { Enum.valueOf(...)
  }.getOrDefault(fallback)` (the CLAUDE.md convention) — existing installs and `.yapt` backups
  written before this change carry rows with these values, and removing the constants would make
  those rows silently coerce to the wrong fallback and misrender history. **Only the write paths
  are removed; this is a permanent state, not a transitional one, and a future cleanup pass must
  not "tidy" these away as dead code.**
- Existing `CareType.CHECK` rows are **hidden, not deleted**, from Plant Detail's shared
  care-history list (a display filter, no `Migration`, no DB version bump, fully reversible). No
  other surface needs the filter — `WateringHistoryChart` already excludes CHECK, per-tab insights
  and filtered lists match by exact `CareType`, and Calendar renders no individual `CareLog` rows.
- Existing `CHECK_STILL_MOIST` rows in "Why this date?" → Recent adjustments **stay visible,
  unfiltered**. That surface is deliberately model provenance ("what did the model once do"), not
  a user journal, and those rows are a truthful record of something the model actually did at the
  time. This is not symmetrical with the care-history filter above, and that asymmetry is
  intentional.

### Interaction with #714/#719/#720/#737

- **#714** and **#719** are **superseded here, not reverted** — reverting either would restore the
  defect each one fixed. Both stay closed. #714's same-day-duplicate special-casing simplifies
  (with no CHECK log, `isDuplicateGuarded()` no longer needs to cover this path, so the guard and
  its special-cased branch can go); its regression test is reframed rather than deleted — "a
  repeated reschedule still commits the date" stays the pinned contract, just reached trivially
  now. #719's `confirmRescheduleSuggestedDays()` retires along with the "(suggested)" row it fed.
- **#720** is substantially simplified by this decision, not resolved by it: with the "(suggested)"
  row gone, the only way to pick an ineffective date is the custom-date picker, which narrows #720
  to a straightforward picker-constraint fix.
- **#737** is largely dissolved: it existed because the (now-removed) "(suggested)" row counted
  from today while +1/+2/+3 count from the due date — two silently different anchors in one list.
  With only one anchor left, the ambiguity disappears.

## Consequences

- The Reschedule button becomes a single, fast, no-prompt action — one fewer tap and one fewer
  decision than ADR-0030's version, at the cost of losing the "I checked and it's still wet"
  attribution as a persisted fact anywhere in the app. Accepted: the repo owner explicitly does not
  want that fact journaled ("I have no use to keep it in journal history").
- The notification drops to two actions. This is a real, if narrow, loss of one on-notification
  attribution ("I checked from the lock screen"), accepted for the same reason as the in-app prompt
  removal — with nothing learning from it, the action had nothing left to justify a dedicated slot.
- `CareType.CHECK` and `WateringAdjustmentTrigger.CHECK_STILL_MOIST` become **write-only-in-the-past**:
  every live code path that still references them is either legacy Room/backup deserialization
  safety or display of already-existing data, never a new write. This is a deliberate, permanent
  design state, recorded here so it is not later mistaken for an incomplete removal.
- `PlantDetailViewModelRescheduleTest`'s existing "reschedule options never touch
  `wateringBaseIntervalDays`, `wateringConfidence`, or `watering_adjustments`" test broadens into
  the one surviving contract for the whole Reschedule flow, once the reason-prompt code it also
  covered is removed.
- The code removal itself (deleting `RescheduleReasonBottomSheet`/`RescheduleReason`,
  `recordStillMoistAdaptiveObservation()`, `suggestedStillMoistDeferralDays()`,
  `confirmRescheduleSuggestedDays()`, `StillMoistReceiver`, the related strings, and adding the
  care-history display filter) lands in a follow-up PR, not this one — the slices don't compile
  independently (deleting `RescheduleReason` breaks the UI until the call sites that reference it
  are updated in the same change), so splitting them further would leave `develop` in a
  half-removed state between merges. This PR records the decision and pins the defect being fixed;
  it changes no runtime behavior.
