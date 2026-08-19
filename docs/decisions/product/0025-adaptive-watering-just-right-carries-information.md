# Product ADR-0025: Adaptive watering — JUST_RIGHT now carries information

**Status**: accepted

**Date**: 2026-08-19

**Supersedes**: ADR-0001

## Context

ADR-0001's second precondition for showing the watering interval suggestion was "feedback is not
JUST_RIGHT" — the reasoning being that a JUST_RIGHT rating means the current interval is working, so
there is nothing to suggest.

#568 (behind the `adaptive_watering` developer-mode flag) replaces the fixed ±1-day nudge with a
multiplicative correction whose step size is weighted by a per-plant confidence counter
(`Plant.wateringConfidence`, 0-5). Under this model, a JUST_RIGHT observation is no longer inert:

- `target = observed × 1.00` can differ from the stored `base` interval whenever the user's actual
  watering rhythm has drifted from what the app has scheduled — the exact "defaulted JUST_RIGHT on an
  off-schedule watering" case that today's system fails to catch (see technical ADR-0021's replay
  scenario 3b).
- Whether the observed gap agrees with the predicted interval is *the* signal that raises confidence
  (never the chip value itself — see technical ADR-0021). A genuine on-schedule JUST_RIGHT is evidence
  the schedule is dialed in; a defaulted JUST_RIGHT on an off-schedule watering is not, and the two are
  now distinguishable from timestamps alone.

ADR-0001's condition 2 assumed JUST_RIGHT was uninformative. That assumption no longer holds once the
flag is on.

## Decision

Condition 2 is dropped. The suggestion (and the underlying computation feeding it) is shown whenever:

1. Care type is WATER.
2. Not in edit mode.
3. The computed interval differs from the current interval.

This is the same list as ADR-0001 minus the JUST_RIGHT exclusion — conditions 1, 3, and 4 (renumbered
1-3 above) are unchanged and still enforced by `AddCareLogViewModel.computeSuggestedInterval()` /
`QuickLogUseCase`'s equivalent guard.

This only changes observable behavior when `adaptive_watering` is on. With the flag off,
`CareSchedule.computeSuggestedInterval()` is untouched, `JUST_RIGHT` still returns `actualIntervalDays`
verbatim (unchanged from before this issue), and condition 3 (computed ≠ current) already suppresses
the dialog whenever nothing would change — so flag-off behavior is byte-for-byte identical to today,
even though ADR-0001's stated *rationale* (condition 2) no longer appears as a separate check in the
adaptive path.

## Consequences

- With the flag on, a user who always taps the pre-selected JUST_RIGHT chip can still see the interval
  suggestion dialog, if their actual watering rhythm has drifted from the stored schedule. This is
  intentional — it is the fix for the silent-failure case ADR-0001 could not detect.
- `Plant.wateringConfidence` gives the app a way to tell "the schedule is validated by observed gaps"
  apart from "the user never disagreed because they never looked" — the latter alone no longer reads as
  agreement.
- Distinguishing a user who *chose* JUST_RIGHT from one who left the pre-selected default untouched is
  explicitly out of scope here (#570, a later part of the #285 split) — this ADR only changes what the
  *timestamps* can prove, not what the chip itself means.
- This is scoped entirely to the flag: while `adaptive_watering` is off, every behavior ADR-0001
  described is preserved exactly.
