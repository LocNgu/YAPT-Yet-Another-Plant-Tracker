---
description: Add/Edit Plant form — four-step Add wizard, form state, SavedStateHandle restore
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/ui/screens/addplant/**/*"
  - "app/src/test/kotlin/com/yapt/planttracker/ui/screens/addplant/**/*"
  - "app/src/androidTest/kotlin/com/yapt/planttracker/ui/screens/addplant/**/*"
---

# Add/Edit Plant (#943, product ADR-0085, technical ADR-0044)

## Shape
- `AddEditPlantScreen` serves three routes: **Add** and **Add-from-Propagate** (`propagatedFromId`, product ADR-0073) run the **four-step wizard**; **Edit** is the single scrolling form with the Save FAB, Delete and Hide/Unhide. `isEditMode` picks the layout.
- **Steps** (`AddPlantStep`: PHOTO, DETAILS, CARE, REVIEW): photo; name + species + location (name required); care (watering + strength + dormancy, fertilizing + liquid + seasons, repotting + seasons); review (notes, read-only summary lines that jump back, **Add plant**).
- **One save path:** Add plant calls `save()` → `saveNew()`. Nothing is written earlier. A blank name in `save()` also jumps to DETAILS. `saveNew()`/`saveEdit()` are unchanged by the wizard.
- **Sections are shared** (`AddEditPlantSections.kt`: `WateringSection`, `FertilizingSection`, `RepottingSection`, `PlantIdentityFields`, …) between wizard and Edit; wizard chrome is in `AddPlantWizard.kt`. Edit mode seeds the watering slider with today's live value (product ADR-0070); the wizard has no seed.

## Navigation and Back
- `goNext()` (DETAILS with a blank name emits `Event.ValidationError(R.string.add_plant_name_required)` and stays), `goBack(): Boolean` (false on step 1 and always in edit mode), `goToStep()`, `hasUnsavedInput`.
- Top-bar arrow (`cd_back`) and system Back (`BackHandler`, wizard only) both go back one step. On step 1: leave at once if `!hasUnsavedInput`, else the discard dialog. A Propagate prefill counts as input. Never delete a photo file on discard (`OrphanPhotoSweeper`, `rules/photos.md`).
- "Step N of 4" is a polite live region; the progress bar is separate.

## Form state (technical ADR-0044)
- Non-text inputs live in the immutable `@Serializable AddEditPlantFormState`, exposed as `uiState: StateFlow`; change it only via `setX()`/`toggleX()` (atomic `updateAndGet`). Screens read it with `collectAsStateWithLifecycle()`; tests read `vm.uiState.value`.
- Name, species, room, notes are **synchronous Compose state** (private backing + getter, `setName()`…), the documented exception to "StateFlow for UI state".
- Every update mirrors the state as JSON into the `SavedStateHandle` (`Factory.create(modelClass, extras)` uses `createSavedStateHandle()`); the constructor default keeps tests handle-free. **Restore wins:** `editLoaded` stops the edit load overwriting a restored draft; `prefillDone` stops the Propagate prefill re-applying. Only untouched defaults are prefilled, inside one atomic update.
- New field → add it to `AddEditPlantFormState` with a default; a `Plant` column also needs the `saveEdit()` carry-over.

## Tests
- ViewModel: `AddEditPlantViewModelWizardTest` (steps, validation, back/discard, restore, Propagate across steps). Compose: `AddPlantWizardScreenTest` (step progress, Back/discard, 20+ characters via `performTextInput` into name and notes, summary jump, Add plant) — semantics only (#420). Add-mode tests in `AddEditPlantScreenTest` reach a step with `viewModel.goToStep(...)`.
