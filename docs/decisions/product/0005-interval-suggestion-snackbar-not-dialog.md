# Product ADR-0005: Watering interval suggestion uses a Snackbar, not a dialog

**Status**: accepted

**Date**: 2024-01-01

## Context

After saving a watering log with TOO_SOON or TOO_LATE feedback, the app suggests a new watering interval. The user needs to see this suggestion and decide whether to apply it or ignore it.

Two common patterns for this kind of prompt:
- **Modal dialog**: blocks the UI until the user makes a choice (Apply / Dismiss). Ensures the user sees and acts on the suggestion.
- **Snackbar with action**: non-blocking, appears at the bottom of the screen, auto-dismisses after a timeout. Can be swiped away or ignored.

The interval suggestion is a *recommendation*, not a required decision. The user may already know they want to keep the current interval, may want to think about it, or may simply not care at this moment. A modal dialog would force an immediate decision even when the user has no strong preference.

## Decision

The suggestion is shown as a Snackbar with:
- Message: "Suggested watering interval: X days"
- Action button: "Apply"
- Duration: `SnackbarDuration.Long` (longer than the default to give the user time to read and decide)

If the user taps "Apply", the plant's watering interval is updated. If the Snackbar times out or is dismissed, the suggestion is discarded silently.

See `PlantDetailScreen.kt`, lines 81–90.

## Consequences

- Users who log waterings quickly (common in a care routine) are not interrupted by a dialog asking about intervals.
- Users who want to act on the suggestion have enough time (`Long` duration) to read it and tap "Apply".
- If the Snackbar disappears before the user notices it, the suggestion is lost. The user would need to log another watering with non-JUST_RIGHT feedback to see a suggestion again.
- The Snackbar is shown on `PlantDetailScreen`, which the user returns to after saving the log. This is the right context — they are looking at the plant's information when the suggestion appears.
