# Technical ADR-0033: Add Care Log measures the watering gap from the entered log's predecessor

**Status**: superseded by [ADR-0037](0037-add-care-log-no-adaptive-observation.md)

**Date**: 2026-09-26

## Context

Technical ADR-0030 moved the Add Care Log form onto the shared `AdaptiveWateringObservation` path, but kept
the form's own gap source, `NEWEST_PAIR_OR_CONFIGURED`, as a compatibility policy. That source took the
observed gap from the plant's two globally newest WATER logs, or from the configured interval when fewer than
two existed. Quick watering had already moved to the new log's chronological predecessor in #654/#671,
because a backdated log can be older than both newest rows.

The form's date picker allows the same kind of backdating. Issue #673 records the resulting bug: suppose a
plant was watered Jan 1, Jan 13 and Jan 27, and the user adds a forgotten Jan 8 watering. The model learned
from the unrelated Jan 13 → Jan 27 gap instead of Jan 1 → Jan 8. ADR-0030 left unifying the two gap sources
for a later change "after separately assessing suggestion behavior."

That assessment shows the two rules only disagree on backdated entries. For a non-backdated entry the new log
is the newest one, so its strictly earlier predecessor is the second row of the newest pair. The gap,
confidence transition, adjustment rows and suggestion are therefore identical. The one remaining difference
is the case with no predecessor. The configured fallback only ever fired when the plant had fewer than two
WATER logs, that is, for its first-ever watering. A log backdated before every existing watering is a new
state: it has later waterings on file but no earlier one.

Alternatives considered:
- **Always skip without a predecessor**, exactly like quick watering. This would change today's
  first-watering behavior, which was never the bug.
- **Always fall back to the configured interval without a predecessor.** A log backdated before the oldest
  watering would then record an invented on-schedule observation, even though no gap exists to support it.

## Decision

This amends technical ADR-0030's gap-source clause. The form's gap source is now
`CHRONOLOGICAL_PREDECESSOR_OR_FIRST_CONFIGURED`. The observed gap always comes from the entered log's
chronological predecessor (`CareLogRepository.getLastWateringBefore`, strictly earlier), which is the same
lookup quick watering and the dormancy check already use. When no predecessor exists, the configured-interval
fallback applies only if the plant has fewer than two WATER logs, the just-inserted one included. That is the
exact condition under which the old newest-pair lookup fell back. A log backdated before every existing
watering gets no observation, as in quick watering.

The two gap-source values now differ only in that no-predecessor case. ADR-0030's single observation path, its
feedback gate, its clock split and every other clause remain in force. No Room schema change is needed.

## Consequences

- Backdated form entries between existing waterings now learn from their true neighbor. Non-backdated
  entries and a plant's first-ever watering behave exactly as before.
- Gap and dormancy now read the same predecessor, so the #776 P1-4 mismatch cannot recur on the form. That
  was a stale same-day duplicate in the newest pair zeroing a genuine dormancy-spanning gap.
- A log backdated before the oldest watering writes no `watering_adjustments` row and no confidence change.
  This leaves a later watering's own observation unaffected.
- The first-watering fallback remains a deliberate difference from quick watering, which skips it. Removing
  it would be a separate product decision about whether a first log with no gap should count as agreement.
