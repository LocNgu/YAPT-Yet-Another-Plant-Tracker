# Product ADR-0006: Watering interval suggestion uses an AlertDialog, not a Snackbar

**Status**: superseded by [ADR-0028](0028-why-this-date-watering-transparency-sheet.md) (for the "Ask before changing intervals" toggle's off state only — the dialog remains the default, on-by-default behavior)  
**Date**: 2026-05-25  
**Supersedes**: ADR-0005

## Context

ADR-0005 chose a Snackbar with an "Apply" action for the watering interval suggestion on the grounds that the suggestion is a recommendation, not a required decision, and a modal dialog would feel interruptive.

In practice, the Snackbar approach had a critical usability flaw: the user typically logs a watering and immediately navigates away or locks the device. The Snackbar auto-dismisses before they see it, and the suggestion is silently lost. Recovering it requires logging another non-JUST_RIGHT watering — which is the wrong action to take just to see a UI element.

Additionally, the "apply or ignore" framing of a Snackbar was deceptive: tapping anywhere other than "Apply" discarded a potentially meaningful schedule change with no indication it happened.

## Decision

The suggestion is shown as a modal `AlertDialog` (implemented in PR #150, issue #138) with:

- **Title**: "Adjust watering interval?"
- **Body**: a pre-filled, editable numeric `TextField` containing the suggested interval in days
- **Positive button**: "Apply" — disabled when the field is empty or non-positive
- **Negative button**: "Dismiss" — permanently discards the suggestion
- Tapping the scrim or back also discards the suggestion

If the suggested interval equals the current interval, no dialog is shown and the suggestion is cleared silently. The dialog fires on `PlantDetailScreen` immediately after returning from `AddCareLogScreen` when a suggestion is available in `savedStateHandle`.

## Consequences

- The suggestion can no longer be missed by navigating away before the Snackbar renders.
- The user explicitly chooses Apply or Dismiss — no silent discard on timeout.
- The editable TextField lets the user fine-tune the value before applying, not just accept or reject the computed suggestion.
- The modal is only shown for JUST_RIGHT (when actual ≠ current), TOO_SOON, and TOO_LATE feedback; it is never shown for non-WATER care log types. This limits interruptions to the cases where an interval change is genuinely warranted.
- The cost is a slightly more interruptive UX for users who log waterings and want to move on quickly. Given that interval adjustments are infrequent and consequential (they affect future reminders), this tradeoff was judged acceptable.
