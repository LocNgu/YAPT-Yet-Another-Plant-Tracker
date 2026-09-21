# Technical ADR-0030: One adaptive watering observation path

**Status**: accepted

**Date**: 2026-09-21

## Context

Issue #780 identified seven bugs in `AddCareLogViewModel` during PR #776. Its hand-maintained
adaptive-observation copy had missed prior fixes in `QuickLogUseCase`, and differently ordered guards
made later fixes land incorrectly. Technical ADR-0023's Consequences recorded that duplication as
deliberate call-site glue when the logic was small. Dormancy, bootstrap, confidence, and seasonal
behavior have since made that choice unreliable.

## Decision

`AdaptiveWateringObservation` owns the single WATER observation path: chronological predecessor
lookup, dormancy and feedback exclusion, history bootstrap, confidence transition, adjustment rows,
live effective-interval comparison, and adaptive-state persistence. `QuickLogUseCase` and
`AddCareLogViewModel` call it after inserting a WATER log. Both call its feedback gate before writing
the log, including the form's edit and re-save path; edits pass the current log ID to exclude it from
its own predecessor lookup. Edit mode continues to skip adaptive observation itself.

The two entry points pass an explicit gap-source policy. Quick logging measures the new log against
its chronological predecessor. The form retains its existing newest-pair-or-configured gap arithmetic
to preserve currently correct behavior, while the chronological predecessor independently determines
whether this observation spans dormancy. The form's static feedback chip also requires write-time
suppression, while quick surfaces usually gate their reason prompt earlier. `loggedAt` drives all
observation decisions and writes; `displayNow` is used only to compare the two values shown today.
The form's `Plant.updatedAt` now follows `loggedAt`, matching its adjustment timestamps and the quick
path's established rule.

This supersedes only technical ADR-0023's convention to duplicate adaptive-observation call-site
glue. Its reset anchors, bootstrap sampling rule, and other decisions remain in force. No Room schema
change is needed.

## Consequences

- Changes to dormancy ordering, bootstrap, confidence, or adjustment provenance are made once and
  exercised through both entry points.
- The form's historical gap arithmetic remains an explicit compatibility policy. A future product
  change can unify it with the chronological gap after separately assessing suggestion behavior.
- Both entry points still own their care-log and UI flow, including the form's edit-mode rule and
  quick logging's override and reminder behavior.
