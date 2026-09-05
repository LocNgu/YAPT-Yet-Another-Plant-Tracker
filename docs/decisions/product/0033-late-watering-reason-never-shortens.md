# ADR-0033: The late-watering reason prompt never shortens the interval

**Status**: accepted

**Date**: 2026-09-04

**Amends**: ADR-0030 (off-schedule actions ask why) — only the *late-direction* half of its
"Resolved mapping" table and its "TOO_SOON becomes structurally impossible on a WATER log" pin.
The early-direction mapping ("Why now?" → "The plant needed it"/"Just my schedule") and the
Reschedule-flow mapping are untouched.

## Context

A user reported that "It was dry by then" — the late-direction answer to "Why was it late?" that
attributes a late watering to the plant (`WateringReason.PLANT_NEEDED_IT` → `WateringFeedback
.TOO_LATE`) — reads as an accusation rather than a neutral observation, particularly for
non-native English speakers.

Working through alternate wording exposed a deeper problem than phrasing. Two things, together:

**1. The two late-direction answers don't cover this app's own core workflow.** YAPT's model is
"check the soil, water only when it's actually dry" — the due date is a nudge to go check, not a
mandate to water on that day. A user following that workflow who checks *informally* (not via the
in-app Reschedule button), finds the soil fine, and only waters once it's genuinely dry has no
honest answer on this prompt: "It was dry by then" implies the plant was starving for the whole
overdue window, and "Forgot, or no time" implies neglect that didn't happen. The prompt was built
around the same two-bit shape as the *early* direction without checking whether that shape still
fit once the sign flipped.

**2. The existing "It was dry by then" → `TOO_LATE` mapping doesn't do what it was assumed to do.**
Per `CareSchedule.computeAdaptiveInterval` (technical ADR-0021, #568): `target = observedGap ×
0.82`, `base = base + gain × (target − base)`. For a late gap this usually **lengthens** the
interval rather than shortening it — e.g. a 7-day plant watered on day 10 with "it was dry by
then" computes `target = 10 × 0.82 = 8.2`, which is *above* the current base of 7, so `base` moves
up toward 8.2, not down. It only shortens in the narrow band where the lateness barely clears the
off-schedule threshold (roughly 1.05–1.22× the current interval). Separately, and regardless of
direction of drift: a late gap is an ambiguous measurement. The observation only tells us the
plant was dry *by the time the user got to it* — it says nothing about whether that was true on
day 8, day 9, or day 10 of a ten-day-late watering. Treating the full late gap as a precise
estimate of "when this plant needs water" extrapolates a point estimate from what is really a wide
window.

The early direction doesn't have this problem: watering *before* the due date because the soil was
already dry is a precise observation — the plant needed it right then, no window of ambiguity.

### Considered and rejected

**A. Reword "It was dry by then" only, keep the same TOO_LATE mapping.** Rejected: fixes the tone
complaint but not the underlying issue — any rewording of the same option still asks the user to
make a causal claim ("the interval is too long") that a single retrospective observation can't
actually support, and the math still doesn't reliably shorten as intended.

**B. Switch to "How was the soil?" (Dry / Just right) and derive the direction algorithmically
from timing.** Considered as a larger, more observational alternative — report a fact rather than
reason about causation. Rejected for this change: it doesn't by itself fix the ambiguity problem
(a "Dry" answer on a 10-day-late gap is exactly as ambiguous as today's "It was dry by then" was),
so it would need the same underlying logic change as the decision below layered under it anyway,
plus a larger rework of the "ask why" mechanism ADR-0030 established. Left as a possible future
UI simplification once the logic below is in place, not pursued now.

**C. Scale the 0.82/1.25 damping by how many days overdue the gap is.** Considered as a way to make
the existing shorten-path proportionally more conservative the more ambiguous the window is,
rather than removing it. Rejected: it touches technical ADR-0021's tuned multiplier/gain constants,
which that ADR and `.claude/rules/schedule.md` explicitly warn against altering to chase different
convergence numbers, for a narrower benefit than option D below (it still permits an imprecise
shorten, just a smaller one).

## Decision

**A late watering can no longer shorten the interval at all.** Shortening only ever happens from
the *early* direction, where the signal is precise. The late-direction prompt's plant-attribution
answer changes from "It was dry by then" (`PLANT_NEEDED_IT` → `TOO_LATE`, shorten) to "Soil was
still moist" (`SOIL_STILL_MOIST` → `TOO_SOON`, lengthen) — reusing the exact signal the Reschedule
flow's existing `RescheduleReason.SOIL_STILL_MOIST` answer already represents, just reachable from
the WATER-log path for a user who checked informally rather than tapping Reschedule.

| Timing | Reason chosen | `wateringFeedback` | Effect on `base` |
|---|---|---|---|
| Early | The plant needed it | `TOO_LATE` | observed × 0.82 — shortens (unchanged) |
| Early | Just my timing | `null` | excluded (unchanged) |
| Late | **Soil was still moist** | **`TOO_SOON`** | **observed × 1.25 — lengthens (changed)** |
| Late | Forgot, or no time | `null` | excluded (unchanged) |

`WateringReason` gains a third value, `SOIL_STILL_MOIST`, mapping to `TOO_SOON`. It is
late-direction-only, mirroring `PLANT_NEEDED_IT`'s early-direction-only role — `WateringReason
.JUST_MY_TIMING` remains the only value common to both directions. `WateringReasonBottomSheet`
now picks its two-chip option set by `gapRanLong` rather than rendering the same two enum values
with only relabeled text.

New string `water_reason_soil_still_moist_late` ("Soil was still moist") — it's the same
underlying observation as `RescheduleReason.SOIL_STILL_MOIST`'s "Soil still moist", but phrased as
its own full clause rather than reusing that shorter label verbatim: it needs to read as a direct
answer to "Why was it late?", not a button label repurposed as one.

### Two properties ADR-0030 pinned that no longer hold

**"TOO_SOON becomes structurally impossible on a WATER log."** This was true under ADR-0030's
two-value `WateringReason` and is no longer true: `SOIL_STILL_MOIST` reaches `TOO_SOON` on a WATER
log directly. `WateringAdjustmentTrigger.WATER_TOO_SOON` was already a defined enum value (reachable
generically by `adjustmentTriggerFor`'s existing `feedback == TOO_SOON` branch, previously dead for
new writes) — it becomes reachable in practice through this change, no new trigger value needed.

**"Each prompt offers at most three reasons" measured as `WateringReason.entries.size`.** The enum
now holds three total values, but each individual prompt still shows exactly two — the "at most
three per prompt" constraint from ADR-0030 is unchanged; only the total-enum-size proxy for it no
longer applies now that the two prompts don't share their full option set.

### The #571 cold-start bootstrap needed its own floor (found in review)

The "never shortens" guarantee above only covers the steady-state per-observation path
(`CareSchedule.computeAdaptiveInterval`'s `TOO_SOON_TARGET_MULTIPLIER`). It does not, by
construction, cover `WateringLifecycleReset.maybeBootstrap()` (#571) — the one-time cold-start that
fires instead of the normal update on a plant's first-ever adaptive observation, or its first
post-reset one. That function computes `base` as a plain median of historical WATER-log gaps
(`CareSchedule.bootstrapBaseInterval`), entirely blind to `WateringFeedback`: a late "Soil was
still moist" watering landing on exactly one of those two triggering observations could still
bootstrap the interval *down*, silently breaking the guarantee through the path that bypasses the
multiplier entirely. Fixed by threading the triggering `WateringFeedback` into
`WateringLifecycleReset.BootstrapRequest`; when it's `TOO_SOON`, the bootstrapped base is floored
at the plant's pre-bootstrap interval. `confidence` is still taken from the bootstrap result
as-is — it reflects how much history exists, not which direction the interval should move, so
flooring it alongside the base would misrepresent how much is actually known.

## Consequences

- A user who is confident a plant was already dry well before they got around to watering it late
  has no way to say so precisely anymore — that attribution is now unrepresentable, the same way
  ADR-0030 made `TOO_SOON` on a WATER log unrepresentable at the time. Accepted deliberately: the
  imprecision of a multi-day-late single observation makes that confidence unreliable evidence
  regardless of how sure the user feels.
- The late direction can now only hold or lengthen the interval, never shorten it. A plant whose
  true interval needs to *shorten* can still get there, but only via a subsequent *early* watering
  (or manual edit) — never from a late one, however many times it's marked "soil was still moist"
  or "forgot."
- `docs/decisions/product/0030-off-schedule-actions-ask-why.md`'s Status line is updated to note
  this amendment; its early-direction and Reschedule-flow content stands unchanged.
- No schema change — `CareLog.wateringFeedback` already stores `TOO_SOON`/`TOO_LATE`/`JUST_RIGHT`/
  `null`; this only changes which `WateringReason` values reach which `WateringFeedback` on a WATER
  log, and which options `WateringReasonBottomSheet` renders per direction.
