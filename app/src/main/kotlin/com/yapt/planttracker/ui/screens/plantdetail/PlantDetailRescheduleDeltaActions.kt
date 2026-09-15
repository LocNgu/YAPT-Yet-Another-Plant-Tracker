package com.yapt.planttracker.ui.screens.plantdetail

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/**
 * The "Rescheduled +N days" chip's tap-to-revert action (#630) — clears
 * `Plant.wateringDueDateOverride`, restoring the schedule-computed due date immediately. A plain
 * override-only write, same posture `applyReschedule` (`PlantDetailRescheduleActions.kt`) already
 * keeps for "I can't right now" (ADR-0029/ADR-0030): never touches
 * `wateringIntervalDays`/`wateringBaseIntervalDays`/`wateringConfidence`, never a
 * `WateringAdjustment` row. No confirmation dialog per spec — the Snackbar/Undo pair is the only
 * safety net, mirroring `applySuggestionOrPrompt`'s silent-apply flow. `Event.RescheduleReverted`
 * carries the plant's actual prior override value, captured once here and threaded straight through
 * for [undoRevertReschedule] to restore as-is.
 *
 * Split into its own file (#719 review) purely to keep `PlantDetailRescheduleActions.kt` under
 * Detekt's `TooManyFunctions` file threshold once that file gained a fifth date option
 * (`confirmRescheduleSuggestedDays`) — no behaviour change, and these two functions were already
 * documented as the distinct #630 "delta chip + revert" feature in `.claude/rules/plant-detail.md`.
 */
fun PlantDetailViewModel.revertReschedule() {
    viewModelScope.launch {
        val p = plant.value ?: return@launch
        val previousOverride = p.wateringDueDateOverride ?: return@launch
        plantRepository.updatePlant(p.copy(wateringDueDateOverride = null, updatedAt = System.currentTimeMillis()))
        emitEvent(PlantDetailViewModel.Event.RescheduleReverted(previousOverride))
    }
}

/**
 * Undo action for the `Event.RescheduleReverted` Snackbar (#630) — restores
 * `Plant.wateringDueDateOverride` to [previousOverrideAtMillis] as-is, no recomputation, mirroring
 * `undoSilentIntervalApply`'s posture. If a newer reschedule was written in the meantime, this
 * silently overwrites it with the stale captured value (documented, not solved — same accepted
 * race as the existing interval-undo Snackbar).
 */
fun PlantDetailViewModel.undoRevertReschedule(previousOverrideAtMillis: Long) {
    viewModelScope.launch {
        plant.value?.let {
            plantRepository.updatePlant(
                it.copy(wateringDueDateOverride = previousOverrideAtMillis, updatedAt = System.currentTimeMillis())
            )
        }
    }
}
