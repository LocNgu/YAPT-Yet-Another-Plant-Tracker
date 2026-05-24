# ADR-0006: Suggested watering interval returned to PlantDetailScreen via Navigation savedStateHandle

**Status**: accepted

**Date**: 2024-01-01

## Context

After the user saves a WATER log in `AddCareLogScreen`, the app computes a suggested new watering interval based on the user's feedback (TOO_SOON / JUST_RIGHT / TOO_LATE) and the actual days since the last watering. This suggestion needs to be surfaced to the user back on `PlantDetailScreen` as a Snackbar with an "Apply" action.

The challenge is routing a one-off value from the AddCareLog destination back to the PlantDetail destination after back-navigation.

Alternatives considered:
- **Shared `StateFlow` in a shared ViewModel or repository**: requires a scope that outlives both screens, adds coupling, and the value could be stale if not cleared.
- **Navigation argument on the back route**: Compose Navigation doesn't support passing data "back" as a named route argument; the destination is determined at the time of `navigate()`, not `popBackStack()`.
- **`savedStateHandle` on the previous back stack entry**: the Navigation component explicitly supports this pattern for returning results from a destination. The previous entry's `savedStateHandle` is set before popping, and the destination reads it via `LaunchedEffect`.

## Decision

`AddCareLogViewModel` computes the suggested interval and emits it as `Event.Saved(suggestedWateringInterval)`. In `NavGraph`, the `onNavigateBack` handler writes the interval to `navController.previousBackStackEntry?.savedStateHandle?.set("suggestedWateringInterval", interval)` before calling `navController.popBackStack()`.

`PlantDetailScreen` reads the value via a `LaunchedEffect(savedStateHandle)` block that calls `savedStateHandle.get<Int>("suggestedWateringInterval")` and immediately removes it after reading to prevent re-showing the Snackbar on recomposition.

`PlantDetailViewModel.suggestedWateringInterval` is a `MutableStateFlow<Int?>` that drives the Snackbar visibility.

See `NavGraph.kt` (lines around the `addCareLog` composable) and `PlantDetailViewModel.kt`.

## Consequences

- The value survives process death because `savedStateHandle` is backed by the saved state registry.
- The value is consumed exactly once: reading and removing it in the same `LaunchedEffect` prevents re-display on config change or recomposition.
- The suggestion is cleared when navigating away from PlantDetail (`suggestedWateringInterval.value = null` in `PlantDetailViewModel`), so stale suggestions don't appear if the user returns later.
- This is the pattern recommended by the Navigation component docs for returning results from a destination; it is not a workaround.
