# Product ADR-0063: Watering overview — a read-only page of how often every plant needs water

**Status**: accepted

**Date**: 2026-10-14

## Context

Plant List can sort by *when* watering is due, but nothing shows *how often* each plant needs water. With dozens
of plants, checking Plant Detail one at a time gives no picture of the spread of intervals, of plants whose
learned interval looks wrong, or of groups of thirsty plants worth keeping together. Issue #901 asks for that
overview; it is also the base the later Away-mode "how often should a sitter come?" step (#899) reads.

Alternatives considered:

- **A Plant List sort or filter by interval.** Rejected. A sort reorders a list the user scans for due care; this
  is a different question that wants groups and a summary, as product ADR-0059 concluded for repotting.
- **Put dormant-cadence plants in the band their cadence falls into.** Rejected. A plant on a 28-day winter
  cadence would read as "every 15+ days" all winter and mislead the in-season picture. Resting keeps the bands
  about the in-season rhythm.
- **Week-based bands (7 / 14 / 28 / 29+).** Rejected. Most houseplants sit between 2 and 14 days, so a week-wide
  first band would hold most of the collection and tell the user nothing.
- **A persisted expand/collapse state or a room filter.** Rejected as scope; "Show all" stays local to the screen.

## Decision

- **A read-only audit page**, reached from a Settings row directly after Repotting overview and nested the same
  way (bottom bar hidden, back returns to Settings). It never edits an interval, logs care, creates a Care task,
  a reminder, a notification or a Plant List sort option, and it persists nothing: no schema, DataStore or backup
  change.
- **Groups, in order, empty ones omitted:** every 1–3 days, 4–7, 8–14, 15+, then **Resting**, then **No watering
  schedule**. Every active plant is in exactly one; archived plants are never listed or counted.
- **Resting** holds every plant whose dormancy window is active and that has a watering interval: a suspended
  plant (no cadence) reads "Watering paused" with no due date; a plant on a dormant cadence shows that fixed
  cadence ("every 28 days") and its next due date. A plant with no `wateringIntervalDays` is in No watering
  schedule even with a dormancy window, because there is nothing to suspend.
- **The interval is today's, never the stored literal:** `CareSchedule.effectiveWateringIntervalDaysForDisplay()`
  for a normal-season plant (so a seasonal plant can change band as the season turns; a pinned plant or amplitude
  Off shows the stored value through the same function), and the fixed `dormantWateringIntervalDays` for a
  dormant cadence, matching "Why this date?". The next due date is `PlantCareStatus.nextWateringDueAt`; the page
  adds no due-date logic.
- **Summary:** a count line ("72 plants · 58 on a schedule · 9 resting · 5 without a schedule", zero parts
  omitted, parts sum to the total, resting plants not counted as on a schedule) and a proportion bar in neutral
  tints that never reuse due-status colours, with a content description that reads out the counts.
- **Rows** show cover photo, name, room when set, "every N days" and the next watering through
  `relativeDateText()`. Order within a group is interval, then name, then id (Resting by cadence, suspended last;
  No schedule by name). A group over five rows shows five plus "Show all N". Tapping a row opens Plant Detail on
  the Water tab.
- **One pure `WateringOverviewBuilder`** (`domain/watering/`) computes every group from the plants, the last WATER
  log per plant and the seasonal amplitude, so no per-plant Room query runs and #899 can reuse it.

## Consequences

- Plants with a suspiciously long or short learned interval become visible in one place, without touching Care,
  the notification, or any due-date rule.
- The page depends on a day-change ticker, the seasonal amplitude and the WATER-log stream; it stays correct at
  midnight, at a seasonal step and after a watering without a restart (technical ADR-0035).
- Editing intervals from the page, a room filter or search, and the sitter-rhythm recommendation (#899) are out
  of scope.
