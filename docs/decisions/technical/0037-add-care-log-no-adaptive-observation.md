# Technical ADR-0037: Add Care Log no longer observes WATER logs or hands back a suggestion

**Status**: accepted

**Date**: 2026-10-06

## Context

Add Care Log used to be a create-and-edit form. After saving a WATER log it ran the shared
`AdaptiveWateringObservation` (technical ADR-0030, with the form's chronological gap source from technical
ADR-0033), computed a suggested interval, and returned it to `PlantDetailScreen` through the previous back stack
entry's `savedStateHandle` (technical ADR-0006).

Product ADR-0062 (issue #532, part 3) made the screen edit-only. An edit never adapts: `observe(isEditMode = true)`
returned `null` before touching anything. So from that point the form's whole observation call, the suggestion
fields on `Event.Saved`, the `NavGraph` handoff on both sides, `PlantDetailViewModel`'s
`handleSuggestedWateringInterval` entry point, the form-only `CHRONOLOGICAL_PREDECESSOR_OR_FIRST_CONFIGURED` gap
source and the `isEditMode` parameter were all dead. The form's `dataStore` and `wateringAdjustmentRepository`
dependencies existed only to feed that call.

Alternatives considered:

- **Leave the dead path in place** (part 3 did this deliberately to keep that PR small). Rejected: it keeps two
  ADRs describing behaviour that cannot happen, and a future reader would reasonably assume the form still
  adapts.
- **Keep `isEditMode` as a guard "in case create mode returns".** Rejected: the guard encodes a mode the screen no
  longer has; if a create path ever returns it should go through `QuickLogUseCase` like every other.

## Decision

`QuickLogUseCase` is the only caller of `AdaptiveWateringObservation.observe()`. Add Care Log calls only
`feedbackForLog()`, the write-time dormancy gate, which still runs on every save (including edits and re-saves,
excluding the edited row from its own predecessor lookup). Concretely:

- `AddCareLogViewModel` loses `computeSuggestedInterval()`, the suggestion fields on `Event.Saved` (now a plain
  `data object`), and the `dataStore`/`wateringAdjustmentRepository` constructor, `Factory` and `NavGraph`
  parameters. It builds the observation helper with neither, which is enough for `feedbackForLog()`.
- `NavGraph` loses the `suggestedWateringInterval`/`suggestedWateringBaseInterval` `savedStateHandle` handoff, and
  `AddCareLogScreen.onNavigateBack` takes no arguments. `handleSuggestedWateringInterval` is deleted;
  `applySuggestionOrPrompt` and the rest of the interval actions stay, because the quick-log surfaces use them.
- `AdaptiveWateringObservation.observe()` loses `isEditMode`, and the `GapSource` enum with its `gapSource`
  parameter is removed. The remaining policy is the one `QuickLogUseCase` already used: the gap comes from the new
  log's chronological predecessor (`getLastWateringBefore`), and no predecessor means no observation.

This supersedes technical ADR-0006 (the `savedStateHandle` suggestion handoff) and technical ADR-0033 (the form's
`CHRONOLOGICAL_PREDECESSOR_OR_FIRST_CONFIGURED` gap source). It amends technical ADR-0030's clause that
`AddCareLogViewModel` calls the shared observation after inserting a WATER log. ADR-0030's single observation
path, its feedback gate, its clock split and its other clauses remain in force. The product ADR-0006 interval
suggestion dialog is unchanged: the quick-log surfaces still produce a suggestion and show it. No Room schema
change.

## Consequences

- There is one observation call site, so a change to dormancy, bootstrap, confidence or adjustment provenance
  needs no parallel check in the form.
- The first-ever-watering configured-interval fallback that ADR-0033 preserved for the form is gone with it. It was
  already unreachable once the form stopped creating logs; `QuickLogUseCase` never had it.
- `CareLogRepository.getLastTwoWaterings` stays: `QuickLogUseCase` still uses it for the override-clear gate.
- Anything that wants to hand a value back from a pushed screen to Plant Detail again would need a new decision;
  this one does not rule out `savedStateHandle` for that, only records that the suggestion no longer uses it.
