# Product ADR-0001: When the watering interval suggestion is shown

**Status**: accepted

**Date**: 2024-01-01

## Context

After logging a watering, the app can suggest a new watering interval based on the user's feedback (TOO_SOON / JUST_RIGHT / TOO_LATE) and the actual days since the last watering. The question is: when should this suggestion appear, and when should it stay silent?

Showing it too often creates noise. Showing it when the interval wouldn't actually change is pointless. Showing it when editing a past log (not adding a new one) would be misleading — the user is correcting a record, not reporting how a watering felt.

## Decision

The suggestion is shown only when **all four** of these conditions are true:

1. **Care type is WATER** — the feedback and interval system only applies to watering, not fertilizing or other care types.
2. **Feedback is not JUST_RIGHT** — if the user rates the watering as "Just Right", the current interval is working and no change is needed.
3. **Not in edit mode** — editing a past log reflects a data correction, not new feedback about the current schedule. The suggestion is suppressed entirely in edit mode.
4. **Computed interval differs from current interval** — if the math produces the same number of days already set, there is nothing to suggest.

See `AddCareLogViewModel.kt`, `computeSuggestedInterval()`.

## Consequences

- Users only see the suggestion when it represents a genuinely actionable change.
- Editing a historical log (to fix a typo, adjust the date, add a note) never triggers an interval suggestion, which is correct — the edit is about record accuracy, not care quality.
- If a user always rates waterings as JUST_RIGHT, they will never see the suggestion. This is intentional: consistent JUST_RIGHT feedback means the schedule is working.
- The suggestion system is one-shot per watering (it fires immediately after saving), not a running average. This keeps it simple but means one mismatched watering can produce a suggestion even if the overall trend is good.
