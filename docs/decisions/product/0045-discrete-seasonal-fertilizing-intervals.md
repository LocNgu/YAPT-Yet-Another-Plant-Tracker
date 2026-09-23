# Product ADR-0045: Discrete manual seasonal fertilizing intervals

**Status**: superseded by [ADR-0048](0048-fertilizing-active-seasons.md)

**Date**: 2026-09-23

## Context

Plants often need fertilizer at a different cadence through the year: full strength during active
growth, a longer interval during transitional months, and none while dormant. A single
`fertilizingIntervalDays` cannot express that taper. Watering already uses ADR-0026's computed cosine,
but that shape is deliberately a continuous household-wide model; fertilizing guidance is step-like,
plant-specific, and set by the user rather than learned.

Issue #286 originally proposed four manual season slots before the per-plant dormancy window existed.
Product ADR-0044 subsequently established that dormancy is one shared fact about a plant and that #286
must consume its month pair by reference instead of encoding a second winter pause. A cosine-based
fertilizing amplitude was reconsidered after dormancy shipped, but rejected: it would still infer a
shape the user did not choose and could not express a deliberately asymmetric growing-season taper.

## Decision

Keep `fertilizingIntervalDays` as the required fallback and add four nullable per-plant intervals:
`fertilizingIntervalSpring`, `fertilizingIntervalSummer`, `fertilizingIntervalAutumn`, and
`fertilizingIntervalWinter`. The current season is selected from the current date and the same
timezone-derived hemisphere used by `SeasonalWatering`; a null slot falls back to
`fertilizingIntervalDays`. Existing plants therefore remain bit-for-bit unchanged.

The season is evaluated at status-computation time, not at the last fertilizing event. A due date can
therefore move when the device enters a new season. The first-fertilize path remains anchored to
`createdAt + FIRST_FERTILIZE_GRACE_DAYS` and does not use a season slot until the first FERTILIZE log
exists.

The ADR-0044 dormancy window suppresses fertilizing due/overdue flags as well as watering flags while
the plant is dormant. The underlying fertilizing due date is still computed and becomes active again
on exit; Plant List groups it as Dormant and Calendar omits fertilizing dates that land inside the
window. No season slot means "pause"; dormancy is the only pause mechanism, including for
summer-dormant winter growers.

Add/Edit Plant is the canonical four-field editor: a "Same for all seasons" switch defaults on and
clears the optional slots, while the expanded fields accept 1-180 days or blank for fallback. Plant
Detail's Fertilize tab shows a read-only summary and links to Add/Edit rather than duplicating a dense
four-field auto-persist editor. There is no adaptive feedback, confidence, or audit history for
fertilizing.

Room moves 14→15 with four nullable columns, and `.yapt` backup schema moves 17→18 with null defaults.

## Consequences

- Northern and southern devices interpret the same named season correctly without location
  permission or network access.
- Partial configuration is valid and stable; changing the main interval also changes every fallback
  season.
- Liquid-fertilizer pairing and notification wording are unchanged. Only the fertilizing due check
  receives the current season's interval.
- Crossing a season boundary can move an already-computed next date because the schedule has a
  today-effective posture, matching the user-visible season choice rather than preserving the season
  of the prior log.
- Dormancy now suspends both scheduled care types. Repotting and custom reminders remain independent.
