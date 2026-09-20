package com.yapt.planttracker.ui.screens.plantdetail

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Opens [PlantDetailViewModel.showRescheduleDialog] directly (#738, product ADR-0039) — a
 * reschedule is model-neutral, so there is no reason prompt to answer first.
 */
fun PlantDetailViewModel.requestReschedule() {
    showRescheduleDialog.value = true
}

fun PlantDetailViewModel.dismissRescheduleDialog() {
    showRescheduleDialog.value = false
}

/**
 * Reschedule "Today" option (#508, product ADR-0029) — only ever tapped from an enabled state,
 * since the screen disables it while [isRescheduleTodayEnabled] (#746, replacing the earlier
 * `PlantCareStatus.isOverdue`-based gate) says tapping it would be a no-op. See [applyReschedule].
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
 * The one place a reschedule is committed, whichever date option was tapped. Delegates to
 * [com.yapt.planttracker.domain.usecase.QuickLogUseCase.recordReschedule], which writes
 * [com.yapt.planttracker.domain.model.Plant.wateringDueDateOverride] only — never
 * `wateringIntervalDays`/`wateringBaseIntervalDays`/`wateringConfidence`, and never a
 * `WateringAdjustment` row (#738, product ADR-0039 — a reschedule is model-neutral again; all
 * learning comes from the next actual watering). Never fires the product ADR-0006 interval-suggestion
 * dialog.
 */
private fun PlantDetailViewModel.applyReschedule(newDueAtMillis: Long) {
    viewModelScope.launch {
        dismissRescheduleDialog()
        val p = plant.value ?: return@launch
        quickLogUseCase.recordReschedule(p, newDueAtMillis)
    }
}
