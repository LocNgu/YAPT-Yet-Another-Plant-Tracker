# Product ADR-0067: "Soil was already too dry" on the Log watering picker; Care tiles use the picker

**Status**: accepted

**Date**: 2026-10-19

## Context

Product ADR-0030 made a quick Water on schedule (within `CareSchedule.GAP_AGREEMENT_TOLERANCE` of the base) ask nothing,
and the log is saved with `wateringFeedback = null`. The model then sees a neutral observation: the target is the
observed gap, the gain is capped at `NEUTRAL_OBSERVATION_GAIN`, and confidence rises because the gap agreed. The quick
path therefore had no way to say "it was due today, and it was already bone-dry". A plant that is dry on its due date
every time never got a shorter interval. The only remaining way to teach that was to water early and answer "The plant
needed it" to "Why now?". Product ADR-0062 and technical ADR-0037 made Add Care Log an edit-only form that no longer
updates the model, so its old chip is not an alternative (#838).

Product ADR-0034 gave Plant Detail's Water button a "Log watering" date picker, so an option inside that picker costs no
extra tap there. It left the Care tiles out of scope. Their long-press menu logged **Water** and **Water & fertilize**
at once, with no date and no place for the option.

Alternatives rejected:

- **An Undo-style snackbar after the log.** Ruled out by the product owner.
- **Always asking on schedule.** It adds a step to the common "on schedule, nothing to say" case, which product ADR-0030
  removed on purpose.
- **Offering the option on a late date.** A late gap never shortens the interval (product ADR-0033).
- **Restoring the Add Care Log chip's learning.** That would supersede technical ADR-0037 and is not wanted.

## Decision

- The "Log watering" date picker gets an optional **Soil was already too dry** checkbox, off by default. It is a
  watering-only option; the same picker's Repot, Prune and Add-photo uses never show it.
- **Its visibility follows the date currently picked, live.** It is judged against that date's own chronological
  predecessor, the same one the reason gate uses:

  | Picked date | Option shown? | Ticked | Not ticked |
  |---|---|---|---|
  | On schedule | yes | saves `TOO_LATE` (via `WateringReason.PLANT_NEEDED_IT`); the base shortens and a `WATER_TOO_LATE` adjustment row is written | no reason, as product ADR-0030 |
  | Early | yes | saves `TOO_LATE` and **skips** "Why now?" (same answer) | "Why now?", as product ADR-0030 |
  | Late | no | not applicable | "Why was it late?", as product ADR-0033 |
  | Gap spans dormancy or an Away trip | no | not applicable | unchanged (the observation is excluded anyway) |

  A tick on a date where the option is hidden is ignored, so moving the date after ticking never applies a stale answer.
- The tick reuses the existing reason path: `WateringReason.PLANT_NEEDED_IT` maps to `WateringFeedback.TOO_LATE`. No
  schema change. The duplicate guard and `CareSchedule.isUnattributedOffScheduleObservation` are unchanged (a tick is
  non-null feedback; an unticked on-schedule log stays `null`).
- The liquid-fertilizer paired WATER log ("Water & fertilize") gets the same option.
- **Care tiles**: the long-press menu's **Water** and **Water & fertilize** items open the same "Log watering" picker
  instead of logging immediately. Confirming goes through the same gate as Plant Detail (on schedule, dormancy, Away,
  then the reason sheet), with backdating, the duplicate guard, the post-watering reminder and the interval suggestion.
  The wording says "too" dry and does not blame the user (#649).

## Consequences

- Amends product ADR-0030: its "Water, on schedule: asks nothing" row now has an optional tick, and an early watering
  with the tick skips "Why now?". Amends product ADR-0034: the Care tiles are now in scope of the "Log watering" picker.
- Care gains one confirm tap on Water and Water & fertilize and gains backdating.
- Plant List, Calendar (being removed, #839), the notification's "Watered" action and Add Care Log are unchanged.
- #849's "Water only" Care item should open the same picker.
- The late prompt still never offers a shortening option (product ADR-0033).
