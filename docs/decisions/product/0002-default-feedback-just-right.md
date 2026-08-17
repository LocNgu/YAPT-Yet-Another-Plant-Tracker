# Product ADR-0002: Default watering feedback is JUST_RIGHT; quick log skips feedback entirely

**Status**: superseded by [ADR-0016](0016-quick-water-bottom-sheet.md)

**Date**: 2024-01-01

## Context

When a user logs a watering, they can rate it: TOO_SOON (watered before the plant needed it), JUST_RIGHT, or TOO_LATE (plant was already thirsty). This feedback drives the interval suggestion system.

Two related decisions needed to be made:

**Default chip selection**: When the "Add Care Log" screen opens for a WATER entry, which feedback option should be pre-selected? Leaving nothing selected forces an explicit choice every time, which adds friction to the most common action in the app. Defaulting to a wrong option silently skews the interval data.

**Quick log**: The plant list has quick-tap water/fertilize buttons for fast logging without opening the full log screen. Showing a feedback prompt inline on the card would break the "quick" nature of the feature. But logging with no feedback at all would leave the feedback field null, which is inconsistent with how full water logs work.

## Decision

**Add Care Log screen**: JUST_RIGHT is pre-selected when the screen opens for a new WATER entry. If the user switches the care type away from WATER and then back, JUST_RIGHT is re-selected. In edit mode, the saved feedback value is loaded instead.

**Quick log**: Always uses JUST_RIGHT without asking. The rationale is that a user tapping the quick-log button is confirming they are watering the plant now, implying the timing is reasonable. If the timing feels wrong, they would use the full log flow to record that.

See `AddCareLogViewModel.kt` line 34 (default), `PlantListViewModel.kt` line 86 (quick log).

## Consequences

- The most common case (watering on schedule) requires no extra tap to set feedback.
- Quick log logs are indistinguishable from full logs with JUST_RIGHT feedback in the database. If many quick logs are present, the interval suggestion system may be less responsive to real timing problems — because JUST_RIGHT suppresses suggestions (see Product ADR-0001).
- If the user wants to record TOO_SOON or TOO_LATE feedback, they must use the full Add Care Log screen, not the quick button.
