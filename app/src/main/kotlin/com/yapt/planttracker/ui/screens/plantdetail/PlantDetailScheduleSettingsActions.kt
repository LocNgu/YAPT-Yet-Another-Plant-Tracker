package com.yapt.planttracker.ui.screens.plantdetail

import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringAdjustment
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.domain.schedule.seasonalAmplitudeOnce
import com.yapt.planttracker.util.toLocalDate
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

fun PlantDetailViewModel.setWateringInterval(days: Int?) {
viewModelScope.launch {
    plant.value?.let { p ->
        val deseasonalizedDays = if (days != null && !p.pinIntervalToBase) deseasonalizedBaseOrNull(days) else null
        val base = if (days != null && !p.pinIntervalToBase) deseasonalizedDays ?: p.wateringBaseIntervalDays else p.wateringBaseIntervalDays
        val now = System.currentTimeMillis()
        plantRepository.updatePlant(
            p.copy(wateringIntervalDays = days, wateringBaseIntervalDays = base, updatedAt = now)
        )
        if (days != null && days != p.wateringIntervalDays && isAdaptiveWateringEnabled()) {
            val after = if (!p.pinIntervalToBase) (deseasonalizedDays ?: days.toDouble()).roundToInt() else days
            wateringAdjustmentRepository.addAdjustment(
                WateringAdjustment(
                    plantId = p.id,
                    triggeredAt = now,
                    trigger = WateringAdjustmentTrigger.MANUAL_EDIT,
                    beforeIntervalDays = currentBaseIntervalDaysOrLiteral(p, p.wateringIntervalDays ?: days),
                    afterIntervalDays = after
                )
            )
        }
    }
}
}

fun PlantDetailViewModel.setPinIntervalToBase(pinned: Boolean) {
viewModelScope.launch {
    plant.value?.let {
        plantRepository.updatePlant(
            it.copy(pinIntervalToBase = pinned, updatedAt = System.currentTimeMillis())
        )
    }
}
}

private suspend fun PlantDetailViewModel.deseasonalizedBaseOrNull(intervalDays: Int): Double? {
val amplitude = dataStore.seasonalAmplitudeOnce()
if (amplitude == 0.0) return null
return SeasonalWatering.deseasonalize(
    intervalDays.toDouble(),
    System.currentTimeMillis().toLocalDate(),
    amplitude,
    SeasonalWatering.currentHemisphere()
)
}

internal suspend fun PlantDetailViewModel.currentBaseIntervalDaysOrLiteral(plant: Plant, literal: Int): Int {
if (plant.pinIntervalToBase) return literal
if (dataStore.seasonalAmplitudeOnce() == 0.0) return literal
return (plant.wateringBaseIntervalDays ?: literal.toDouble()).roundToInt()
}

fun PlantDetailViewModel.setFertilizingInterval(days: Int?) {
viewModelScope.launch {
    plant.value?.let {
        plantRepository.updatePlant(
            it.copy(fertilizingIntervalDays = days, updatedAt = System.currentTimeMillis())
        )
    }
}
}

fun PlantDetailViewModel.setLiquidFertilizer(enabled: Boolean) {
viewModelScope.launch {
    plant.value?.let {
        plantRepository.updatePlant(
            it.copy(useLiquidFertilizer = enabled, updatedAt = System.currentTimeMillis())
        )
    }
}
}
