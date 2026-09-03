package com.yapt.planttracker.ui.screens.plantdetail

import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.domain.model.RescheduleReason
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Opens the #586 reason prompt; the date dialog only follows once a reason is chosen. */
fun PlantDetailViewModel.requestReschedule() {
    showRescheduleReasonSheet.value = true
}

/**
 * Dismissing the reason prompt abandons the whole reschedule — no override write, no log, no
 * model effect. "Records no signal" is satisfied here by recording nothing at all (#586).
 */
fun PlantDetailViewModel.dismissRescheduleReasonSheet() {
    showRescheduleReasonSheet.value = false
    rescheduleReason.value = null
    rescheduleSuggestedDays.value = null
}

/**
 * Answer to the #586 reason prompt. For [RescheduleReason.SOIL_STILL_MOIST] the date picker opens
 * on a deferral derived from the interval the model lands on after that observation, rather than
 * on today or #570's flat +1 day; for [RescheduleReason.CANT_RIGHT_NOW] there is nothing to
 * suggest, because nothing about the plant was observed.
 */
fun PlantDetailViewModel.chooseRescheduleReason(reason: RescheduleReason) {
    viewModelScope.launch {
        rescheduleReason.value = reason
        rescheduleSuggestedDays.value = plant.value
            ?.takeIf { reason == RescheduleReason.SOIL_STILL_MOIST }
            ?.let { quickLogUseCase.suggestedStillMoistDeferralDays(it) }
        showRescheduleReasonSheet.value = false
        showRescheduleDialog.value = true
    }
}

fun PlantDetailViewModel.dismissRescheduleDialog() {
    showRescheduleDialog.value = false
    rescheduleReason.value = null
    rescheduleSuggestedDays.value = null
}

/**
 * Reschedule "Today" option (#508, product ADR-0029) — only ever tapped from an enabled state,
 * since the screen disables it while `PlantCareStatus.isDueSoon` (already due today, a true
 * no-op there), and also while the reason is "Soil still moist", where pulling the date forward
 * would contradict what the user just said. See [applyReschedule].
 */
fun PlantDetailViewModel.confirmRescheduleToday() = applyReschedule(System.currentTimeMillis())

/**
 * Reschedule "+[days]" option (#508, product ADR-0029) — anchored to the current *effective* due
 * date (`maxOf(nextWateringDueAt, now)`, already override-aware via `CareSchedule`), unchanged
 * from the stepper dialog this replaces. [days] never affects what the model learns (#586).
 */
fun PlantDetailViewModel.confirmRescheduleRelativeDays(days: Int) {
    val currentDue = maxOf(careStatus.value?.nextWateringDueAt ?: 0L, System.currentTimeMillis())
    applyReschedule(currentDue + TimeUnit.DAYS.toMillis(days.toLong()))
}

/**
 * Reschedule "Custom date…" option (#508, product ADR-0029) — [newDueAtMillis] is the user-picked
 * date at local start-of-day; the `DatePicker` itself excludes past dates via `SelectableDates`,
 * so no further validation happens here.
 */
fun PlantDetailViewModel.confirmRescheduleCustomDate(newDueAtMillis: Long) = applyReschedule(newDueAtMillis)

/**
 * The one place a reschedule is committed, whichever date option was tapped. What the answer to
 * the #586 reason prompt decides — never the length of the deferral:
 *
 * - **"Soil still moist"** routes through the same `QuickLogUseCase.recordStillMoistCheck` the
 *   notification's Still-moist action calls, so the two paths produce identical `CareType.CHECK`
 *   logs and model effects by construction rather than by two implementations happening to agree.
 * - **"I can't right now"** writes `Plant.wateringDueDateOverride` only — never
 *   `wateringIntervalDays`/`wateringBaseIntervalDays`/`wateringConfidence`, and never a
 *   `WateringAdjustment` row. That is ADR-0029's posture for *every* reschedule, preserved here
 *   for the half of them that really is about the user's availability.
 *
 * Neither path ever fires the ADR-0006 interval-suggestion dialog.
 */
private fun PlantDetailViewModel.applyReschedule(newDueAtMillis: Long) {
    viewModelScope.launch {
        val reason = rescheduleReason.value
        dismissRescheduleDialog()
        val p = plant.value ?: return@launch
        if (reason == RescheduleReason.SOIL_STILL_MOIST) {
            val logged = quickLogUseCase.recordStillMoistCheck(p, newDueAtMillis)
            emitQuickLogMessage(
                if (logged) {
                    PlantDetailViewModel.QuickLogMessage.StillMoistChecked(p.name)
                } else {
                    PlantDetailViewModel.QuickLogMessage.AlreadyCheckedToday(p.name)
                }
            )
        } else {
            plantRepository.updatePlant(
                p.copy(wateringDueDateOverride = newDueAtMillis, updatedAt = System.currentTimeMillis())
            )
        }
    }
}

/**
 * The "Rescheduled +N days" chip's tap-to-revert action (#630) — clears
 * `Plant.wateringDueDateOverride`, restoring the schedule-computed due date immediately. A plain
 * override-only write, same posture [applyReschedule] already keeps for "I can't right now"
 * (ADR-0029/ADR-0030): never touches `wateringIntervalDays`/`wateringBaseIntervalDays`/
 * `wateringConfidence`, never a `WateringAdjustment` row. No confirmation dialog per spec — the
 * Snackbar/Undo pair is the only safety net, mirroring `applySuggestionOrPrompt`'s silent-apply
 * flow. `Event.RescheduleReverted` carries the plant's actual prior override value, captured once
 * here and threaded straight through for [undoRevertReschedule] to restore as-is.
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
