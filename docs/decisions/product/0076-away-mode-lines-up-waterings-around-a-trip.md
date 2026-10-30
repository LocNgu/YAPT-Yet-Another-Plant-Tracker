# Product ADR-0076: Away mode lines up waterings around a trip — hold for the last day, bring forward on it

**Status**: accepted

**Date**: 2026-10-30

**Amends**: [ADR-0065](0065-away-mode.md) (Care banner and Before you leave: when it is available and what it lists).

## Context

Issue #969. Before you leave (product ADR-0065) only brings waterings forward, only on the last day at home, and
its cutoffs come from the sitter rhythm rather than from the plant. Two cases it misses:

- A plant on a 21-day interval, watered 15 days before departure, is due 6 days into a 21-day trip. Watered on the
  last day at home instead, it would last the whole trip and never need the sitter. With the default 7-day rhythm
  it lands in Check the soil; with a 4-day rhythm it isn't listed at all.
- A 21-day plant that comes due 3 days before departure is watered on schedule and is due again on day 18 of the
  trip. Held 3 days, until the last day, it would last until the owner is home.

A pot cannot store extra water. Watering soil that is still moist buys no extra days and raises the risk of root
rot, so bringing a watering forward only helps when the soil is mostly dry. Most houseplants tolerate a few extra
dry days better than an early watering, so a short hold is the safer shift. This matches product ADR-0065's "lean
dry". YAPT has no plant-type or drought-tolerance field, so any tolerance can only come from the plant's own
interval.

Alternatives rejected:

- **A hold limit of about 20% of the interval** (the issue's first suggestion). A hold of 15% to 20% is
  off-schedule by product ADR-0030's definition, so every manual watering surface would open "Why was it late?",
  and an unattributed answer would drop the gap from learning. Product ADR-0030 says there is "no new constant":
  15% (`GAP_AGREEMENT_TOLERANCE`) is already the app's notion of close enough, and a second one would drift.
- **Skipping the reminder of a held plant, or a Care row note.** Watering reminders are "Check {plant}" check-ins
  (product ADR-0027), and a skipped one would be hidden per-plant state the worker does not have. A note on a Care
  row needs the hold derived in the Care ViewModel, against the rule that the UI never derives due tasks, and Care
  tiles deliberately carry no due-date text. Product ADR-0065 also says Care keeps listing tasks so the queue
  stays truthful.
- **Shifting due dates.** Already rejected by product ADR-0065 (plant-row writes, no clean undo).
- **A "fewer waterings during the trip" test** for plants whose interval is shorter than the trip. It is fragile
  and depends on how the trip length lines up with the interval.

## Decision

Two rules, both from the plant's own effective interval and both shown only when they let the plant last the whole
trip. Neither writes anything.

### Definitions

All arithmetic is calendar days (`LocalDate`), never milliseconds (technical ADR-0013, technical ADR-0034).

- `T` = the trip's first day, `E` = its last day, `L = T - 1` (the last day at home), `tripDays = E - T + 1`.
- Everything is evaluated **as of `L`**, whatever day it is today: the same convention the sitter projection uses.
  Mode and interval are `L`'s (seasonal and dormancy state included).
- `I` is the plant's effective watering interval on `L`. Only plants on the normal cadence with an interval are
  considered; dormant (cadence or suspended) and unscheduled plants are left out, as are hidden and archived ones.
- `D` is the plant's watering due date from `TodayQueueAggregator`, including an active override.
- `W` is the day of the plant's newest WATER log. A plant with no WATER log is excluded from both rules.
- **Lasts the whole trip**: `I >= tripDays`, that is `L + I >= E`. A plant due on `E` counts as lasting, because
  the owner is home that day.

### Hold for your last day

`hold(p)` is true when all of these hold:

1. the plant lasts the whole trip;
2. `D < L`;
3. a watering on `L` is on schedule, by calling `CareSchedule.isWateringOnScheduleAt(W, I, L)` (the existing
   15% tolerance, never reimplemented);
4. `D + I < E`: left alone it comes due inside the trip.

The shift is at most `floor(0.15 * I)` days, so a plant with `I < 7` is never held, and a hold is only possible when
roughly `0.85 * I < tripDays <= I`. That is why the lists stay short. Holds are only meaningful while `today < L`;
on `L` a held plant is simply due and sits in Water today, as it does now.

### Water on your last day

On `L` only, a plant is added to **Check the soil** (unticked) when all of these hold:

1. the plant lasts the whole trip;
2. `firstVisit < D <= E - 1`, with `firstVisit = L + gap` and `gap = visitEveryDays ?: 7`: it is not already in
   Water today or Check the soil, and it comes due inside the trip;
3. at least 70% of its interval has elapsed: `10 * (L - W) >= 7 * I`, in integers. 70% is the lowest value that
   catches the 21-day example above (15 of 21 days is 71.4%).

A plant already in either existing list stays where it is, so nothing is listed twice. The "can wait" count is the
visible plants in none of the three groups. The wording stays "Check the soil", because the watering only helps if
the soil is mostly dry.

### Suggestions, not due rules

Holds and bring-forwards are suggestions on the Before you leave screen. They are not due rules. No due date moves,
Care still lists the plant on its real due date, reminders are unchanged (including for a held plant), and nothing
is written to a plant row. The sitter rhythm and the visit projection are unchanged: the projection already treats
every plant due on or before `L` as watered that day, which covers a held plant, and a bring-forward is unticked and
owner-gated, so assuming it watered would hide a plant from the sitter on a guess. Once the owner logs it, the real
WATER log reaches both screens.

### Learning

Learning needs no new rule; this is how the existing ones treat each shift.

| Shift | What happens |
|---|---|
| Hold (at most 15% late) | An ordinary on-schedule watering with null feedback: a nudge of at most 0.15 gain and confidence +1, the same as any 1 to 3 days late watering today. No "Why was it late?" prompt appears on any surface, because the same predicate gates the prompt. |
| Bring-forward, 85% elapsed or more | On schedule, so the same on-schedule nudge. |
| Bring-forward, 70% to under 85% elapsed | `WATER_NOT_ATTRIBUTED`, excluded from learning (product ADR-0030). It is logged through Before you leave's bulk log, which asks no reason, so the feedback is null. |
| The next gap, from `L` to the first watering after the trip | `AWAY_EXCLUDED`, because the gap spans the trip. |

Product ADR-0065's "the logs stay out of learning" is therefore strictly true only for the off-schedule case. An
on-schedule hold or bring-forward teaches like any other on-schedule watering.

### Shipping in two parts

This ADR records the whole design. The first part ships the pure rules and the bring-forward addition to the
last-day plan. The lead-in window (Before you leave opening from `AWAY_BANNER_LEAD_DAYS` days before the trip, with
a read-only **Hold for your last day** list, and the matching Care banner and Settings button) ships in a follow-up
after the Before you leave list rework. Until it lands, Before you leave is still available on the last day only and
no hold is shown anywhere.

## Consequences

- A long-interval plant that is close to the trip length can skip the sitter entirely, with no new field and no
  plant-row write. Cancelling or editing the trip needs no undo.
- The lists are short by construction. The cost of reusing the 15% tolerance is a smaller hold than the issue
  asked for: a 21-day plant can be held 3 days, not 4, and a 10-day plant 1 day, not 2.
- Bring-forwards below 85% elapsed are excluded from learning, so they do not teach, but they also do not distort
  the interval.
- Plants with `I < tripDays` are not handled: a short-interval plant needs the sitter either way.
- A per-plant drought-tolerance field (longer holds for succulents, earlier waterings for moisture lovers) and
  before-you-go tips remain out of scope.
