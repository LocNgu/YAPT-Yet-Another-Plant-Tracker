# Technical ADR-0044: Add/Edit Plant form state is one StateFlow persisted to SavedStateHandle; typed text stays synchronous

**Status**: accepted

**Date**: 2026-11-12

## Context

`AddEditPlantViewModel` held ~21 `mutableStateOf` fields written directly by the screen and by tests, and
nothing survived process death. The wizard (product ADR-0085) requires the step and every input to survive it.
Multi-field writes (dormancy window, Propagate prefill, edit load) were sequences of independent writes, and the
pin/strength mutual exclusion lived in property setters. Feeding a `TextField` from an asynchronously collected
`StateFlow` can drop keystrokes or move the cursor (the `PlantListViewModel.searchQueryText` precedent).

## Decision

- **One immutable `@Serializable AddEditPlantFormState`** holds every non-text input (toggles, intervals, season
  sets, strength, dormancy, photos, wizard step) plus restore bookkeeping (`editLoaded`, `prefillDone`,
  `linkedParentId`, `propagatedFromName`, `seededWateringIntervalDays`, `loadedAmplitudeOverride`). The ViewModel
  exposes it as `uiState: StateFlow` and changes it only through `setX()` / `toggleX()` methods built on
  `MutableStateFlow.updateAndGet`, so multi-field writes and the guarded Propagate prefill are atomic against
  user edits and flip logic reads the latest state.
- **Typed text (name, species, room, notes) is the documented exception:** private `mutableStateOf` with a public
  read-only getter and `setName()`-style setters, so the TextField stays synchronous.
- **Persistence:** every update writes the state as a JSON string (kotlinx.serialization; enums by name) to the
  `SavedStateHandle` key `add_edit_plant_form`; each text setter writes its own key. The `Factory` builds the
  handle with `extras.createSavedStateHandle()`; the constructor parameter defaults to a fresh handle so tests need
  no changes. An undecodable saved form falls back to the defaults.
- **Restore wins:** `editLoaded` makes the edit load skip every form field of a restored draft (it still refreshes
  display-only `isHidden`/`seasonalAdjustment`); `prefillDone` makes the Propagate prefill not re-apply. If the
  process died before either finished, the load or prefill runs with its untouched-default guards.

## Consequences

- Screens and tests write through setters, never fields; reads use `uiState.value`.
- A new form field must be added to `AddEditPlantFormState` (serializable, with a default so older saved drafts
  decode) and, for a `Plant` column, carried over in `saveEdit()`.
- The JSON write runs on every slider step; the payload is small, so no debounce.
- Product ADR-0070's seed (`seededWateringIntervalDays`) is part of the state, so a restored edit keeps comparing
  against the originally seeded value.
