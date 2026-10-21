# Product ADR-0068: The Water tab and hero show today's live watering interval

**Status**: accepted

**Date**: 2026-10-21

## Context

`Plant.wateringIntervalDays` is the effective interval **as of the last discrete event** (manual edit, suggestion apply,
bootstrap, #702 fixup). With seasonal watering on and the plant unpinned, the due date uses today's value from
`CareSchedule.effectiveWateringIntervalDaysForDisplay()`, which keeps moving with the season. Product ADR-0023's Water-tab
card and the Plant Detail hero line both printed the stored literal, so a plant set to 7 days in October could read "Water
every 7 days" in January while the schedule really ran at about 9. Users read that line as "how often I water right now"
(#927).

The slider on that card writes through `setWateringInterval()`, which already de-seasonalizes the typed value as "the
value at today's season" (`base = edited / season(today)`). Showing the stored literal in a control whose save path
assumes today's value was inconsistent: releasing the thumb unmoved on a stale literal re-anchored the base and changed
the schedule.

Alternatives considered for the slider: keep showing the stored literal and change only the label; or make the slider edit
the season-neutral base and derive the label. The first still contradicts the save path; the second asks users to type a
number they never see on any other surface.

## Decision

- **The Water-tab slider shows and edits today's effective interval**, from `CareSchedule.effectiveWateringIntervalDaysForDisplay()`
  with the live amplitude and hemisphere (`PlantDetailViewModel.liveWateringInterval`, which takes a day-change ticker so it
  updates at midnight). The card header, the slider `stateDescription` and the −/+ steps all use this value. The range stays
  1..60; a live value above 60 shows its true value in the header while the thumb sits at 60 and + is disabled.
- **Committing exactly the live value is a full no-op** (no `updatePlant`, no base re-anchor, no `updatedAt` bump, no
  `MANUAL_EDIT` row). The comparison happens inside `plantEditMutex` against a live value computed for the freshly read plant,
  never the cached `plant` flow or the stored literal.
- **The `MANUAL_EDIT` audit row is gated on a base-space change** (`round(newBase) != round(storedBase)`), no longer on the typed
  literal differing from the stored literal. The before/after values are unchanged.
- **The seasonal hint** appears only when today's value differs from the rounded season-neutral base `round(wateringBaseIntervalDays)`
  and the plant is unpinned with amplitude on. Water tab: "Adjusted for winter · base 8 days" (season from
  `SeasonalFertilizing.season(today, hemisphere)`, lowercase through a string resource). It is tappable, opening "Why this date?",
  only while `computedNextWateringDueAt != null`. It is hidden while dormant.
- **The hero 💧 line is one live line**: "💧 Every 9 days · winter (base 8d)" when adjusted, "💧 Every 9 days" otherwise. Dormancy is driven
  by `careStatus.wateringScheduleMode`: `DORMANT_SUSPENDED` reads "💧 Dormant", `DORMANT_CADENCE` reads "💧 Every 3 weeks · dormant", and
  neither shows a seasonal hint. Nothing is shown until `careStatus` has loaded, and neither surface ever shows the stored literal.
  Like the Water-tab line, the hero line is tappable and opens "Why this date?" (from any tab) only while
  `computedNextWateringDueAt != null`; "💧 Dormant" is always plain text.
- **Home's summary card is unchanged** (dates only, product ADR-0060).
- **Add/Edit Plant keeps its stored-literal slider for now** (#928). This is an accepted temporary divergence from the
  "mirrors its Add/Edit counterpart" clause of product ADR-0023.

## Consequences

- `Plant.wateringIntervalDays` keeps its semantics (value at the last discrete event); only what the two Plant Detail surfaces
  display and what an unmoved commit does changed. Schedule maths is untouched.
- Releasing the slider unmoved, or ending a −/+ burst on the live value, no longer rewrites anything. That fixes the old
  re-anchoring bug.
- Re-enabling a switched-off schedule writes the value the card shows (the default when there is no live value).
- The two Plant Detail surfaces and Add/Edit disagree about what the number means until #928 lands.
- Related: product ADR-0026 (computed seasonal factor), product ADR-0048 (`SteppedSlider`), product ADR-0023 (inline settings).
