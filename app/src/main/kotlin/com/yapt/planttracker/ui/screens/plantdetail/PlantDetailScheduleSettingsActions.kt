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

/**
 * "Pin interval" switch on the inline Water tab settings card (#569), always visible (#656). Reads
 * the plant fresh inside [PlantDetailViewModel.intervalEditMutex] (#804) rather than the cached
 * `plant` StateFlow, which a season-toggle or liquid-fertilizer write sharing this lock can still
 * have in flight.
 */
fun PlantDetailViewModel.setPinIntervalToBase(pinned: Boolean) {
    viewModelScope.launch {
        intervalEditMutex.withLock {
            val p = plantRepository.getPlantById(plantId).first() ?: return@withLock
            plantRepository.updatePlant(p.copy(pinIntervalToBase = pinned, updatedAt = System.currentTimeMillis()))
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

/**
 * Inline auto-save for the Fertilize tab's season selector (#795). [FertilizingSeasonsSelector]
 * reports the tapped [season] rather than a full replacement set (#804) — this reads the plant
 * fresh inside [PlantDetailViewModel.intervalEditMutex], applies the toggle to *that* set, and
 * rejects (writes nothing) if the result would be empty, same rule as Add/Edit Plant but checked
 * against the freshly read row rather than the cached `plant` StateFlow. A prior season toggle, or
 * a liquid-fertilizer/pin-interval write sharing this lock, can still be in flight when the next
 * one starts — that race, not the "at least one season" rule itself, is what #804 fixed.
 */
fun PlantDetailViewModel.toggleFertilizingSeason(season: FertilizingSeason) {
    viewModelScope.launch {
        intervalEditMutex.withLock {
            val p = plantRepository.getPlantById(plantId).first() ?: return@withLock
            val newSeasons = if (season in p.fertilizingSeasons) {
                p.fertilizingSeasons - season
            } else {
                p.fertilizingSeasons + season
            }
            if (newSeasons.isEmpty()) return@withLock
            plantRepository.updatePlant(p.copy(fertilizingSeasons = newSeasons, updatedAt = System.currentTimeMillis()))
        }
    }
}

/**
 * Liquid-fertilizer switch on the Fertilize tab's inline settings card. Reads the plant fresh
 * inside [PlantDetailViewModel.intervalEditMutex] (#804) rather than the cached `plant` StateFlow,
 * which a season-toggle or pin-interval write sharing this lock can still have in flight.
 */
fun PlantDetailViewModel.setLiquidFertilizer(enabled: Boolean) {
    viewModelScope.launch {
        intervalEditMutex.withLock {
            val p = plantRepository.getPlantById(plantId).first() ?: return@withLock
            plantRepository.updatePlant(p.copy(useLiquidFertilizer = enabled, updatedAt = System.currentTimeMillis()))
        }
    }
}
