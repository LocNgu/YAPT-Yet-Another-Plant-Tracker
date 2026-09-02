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

/**
 * Inline scheduling edits from the Plant Detail tabs (#436, product ADR-0022). Each persists a
 * single field straight through `PlantRepository.updatePlant`; the change flows back via the
 * `plant` StateFlow so the tab insights update immediately. A `null` interval clears the schedule
 * (the "Not scheduled" state), matching the reminder toggle on Add/Edit Plant.
 */
fun PlantDetailViewModel.setWateringInterval(days: Int?) {
    viewModelScope.launch {
        plant.value?.let { p ->
            // De-seasonalize the newly set value to today (#569), mirroring AddEditPlant's
            // manual-edit handling — unchanged when SEASONAL_WATERING is off, the plant is
            // pinned, or the schedule was just switched off (`days == null`); the prior base
            // (if any) is preserved rather than cleared.
            val deseasonalizedDays = if (days != null && !p.pinIntervalToBase) {
                deseasonalizedBaseOrNull(days)
            } else {
                null
            }
            val wateringBaseIntervalDays = if (days != null && !p.pinIntervalToBase) {
                deseasonalizedDays ?: p.wateringBaseIntervalDays
            } else {
                p.wateringBaseIntervalDays
            }
            val now = System.currentTimeMillis()
            plantRepository.updatePlant(
                p.copy(
                    wateringIntervalDays = days,
                    wateringBaseIntervalDays = wateringBaseIntervalDays,
                    updatedAt = now
                )
            )
            if (days != null && days != p.wateringIntervalDays && isAdaptiveWateringEnabled()) {
                // #584 review: log the base-space before/after, not the literal typed value. This
                // deliberately does *not* reuse `wateringBaseIntervalDays` above for "after" — that
                // preserves a stale prior base when season is off, whereas the log's "after" must
                // collapse to the literal `days` in that case (mirrors the "before" side's collapse).
                val loggedAfter = if (!p.pinIntervalToBase) {
                    (deseasonalizedDays ?: days.toDouble()).roundToInt()
                } else {
                    days
                }
                wateringAdjustmentRepository.addAdjustment(
                    WateringAdjustment(
                        plantId = p.id,
                        triggeredAt = now,
                        trigger = WateringAdjustmentTrigger.MANUAL_EDIT,
                        beforeIntervalDays = currentBaseIntervalDaysOrLiteral(p, p.wateringIntervalDays ?: days),
                        afterIntervalDays = loggedAfter
                    )
                )
            }
        }
    }
}

/** "Pin interval" switch on the inline Water tab settings card (#569) — see `seasonalWateringEnabled`. */
fun PlantDetailViewModel.setPinIntervalToBase(pinned: Boolean) {
    viewModelScope.launch {
        plant.value?.let {
            plantRepository.updatePlant(it.copy(pinIntervalToBase = pinned, updatedAt = System.currentTimeMillis()))
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

/**
 * [plant]'s current base-space reference for [WateringAdjustment] row units (#584 review) —
 * mirrors `QuickLogUseCase`'s `currentAdaptiveBaseIntervalDays()` fallback. Collapses to [literal]
 * itself when the plant is pinned or SEASONAL_WATERING is off, matching every other read of
 * `Plant.wateringBaseIntervalDays`.
 */
@Suppress("ReturnCount")
internal suspend fun PlantDetailViewModel.currentBaseIntervalDaysOrLiteral(plant: Plant, literal: Int): Int {
    if (plant.pinIntervalToBase) return literal
    val amplitude = dataStore.seasonalAmplitudeOnce()
    if (amplitude == 0.0) return literal
    return (plant.wateringBaseIntervalDays ?: literal.toDouble()).roundToInt()
}

fun PlantDetailViewModel.setFertilizingInterval(days: Int?) {
    viewModelScope.launch {
        plant.value?.let { p ->
            plantRepository.updatePlant(
                p.copy(fertilizingIntervalDays = days, updatedAt = System.currentTimeMillis())
            )
        }
    }
}

fun PlantDetailViewModel.setLiquidFertilizer(enabled: Boolean) {
    viewModelScope.launch {
        plant.value?.let { p ->
            plantRepository.updatePlant(
                p.copy(useLiquidFertilizer = enabled, updatedAt = System.currentTimeMillis())
            )
        }
    }
}
