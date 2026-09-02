package com.yapt.planttracker.ui.screens.plantdetail

import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.domain.featureflag.FeatureFlagRegistry
import com.yapt.planttracker.domain.featureflag.FeatureFlags
import com.yapt.planttracker.domain.model.WateringAdjustment
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.ui.components.TimeRange
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

fun PlantDetailViewModel.clearSuggestedInterval() {
suggestedWateringInterval.value = null
}

fun PlantDetailViewModel.dismissSuggestedInterval() {
viewModelScope.launch {
    if (isAdaptiveWateringEnabled()) {
        plant.value?.let { p ->
            plantRepository.updatePlant(
                p.copy(
                    wateringConfidence = CareSchedule.confidenceAfterDismissal(p.wateringConfidence),
                    updatedAt = System.currentTimeMillis()
                )
            )
            p.wateringIntervalDays?.let { current ->
                val currentBase = currentBaseIntervalDaysOrLiteral(p, current)
                wateringAdjustmentRepository.addAdjustment(
                    WateringAdjustment(
                        plantId = p.id,
                        trigger = WateringAdjustmentTrigger.DIALOG_DISMISSAL,
                        beforeIntervalDays = currentBase,
                        afterIntervalDays = currentBase
                    )
                )
            }
        }
    }
    suggestedWateringInterval.value = null
}
}

internal suspend fun PlantDetailViewModel.isAdaptiveWateringEnabled(): Boolean =
dataStore.data.first()[FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.ADAPTIVE_WATERING)]
    ?: FeatureFlagRegistry.ADAPTIVE_WATERING.default

private suspend fun PlantDetailViewModel.shouldShowIntervalDialog(): Boolean {
if (!isAdaptiveWateringEnabled()) return true
return dataStore.data.first()[SettingsKeys.ASK_BEFORE_CHANGING_INTERVALS] ?: true
}

internal suspend fun PlantDetailViewModel.applySuggestionOrPrompt(suggestedInterval: Int) {
if (shouldShowIntervalDialog()) {
    suggestedWateringInterval.value = suggestedInterval
    return
}
val p = plant.value ?: return
val result = quickLogUseCase.applyWateringIntervalSuggestion(p, suggestedInterval, suggestedInterval)
_events.emit(
    PlantDetailViewModel.Event.SilentIntervalApplied(
        beforeIntervalDays = result.previousEffectiveIntervalDays,
        beforeBaseIntervalDays = result.previousBaseIntervalDays,
        afterIntervalDays = result.newEffectiveIntervalDays
    )
)
}

fun PlantDetailViewModel.handleSuggestedWateringInterval(suggestedInterval: Int) {
viewModelScope.launch { applySuggestionOrPrompt(suggestedInterval) }
}

internal fun PlantDetailViewModel.setTimeRange(range: TimeRange) {
selectedTimeRange.value = range
}

fun PlantDetailViewModel.applySuggestedInterval(newInterval: Int) {
viewModelScope.launch {
    plant.value?.let {
        quickLogUseCase.applyWateringIntervalSuggestion(
            it,
            suggestedWateringInterval.value,
            newInterval
        )
    }
    suggestedWateringInterval.value = null
    _events.emit(PlantDetailViewModel.Event.IntervalUpdated)
}
}

fun PlantDetailViewModel.undoSilentIntervalApply(beforeIntervalDays: Int, beforeBaseIntervalDays: Double?) {
viewModelScope.launch {
    plant.value?.let { p ->
        val silentlyAppliedInterval = p.wateringIntervalDays ?: beforeIntervalDays
        val now = System.currentTimeMillis()
        plantRepository.updatePlant(
            p.copy(
                wateringIntervalDays = beforeIntervalDays,
                wateringBaseIntervalDays = beforeBaseIntervalDays,
                updatedAt = now
            )
        )
        if (isAdaptiveWateringEnabled()) {
            wateringAdjustmentRepository.addAdjustment(
                WateringAdjustment(
                    plantId = p.id,
                    triggeredAt = now,
                    trigger = WateringAdjustmentTrigger.SILENT_APPLY_UNDONE,
                    beforeIntervalDays = silentlyAppliedInterval,
                    afterIntervalDays = beforeIntervalDays
                )
            )
        }
    }
}
}
