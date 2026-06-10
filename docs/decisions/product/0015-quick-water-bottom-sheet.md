# Product ADR-0015: Quick-water opens a feedback bottom sheet

**Status**: accepted, supersedes [ADR-0002](0002-default-feedback-just-right.md)

**Date**: 2026-06-10

## Context

ADR-0002 decided that the quick-water button on PlantCard would always log with JUST_RIGHT feedback silently. This kept the flow fast but meant users could never record TOO_SOON or TOO_LATE feedback from the plant list, undermining the adaptive interval feature.

Issue #126 revisited this decision with the goal of finding a flow that is both fast (≤ 2 taps for the common case) and informative (feedback other than JUST_RIGHT reachable without a screen transition).

## Decision

Tapping the water drop icon on PlantCard opens a `WaterFeedbackBottomSheet` — a `ModalBottomSheet` containing:

- The plant name as a title ("Water Monstera?")
- The "How was the soil?" prompt (per ADR-0011)
- Three FilterChips for TOO_SOON / JUST_RIGHT / TOO_LATE, with JUST_RIGHT pre-selected
- A "Log" button

Tapping "Log" with the default JUST_RIGHT chip requires 2 taps from the plant list (water icon → Log), preserving the fast-path expectation. Selecting a different chip and tapping Log requires 3 taps.

Dismissing the sheet without tapping Log cancels the action — no log is written.

After the log is saved, if the adaptive interval suggestion fires (computed interval differs from stored interval), a suggestion AlertDialog appears directly in PlantListScreen — no screen navigation needed.

The quick-fertilize button behaviour is unchanged (no feedback, single tap).

## Consequences

- Users can now record TOO_SOON or TOO_LATE feedback without navigating to the Add Care Log screen.
- The adaptive watering interval suggestion fires from the quick-water path as well as the full Add Care Log path.
- The 1-tap silent quick-water is replaced by a 2-tap bottom-sheet flow. This is a minor increase in friction for routine waterings, accepted as the tradeoff for richer feedback capture.
- The `WaterFeedbackBottomSheet` composable follows the same `ModalBottomSheet` pattern as `PhotoSourceBottomSheet`.
