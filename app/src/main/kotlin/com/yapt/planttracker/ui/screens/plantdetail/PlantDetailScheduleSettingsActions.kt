package com.yapt.planttracker.ui.screens.plantdetail

import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringAdjustment
import com.yapt.planttracker.domain.schedule.DormancyWindow
import com.yapt.planttracker.domain.schedule.FertilizingSeason
import com.yapt.planttracker.domain.schedule.seasonalAmplitudeOnce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

/** "Pin interval" switch on the inline Water tab settings card (#569), always visible (#656). */
fun PlantDetailViewModel.setPinIntervalToBase(pinned: Boolean) {
    viewModelScope.launch {
        plant.value?.let {
            plantRepository.updatePlant(it.copy(pinIntervalToBase = pinned, updatedAt = System.currentTimeMillis()))
        }
    }
}

/** Persist a complete window, or clear both columns together, from the Water tab (#762). */
fun PlantDetailViewModel.setDormancyWindow(
    startMonth: Int?,
    endMonth: Int?,
    dormantWateringIntervalDays: Int? = null
) {
    require(
        (startMonth == null && endMonth == null) ||
            (startMonth != null && endMonth != null && startMonth in 1..12 && endMonth in 1..12)
    )
    require(
        dormantWateringIntervalDays == null ||
            DormancyWindow.validWateringInterval(dormantWateringIntervalDays) != null
    )
    viewModelScope.launch {
        dormancyEditMutex.withLock {
            // Read inside the lock: a previous selection may have committed while the screen's
            // StateFlow still holds its older Plant snapshot.
            plantRepository.getPlantById(plantId).first()?.let { current ->
                plantRepository.updatePlant(
                    current.copy(
                        dormancyStartMonth = startMonth,
                        dormancyEndMonth = endMonth,
                        dormantWateringIntervalDays = if (startMonth == null || endMonth == null) {
                            null
                        } else {
                            dormantWateringIntervalDays
                        },
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }
}

/**
 * [plant]'s current base-space reference for [WateringAdjustment] row units (#584 review) —
 * mirrors `QuickLogUseCase`'s `currentAdaptiveBaseIntervalDays()` fallback. Collapses to [literal]
 * itself when the plant is pinned or amplitude is Off, matching every other read of
 * `Plant.wateringBaseIntervalDays`.
 */
@Suppress("ReturnCount")
internal suspend fun PlantDetailViewModel.currentBaseIntervalDaysOrLiteral(plant: Plant, literal: Int): Int {
    if (plant.pinIntervalToBase) return literal
    val amplitude = dataStore.seasonalAmplitudeOnce()
    if (amplitude == 0.0) return literal
    return (plant.wateringBaseIntervalDays ?: literal.toDouble()).roundToInt()
}

/** Inline auto-save for the Fertilize tab's season selector (#795); rejects an empty set, same as Add/Edit Plant. */
fun PlantDetailViewModel.setFertilizingSeasons(seasons: Set<FertilizingSeason>) {
    if (seasons.isEmpty()) return
    viewModelScope.launch {
        plant.value?.let { p ->
            plantRepository.updatePlant(
                p.copy(fertilizingSeasons = seasons, updatedAt = System.currentTimeMillis())
            )
        }
    }
}

fun PlantDetailViewModel.setLiquidFertilizer(enabled: Boolean) {
    viewModelScope.launch {
        plant.value?.let { p ->
            plantRepository.updatePlant(p.copy(useLiquidFertilizer = enabled, updatedAt = System.currentTimeMillis()))
        }
    }
}
