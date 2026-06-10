# Product ADR-0015: Quick-water flow uses a bottom sheet with feedback chips

**Status**: accepted
**Date**: 2026-06-10

## Context

Issue #126 identified two conflicting watering paths:

1. **PlantCard quick-water button** — one tap, but always logs `JUST_RIGHT` feedback silently. Users cannot record soil state, so the adaptive interval system is blind to over- or under-watering.
2. **PlantDetail → + → select type → select feedback → Save** — captures feedback but requires 4+ steps and a full-screen transition.

The alternatives considered (from the issue) were:
- **Bottom sheet on quick-water tap** — small modal with feedback chips + one button
- **Inline chip row on PlantCard** — expand the card in place; collapses after logging
- **Swipe gesture** — encode feedback via swipe direction
- **Long-press vs tap** — short tap = JUST_RIGHT (current), long-press = feedback picker
- **Streamlined AddCareLog** — pre-fill WATER + JUST_RIGHT, collapse non-essential fields

The bottom sheet was chosen because:
- It keeps the one-tap path to begin (tap the water drop icon → sheet appears)
- JUST_RIGHT is pre-selected, so tapping "Log watering" is the 2-tap happy path
- TOO_SOON / TOO_LATE are reachable without navigating away
- No change to the card layout or gesture recognition is required
- Dismissing the sheet without tapping "Log watering" cancels the action (no silent log)
- The pattern is already used in the app (PhotoSourceBottomSheet)

## Decision

Tapping the water drop quick-log button on a PlantCard opens a `ModalBottomSheet`
(`QuickWaterBottomSheet`) containing:
- A title "Watering {plant name}"
- The "How was the soil?" prompt
- Three `FilterChip`s for feedback (Still wet / Just right / Too dry), pre-selected to JUST_RIGHT
- A full-width "Log watering" primary button

Tapping "Log watering" logs the watering with the selected feedback. If the adaptive interval system produces a suggestion, the app navigates to PlantDetailScreen (setting `suggestedWateringInterval` on the savedStateHandle) so the existing interval-adjustment dialog is shown. If no suggestion is produced, a Snackbar confirms the log.

Dismissing the sheet (tap outside, back gesture, or drag) cancels the action with no log written.

The existing `quickLog(plantId, CareType.WATER)` path (which logged JUST_RIGHT silently) is no longer called for watering; the bottom sheet replaces it. The fertilize quick-log path is unchanged (fertilize has no soil-state feedback).

ADR-0002 documented that "quick log always uses JUST_RIGHT without asking" and "if timing feels wrong, use the full log flow." This ADR supersedes that behaviour for the watering path: feedback is now surfaced inline in the quick-water flow.

## Consequences

- The happy path (JUST_RIGHT watering) requires exactly 2 taps from the plant list — no regressions on speed.
- Users who want TOO_SOON or TOO_LATE no longer need to navigate to AddCareLog.
- The adaptive interval system is more accurate because real feedback is captured rather than always assuming JUST_RIGHT.
- When an interval suggestion fires from the quick-water sheet, the user is navigated to PlantDetailScreen where the existing interval dialog appears. This is the same UX as the AddCareLog path.
- `PlantListViewModel` gains `quickLogWaterWithFeedback()` and a `quickWaterResult: SharedFlow<QuickWaterResult>` for the result (snackbar message + optional suggestion).
- `PlantListScreen` gains a new `onNavigateToPlantWithSuggestion` callback used when a suggestion is present.
- Fertilize quick-log is unchanged.
