package com.yapt.planttracker.ui.screens.plantdetail

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * The "Rescheduled +N days" chip's tap-to-revert action (#630) — clears
 * `Plant.wateringDueDateOverride`, restoring the schedule-computed due date immediately. A plain
 * override-only write, same posture `applyReschedule` (`PlantDetailRescheduleActions.kt`) already
 * keeps for "I can't right now" (product ADR-0029 and product ADR-0030): never touches
 * `wateringIntervalDays`/`wateringBaseIntervalDays`/`wateringConfidence`, never a
 * `WateringAdjustment` row. No confirmation dialog per spec — the Snackbar/Undo pair is the only
 * safety net, mirroring `applySuggestionOrPrompt`'s silent-apply flow. `Event.RescheduleReverted`
 * carries the plant's actual prior override value, captured once here from a fresh read (never the
 * cached `plant` StateFlow, which can lag a write still in flight) and threaded straight through for
 * [undoRevertReschedule] to restore as-is. Writes through the column-specific
 * [com.yapt.planttracker.data.repository.PlantRepository.updateWateringDueDateOverride] inside
 * [PlantDetailViewModel.plantEditMutex] (#808, technical ADR-0036): the statement can't revert any
 * other column, and the lock stops a full-row writer that already read the row from putting the old
 * override back.
 *
 * Split into its own file (#719 review) purely to keep `PlantDetailRescheduleActions.kt` under
 * Detekt's `TooManyFunctions` file threshold — no behaviour change, and these two functions were
 * already documented as the distinct #630 "delta chip + revert" feature in
 * `.claude/rules/plant-detail.md`.
 */
fun PlantDetailViewModel.revertReschedule() {
    viewModelScope.launch {
        val previousOverride = plantEditMutex.withLock {
            val previous = plantRepository.getPlantById(plantId).first()?.wateringDueDateOverride
                ?: return@withLock null
            plantRepository.updateWateringDueDateOverride(plantId, null, System.currentTimeMillis())
            previous
        } ?: return@launch
        emitEvent(PlantDetailViewModel.Event.RescheduleReverted(previousOverride))
    }
}

/**
 * Undo action for the `Event.RescheduleReverted` Snackbar (#630) — restores
 * `Plant.wateringDueDateOverride` to [previousOverrideAtMillis] as-is, no recomputation, mirroring
 * `undoSilentIntervalApply`'s posture. If a newer reschedule was written in the meantime, this
 * silently overwrites it with the stale captured value (documented, not solved — same accepted
 * race as the existing interval-undo Snackbar). It never reverts any *other* column (#808): a
 * column-specific write under [PlantDetailViewModel.plantEditMutex], like [revertReschedule].
 */
fun PlantDetailViewModel.undoRevertReschedule(previousOverrideAtMillis: Long) {
    viewModelScope.launch {
        plantEditMutex.withLock {
            plantRepository.updateWateringDueDateOverride(
                plantId,
                previousOverrideAtMillis,
                System.currentTimeMillis()
            )
        }
    }
}
