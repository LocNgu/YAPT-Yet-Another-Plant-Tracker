---
description: Add/Edit Plant form — four-step Add wizard, form state, SavedStateHandle restore
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/ui/screens/addplant/**/*"
  - "app/src/test/kotlin/com/yapt/planttracker/ui/screens/addplant/**/*"
  - "app/src/androidTest/kotlin/com/yapt/planttracker/ui/screens/addplant/**/*"
---

# Add/Edit Plant (#943, product ADR-0086, technical ADR-0044)

## Shape
- `AddEditPlantScreen` serves three routes: **Add** and **Add-from-Propagate** (`propagatedFromId`, product ADR-0073) run the **four-step wizard**; **Edit** is the single scrolling form with the Save FAB, Delete and Hide/Unhide. `isEditMode` picks the layout.
- **Steps** (`AddPlantStep`: DETAILS, PHOTO, CARE, REVIEW; the wizard starts on DETAILS): name + species + location (name required, checked when leaving it); photo; care; review (notes, read-only summary lines that jump back, **Add plant**).
- **Care step** (`WizardCareSections`): watering (no strength control) and fertilizing (switch, slider, seasons, liquid) are visible; a collapsed **More options** header (clickable, `stateDescription` Expanded/Collapsed) holds dormancy, `WateringStrengthSection` (strength/pin) and repotting. Edit still uses `CareSections` with strength inside `WateringSection` (`showStrength = true`), unchanged.
- **More options state** is `AddEditPlantFormState.moreOptionsExpanded` (saved with the draft; not counted as unsaved input), set by `setMoreOptionsExpanded()`. Default collapsed; `withParentCarePrefill` sets it only when the copy changed a More-options value, and `prefillDone` stops that re-applying after restore. Collapsing never discards values. Review shows one line per non-default More-options setting (`dormancyChosen`, `strengthChosen`, repotting enabled/seasons); a line calls `goToMoreOptions()` (CARE + expanded).
- **One save path:** Add plant calls `save()` → `saveNew()`. Nothing is written earlier. A blank name in `save()` also jumps to DETAILS. `saveNew()`/`saveEdit()` are unchanged by the wizard.
- **Sections are shared** (`AddEditPlantSections.kt`: `WateringSection`, `FertilizingSection`, `RepottingSection`, `PlantIdentityFields`, …) between wizard and Edit; wizard chrome is in `AddPlantWizard.kt`. Edit mode seeds the watering slider with today's live value (product ADR-0070); the wizard has no seed.
- **Help text (#1039, product ADR-0087):** each step shows a plain-text `StepIntro` under the title (not a live region). Five info icons (Fertilizing "Active in" seasons; Seasonal strength, which also explains Pin; Dormancy; Repotting reminder; Prefer repotting in) carry an `InfoTooltip` (`ui/components/`) via opt-in params (`showInfo` on `WateringStrengthSetting`/`DormancyWindowSetting`, `SeasonsLabel.trailing` on `FertilizingSeasonsSelector`); Plant Detail leaves them off. Keep the icon outside any clickable/toggle row.

## Growing conditions (#1003)
- `GrowingConditionsSection` (light `FilterChip`s, pot material chips, pot size text field; shared `ui/components/GrowingConditionsFields.kt`, `yaptFilterChipColors()`) sits under `PlantIdentityFields` on the wizard's DETAILS step and in Edit. A tap on the selected chip clears it. All three are optional and display-only (never feed `CareSchedule`).
- Form state: `lightLevel`, `potMaterial`, `potDiameterText` (digits only, max 3). `PotDiameter` (`domain/model/GrowingConditions.kt`) is the single 1-200 rule: an invalid size blocks `goNext()` on DETAILS and `save()` (which jumps the wizard back to DETAILS) with `growing_conditions_pot_size_error`. `persist()` builds the plant from the form, so Edit loads the stored values via `withPlantLoaded()` and an unrelated edit keeps them. Tests: `AddEditPlantViewModelGrowingConditionsTest`.

## Navigation and Back
- `goNext()` (DETAILS with a blank name emits `Event.ValidationError(R.string.add_plant_name_required)` and stays), `goBack(): Boolean` (false on the DETAILS step and always in edit mode), `goToStep()`, `goToMoreOptions()`, `hasUnsavedInput`.
- Top-bar arrow (`cd_back`) and system Back (`BackHandler`, wizard only) both go back one step. On DETAILS: leave at once if `!hasUnsavedInput`, else the discard dialog. A Propagate prefill counts as input. Never delete a photo file on discard (`OrphanPhotoSweeper`, `rules/photos.md`).
- "Step N of 4" is a polite live region; the progress bar is separate.

## Form state (technical ADR-0044)
- Non-text inputs live in the immutable `@Serializable AddEditPlantFormState`, exposed as `uiState: StateFlow`; change it only via `setX()`/`toggleX()` (atomic `updateAndGet`). Screens read it with `collectAsStateWithLifecycle()`; tests read `vm.uiState.value`.
- Name, species, room, notes are **synchronous Compose state** (private backing + getter, `setName()`…), the documented exception to "StateFlow for UI state".
- Every update mirrors the state as JSON into the `SavedStateHandle` (`Factory.create(modelClass, extras)` uses `createSavedStateHandle()`); the constructor default keeps tests handle-free. **Restore wins:** `editLoaded` stops the edit load overwriting a restored draft; `prefillDone` stops the Propagate prefill re-applying. Only untouched defaults are prefilled, inside one atomic update.
- New field → add it to `AddEditPlantFormState` with a default; a `Plant` column also needs the `saveEdit()` carry-over.

## Tests
- ViewModel: `AddEditPlantViewModelWizardTest` (steps, validation, back/discard, restore, Propagate across steps, More options collapse/auto-expand/restore). Compose: `AddPlantWizardScreenTest` (step progress, Back/discard, 20+ characters via `performTextInput` into name and notes, summary jump, More options collapse and Review lines, Add plant) — semantics only (#420). Add-mode tests in `AddEditPlantScreenTest` reach a step with `viewModel.goToStep(...)`; ones touching dormancy or repotting expand More options first.
