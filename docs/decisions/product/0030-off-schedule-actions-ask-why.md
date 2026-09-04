# Product ADR-0030: Off-schedule actions get asked why; the answer decides whether it counts

**Status**: accepted (late-direction mapping superseded by
[ADR-0032](0032-late-watering-reason-never-shortens.md) — the early-direction mapping and the
Reschedule-flow mapping below still stand as written)

**Date**: 2026-08-25

**Supersedes**: ADR-0029 (three actions; Reschedule unconditionally inert to the adaptive model).
Amends ADR-0027 (Still moist's entry point, not its data model) and ADR-0025 (#570's single "was
dry" flag is replaced by the watering reason prompt).

## Context

Three problems accumulated on Plant Detail's watering-due surface, with one root cause.

**Two controls for one intention.** ADR-0029 shipped Water / Still moist / Reschedule. A user who
wants to defer has to first decide *why* in order to pick the right button — but they usually know
they want to defer before they have thought about why.

**A flat 1-day deferral that cannot clear "due".** `STILL_MOIST_DEFERRAL_DAYS = 1` meant a plant
overdue by two or more days was still overdue after tapping Still moist, and the daily duplicate
guard correctly blocked a second tap. The user had to reach for Reschedule as well. Reproduced
on-device against #585.

**Timing alone does not reveal intent — on either control.** This is the root cause:

- Watered three days late → the plant was fine and could go longer, *or* you were busy and it went
  thirsty.
- Reschedule +2 days → the soil is still wet, *or* you are away for the weekend.

The same observable action carries opposite meanings. Any design that reads meaning off timing or
duration conflates *the plant's needs* with *the user's availability* — precisely the conflation
ADR-0007 exists to prevent.

### Considered and rejected

**A. Dampen the learning signal by reschedule length.** Short reschedules teach a little, long ones
nothing. Rejected: duration does not encode intent — "away for the weekend" and "still wet, give it
two days" are both +2 days and mean opposite things. It also hides the rule, since nothing on screen
says where the threshold sits, which works against the transparency requirement (ADR-0028) that
motivated this whole feature set.

**B. Compute the Still-moist deferral and keep three buttons.** Rejected as insufficient rather than
wrong: it fixes the flat-1-day problem cleanly but leaves the other two untouched. Its arithmetic is
absorbed into the Reschedule flow below.

**C. Keep ADR-0029's three-action split.** Rejected because it forces the user to disambiguate
*before* acting, and because it still reads intent from which button was pressed — it just moved the
inference earlier.

## Decision

**Two actions, and the app asks instead of inferring.**

| The user did | App asks | Effect on the model |
|---|---|---|
| **Water**, on schedule | nothing | quiet confirmation; confidence up via gap agreement |
| **Water**, early or late | *why?* | "The plant needed it" → learn · "Just my timing" → ignore |
| **Reschedule** | *why?* | "Soil still moist" → learn · "I can't right now" → ignore |

One rule, both buttons: **off-schedule actions get asked why; the answer decides whether it counts.**
That symmetry is why this is better than the three-action split rather than merely simpler than it.

The prompt is required, not optional. Making it optional puts us back at the root cause, since the
app would have to guess from timing. The prompt is the mechanism that makes the two-button design
correct.

**Two options per prompt, three at the absolute most.** From the reason the model needs exactly one
bit: was this about the plant, or about the user? Everything beyond that is journaling and belongs in
the free-text note field. Reason lists bloat; this constraint is what stops the design decaying back
into ambiguity.

### "On schedule" reuses `GAP_AGREEMENT_TOLERANCE`

No new constant. ±15% is already the app's definition of "the prediction matched reality", and it is
scale-invariant in the right way — roughly ±1 day on a 7-day plant, ±4½ on a 30-day cactus. A second
notion of "close enough" would inevitably drift from the first.

### The reason reuses `CareLog.wateringFeedback` — no schema change

No new column, no Room migration, no backup schema bump. `WateringReason.PLANT_NEEDED_IT` stores
`TOO_LATE`; `JUST_MY_TIMING` stores nothing.

`TOO_LATE` serves the early *and* the late case correctly because #568's multiplier applies to the
**observed gap**, not to `base` — so the same value shortens after an early watering and pulls back
after an over-long one. One reason value, right in both directions.

### The five-states-in-a-four-state-field problem

Reusing `wateringFeedback` needs five distinguishable states in a field that has four:
`TOO_SOON` · `TOO_LATE` · `JUST_RIGHT` · *no answer* · **explicitly not evidence**.

The last one is not optional. Watering early because you are going away and answering "just my
timing" writes `null` — and #570's gap-learning rule moves `base` toward the observed gap at a capped
gain even for `null`. A pre-emptive holiday watering at day 5 of a 20-day plant would therefore
quietly **shorten** its interval despite the user having explicitly said it was not about the plant.
That is the conflation this design exists to prevent, re-entering through the passive channel.

**Resolution: derive it from whether the prompt was shown.**

| Log state | Meaning |
|---|---|
| **On schedule** + `null` | prompt never appeared → passive gap-learning applies |
| **Off schedule** + `null` | prompt appeared, user declined to attribute it → **excluded from base learning entirely** |

"Was the prompt shown" is a pure function of timing and `GAP_AGREEMENT_TOLERANCE`, so **no new state
is persisted**. The rule lives inside `CareSchedule.computeAdaptiveInterval()` rather than being
threaded through every call site as a boolean, which is how it holds identically for the quick-log
sheets, the AddCareLog form, a bulk log, and the notification's "Watered" action — a parameter is one
a caller can forget. This is a change to #570's gap-learning rule, not an addition alongside it.

Confidence is deliberately *not* separately suppressed for an excluded observation: confidence is
evidence about the schedule, and an off-schedule gap disagrees with the prediction whatever the
reason, so it simply does not rise.

### Resolved mapping

| Timing | Reason chosen | `wateringFeedback` | Log type | Effect on `base` |
|---|---|---|---|---|
| On schedule | *(not asked)* | `null` | WATER | gap-learning at capped gain; confidence up |
| Early | The plant needed it | `TOO_LATE` | WATER | observed × 0.82 — shortens |
| Early | Just my timing | `null` | WATER | **excluded** |
| Late | The plant needed it | `TOO_LATE` | WATER | observed × 0.82 — pulls back |
| Late | Just my timing | `null` | WATER | **excluded** |
| Reschedule | Soil still moist | `TOO_SOON` | CHECK | observed × 1.25 — lengthens |
| Reschedule | I can't right now | — | *(no log)* | nothing |

### Two properties this pins

**`TOO_SOON` becomes structurally impossible on a WATER log.** `WateringReason` has no value that
maps to it, so the original objection that started this rework — "a watering log with feedback 'still
wet' is illogical" — stops being a convention and becomes unrepresentable. It is now reachable only
from a `CareType.CHECK` log.

**`JUST_RIGHT` goes unused for new writes.** On-schedule waterings write `null` because no prompt
appears, and the two remaining silent writers (a bulk log, and the paired WATER of a liquid
fertilizing) now write `null` too — which is what they always meant, since the user was never asked.
The enum value stays for backward compatibility: existing logs and older `.yapt` backups continue to
deserialise via the standard `runCatching { Enum.valueOf(...) }` pattern and keep the full
confidence-driven gain. Demo seed data deliberately still writes `JUST_RIGHT`, so the legacy path
stays exercised.

### Dismissing a prompt records no signal

Matches ADR-0024's precedent that null feedback means no learning. Dismissing the *watering* prompt
cancels the watering outright; logging without choosing a chip is the explicit "I'd rather not say"
answer. Dismissing the *reschedule* prompt abandons the reschedule entirely. Either way nothing wrong
reaches the model. Accepted implication: ignoring the prompt is equivalent to answering "it was about
me" — the safe direction, since it cannot teach the model anything wrong.

### The deferral is derived, and its length is never a signal

The user picks the date, so the flat constant is gone. For "Soil still moist" the picker opens on
`QuickLogUseCase.suggestedStillMoistDeferralDays()` — `newBase - observedGap`, floored at one day,
i.e. "come back when the freshly-lengthened interval says it is due" — computed by the same function
that performs the real write, so the suggestion and what is learned cannot drift. `Today` is hidden
for that reason, since pulling the date forward would contradict what the user just said. The
notification action, which has no picker, applies the same derived value.

**How many days the user then picks never affects whether or how much the model learns.** The reason
already decided that. This is asserted by tests, since inferring from duration is the specific failure
this design exists to avoid.

### Notification actions: a fixed set of Watered · Still moist · Not now

A reminder fires at or after the due date, so a notification-initiated watering is never *early*, and
Android affords roughly three action slots. Splitting "Watered" into its two reason variants would
push out either Still moist or Not now.

- **Watered** writes `null` — correct when on schedule, and the safe exclusion when late.
- **Still moist** writes the CHECK log with `TOO_SOON`, still one tap, now with the derived deferral.
- **Not now** reschedules with no signal (`SkipWateringReceiver`, unchanged).

What this loses is the "I watered late *because* it was dry" attribution from the notification; that
remains available in-app. Rejected alternative: varying the action set by how overdue the plant is —
it makes the notification's buttons unpredictable between firings, which is worse than losing one
attribution.

## Consequences

- **Still moist disappears as a top-level action, days after ADR-0029 shipped it.** Named plainly
  rather than glossed over: this is churn on freshly-merged UI. The behaviour and its data model
  survive intact — `CareType.CHECK`, `QuickLogUseCase.recordStillMoistCheck()`, and the override
  semantics are unchanged; only the entry point moves.
- **Reschedule is no longer unconditionally inert to the adaptive model** (ADR-0029's central claim).
  This is *not* a return to the pre-ADR-0007 conflation: the distinction ADR-0007 protects is
  preserved exactly, and is now **asked** rather than **assumed**. "I can't right now" remains a pure
  `wateringDueDateOverride` write with no log and no `watering_adjustments` row.
- **Passive gap-learning now only ever refines within the tolerance band.** An unexplained
  off-schedule gap moves `base` by nothing at all, so the interval only ever changes on explicit
  attribution or on an on-schedule nudge. Accepted deliberately: the user is always asked when it
  matters, so an unanswered off-schedule gap really is a declined attribution.
- **A new `WateringAdjustmentTrigger.WATER_NOT_ATTRIBUTED`** distinguishes "the model deliberately
  ignored this" from `WATER_NEUTRAL`'s "nothing to change", so the ADR-0028 sheet can explain a row
  where nothing moved. No migration — the column stores enum names as strings.
- **`check_reminders`' notification now carries three actions rather than two**, using the Android
  slot budget in full and leaving no room for a fourth without a redesign.
- **"Reason = away/vacation" is the natural entry point for a later vacation mode**, so that will not
  need a third control.
- The thing a future change is most likely to break is the symmetric rule itself, by adding a fourth
  control or a fourth reason. That is what this ADR exists to record.
- Amended by [ADR-0031](0031-watering-actions-always-visible.md) (visibility gate): the
  `isOverdue || isDueSoon` due-status gate this ADR carried forward from ADR-0029 is dropped, so both
  Water and Reschedule watering render on any day, not only once a plant is due.
