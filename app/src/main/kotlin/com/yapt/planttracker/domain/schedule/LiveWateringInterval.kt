package com.yapt.planttracker.domain.schedule

import com.yapt.planttracker.domain.model.Plant
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Today's watering interval as the Plant Detail hero and Water-tab card present it (#927, product
 * ADR-0068). [effectiveDays] is what the due-date math uses today, never `Plant.wateringIntervalDays`.
 * [adjustment] is non-null only when the seasonal curve actually moves today's interval away from the
 * rounded season-neutral base, so the UI can show "adjusted for winter · base 8 days" without
 * re-deriving any season math.
 */
data class LiveWateringInterval(
    val effectiveDays: Int,
    val adjustment: SeasonalAdjustment? = null
) {
    data class SeasonalAdjustment(val season: FertilizingSeason, val baseDays: Int)

    companion object {
        fun compute(
            plant: Plant,
            today: LocalDate,
            seasonalAmplitude: Double,
            hemisphere: Hemisphere
        ): LiveWateringInterval? {
            val effective = CareSchedule.effectiveWateringIntervalDaysForDisplay(
                plant = plant,
                nowDate = today,
                seasonalAmplitude = seasonalAmplitude,
                hemisphere = hemisphere
            ) ?: return null
            val seasonal = seasonalAmplitude != 0.0 && !plant.pinIntervalToBase
            // Mirrors CareSchedule's fallback: a never-recorded base is the literal.
            val base = plant.wateringBaseIntervalDays?.roundToInt() ?: plant.wateringIntervalDays ?: effective
            return if (seasonal && effective != base) {
                LiveWateringInterval(effective, SeasonalAdjustment(SeasonalFertilizing.season(today, hemisphere), base))
            } else {
                LiveWateringInterval(effective)
            }
        }
    }
}
