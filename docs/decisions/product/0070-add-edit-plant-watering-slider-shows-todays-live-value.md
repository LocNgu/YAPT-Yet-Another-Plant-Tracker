# Product ADR-0070: Add/Edit Plant's watering slider shows today's live interval

**Status**: accepted

**Date**: 2026-10-22

## Context

Product ADR-0068 (#927) made the Plant Detail Water tab and hero show today's effective watering interval
(`CareSchedule.effectiveWateringIntervalDaysForDisplay()`) instead of `Plant.wateringIntervalDays`, the value as of the
last discrete event, and recorded Add/Edit Plant as a temporary exception (#928). Until then the two editors disagreed
about what the number meant, and Add/Edit's `save()` re-anchored `wateringBaseIntervalDays` from the slider on every save
whose slider differed from the stored literal. With a stale literal that meant a name-only edit silently rescheduled the
plant: the seed was "7", the app computed the schedule from "9", and Save compared the slider with "7".

A new plant has no stored value yet. Its default 7 already means "the value at today's season", and `saveNew()`
de-seasonalizes it.

Alternatives considered for detecting an untouched slider: a dirty flag set by the slider callbacks (but dragging away and
back would then count as an edit, and every code path writing `wateringIntervalDays` would need to set it), and comparing
with the stored literal (the bug). Comparing with the seeded live value needs no extra state.

## Decision

- **In edit mode the slider, its header and its `stateDescription` show the live value**, seeded once when the plant loads
  from `LiveWateringInterval.compute()` with the live amplitude and hemisphere (the same function as Plant Detail). The seed
  does not follow midnight and there is no day-change ticker on this form. A live value above 60 shows its true value while
  the thumb sits at 60.
- **Save compares the slider with the seeded value.** Equal means the interval is untouched, whether or not the thumb was
  moved away and back: no base re-anchor, no confidence reset, no `MANUAL_EDIT` row, and the stored literal is written back
  unchanged (writing the live value into the literal would move the base fallback of a plant that never recorded a base).
  A different value re-anchors the base as `typed / season(today)` (unless pinned or amplitude is Off) and resets confidence
  to 0.
- **The `MANUAL_EDIT` audit row is gated on a base-space change**, `round(newBase) != round(storedBase)`, as in product
  ADR-0068.
- **Pin toggled on with an untouched slider pins the displayed (live) value** and carries the stored base over. Pin toggled
  off with an untouched slider keeps the stored base and resumes seasonal behaviour. Neither writes a `MANUAL_EDIT` row.
- **New plants are unchanged**: the typed default is de-seasonalized by `saveNew()`, and no hint is shown.
- **The seasonal hint**, the plain-text "Adjusted for winter · base 8 days" (same string as the Water tab), appears under the
  slider in edit mode only, while the reminder is on, the form's pin switch is off, amplitude is on, the plant is not in its
  dormancy window today and today's value differs from `round(base)`. It is not tappable; Add/Edit has no "Why this date?".
- This closes the temporary divergence recorded in product ADR-0068 and restores the "mirrors its Add/Edit counterpart"
  clause of product ADR-0023 for the watering interval.

## Consequences

- A name-only (or any unrelated) save on Add/Edit no longer changes the schedule, even with a stale literal.
- `Plant.wateringIntervalDays` keeps its meaning (the value at the last discrete event); schedule maths is untouched and
  there is no migration.
- The seed is read once. A form left open across midnight keeps the earlier day's number; moving the slider de-seasonalizes
  it at save time.
- Related: product ADR-0068, product ADR-0023, product ADR-0026 (computed seasonal factor), product ADR-0048
  (`SteppedSlider`).
