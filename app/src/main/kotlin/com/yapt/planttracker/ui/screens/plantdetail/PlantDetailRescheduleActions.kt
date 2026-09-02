package com.yapt.planttracker.ui.screens.plantdetail

import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.domain.model.RescheduleReason
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

fun PlantDetailViewModel.requestReschedule() { showRescheduleReasonSheet.value = true }

fun PlantDetailViewModel.dismissRescheduleReasonSheet() {
showRescheduleReasonSheet.value = false
rescheduleReason.value = null
rescheduleSuggestedDays.value = null
}

fun PlantDetailViewModel.chooseRescheduleReason(reason: RescheduleReason) {
viewModelScope.launch {
    rescheduleReason.value = reason
    rescheduleSuggestedDays.value = plant.value?.takeIf {
        reason == RescheduleReason.SOIL_STILL_MOIST
    }?.let { quickLogUseCase.suggestedStillMoistDeferralDays(it) }
    showRescheduleReasonSheet.value = false
    showRescheduleDialog.value = true
}
}

fun PlantDetailViewModel.dismissRescheduleDialog() {
showRescheduleDialog.value = false
rescheduleReason.value = null
rescheduleSuggestedDays.value = null
}

fun PlantDetailViewModel.confirmRescheduleToday() = applyReschedule(System.currentTimeMillis())

fun PlantDetailViewModel.confirmRescheduleRelativeDays(days: Int) {
applyReschedule(
    maxOf(careStatus.value?.nextWateringDueAt ?: 0L, System.currentTimeMillis()) + TimeUnit.DAYS.toMillis(days.toLong())
)
}

fun PlantDetailViewModel.confirmRescheduleCustomDate(newDueAtMillis: Long) = applyReschedule(newDueAtMillis)

private fun PlantDetailViewModel.applyReschedule(newDueAtMillis: Long) {
viewModelScope.launch {
    val reason = rescheduleReason.value
    dismissRescheduleDialog()
    val p = plant.value ?: return@launch
    if (reason == RescheduleReason.SOIL_STILL_MOIST) {
        val logged = quickLogUseCase.recordStillMoistCheck(p, newDueAtMillis)
        _quickLogMessage.emit(
            if (logged) {
                PlantDetailViewModel.QuickLogMessage.StillMoistChecked(
                    p.name
                )
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

fun PlantDetailViewModel.revertReschedule() {
viewModelScope.launch {
    val p = plant.value ?: return@launch
    val previousOverride = p.wateringDueDateOverride ?: return@launch
    plantRepository.updatePlant(p.copy(wateringDueDateOverride = null, updatedAt = System.currentTimeMillis()))
    _events.emit(PlantDetailViewModel.Event.RescheduleReverted(previousOverride))
}
}

fun PlantDetailViewModel.undoRevertReschedule(previousOverrideAtMillis: Long) {
viewModelScope.launch {
    plant.value?.let {
        plantRepository.updatePlant(
            it.copy(wateringDueDateOverride = previousOverrideAtMillis, updatedAt = System.currentTimeMillis())
        )
    }
}
}
