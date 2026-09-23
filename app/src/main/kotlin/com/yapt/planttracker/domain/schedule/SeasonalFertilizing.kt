package com.yapt.planttracker.domain.schedule

import com.yapt.planttracker.domain.model.Plant
import java.time.LocalDate

/** Discrete, manually configured fertilizing seasons (#286, product ADR-0045). */
enum class FertilizingSeason {
    SPRING,
    SUMMER,
    AUTUMN,
    WINTER
}

/** Hemisphere-aware season selection and nullable-slot fallback for fertilizing schedules. */
object SeasonalFertilizing {

    private const val SPRING_START_MONTH = 3
    private const val SPRING_END_MONTH = 5
    private const val SUMMER_START_MONTH = 6
    private const val SUMMER_END_MONTH = 8
    private const val AUTUMN_START_MONTH = 9
    private const val AUTUMN_END_MONTH = 11

    fun season(date: LocalDate, hemisphere: Hemisphere): FertilizingSeason {
        val northernSeason = when (date.monthValue) {
            in SPRING_START_MONTH..SPRING_END_MONTH -> FertilizingSeason.SPRING
            in SUMMER_START_MONTH..SUMMER_END_MONTH -> FertilizingSeason.SUMMER
            in AUTUMN_START_MONTH..AUTUMN_END_MONTH -> FertilizingSeason.AUTUMN
            else -> FertilizingSeason.WINTER
        }
        return if (hemisphere == Hemisphere.NORTHERN) northernSeason else northernSeason.opposite()
    }

    fun effectiveInterval(plant: Plant, date: LocalDate, hemisphere: Hemisphere): Int? {
        val fallback = plant.fertilizingIntervalDays ?: return null
        return when (season(date, hemisphere)) {
            FertilizingSeason.SPRING -> plant.fertilizingIntervalSpring
            FertilizingSeason.SUMMER -> plant.fertilizingIntervalSummer
            FertilizingSeason.AUTUMN -> plant.fertilizingIntervalAutumn
            FertilizingSeason.WINTER -> plant.fertilizingIntervalWinter
        } ?: fallback
    }

    private fun FertilizingSeason.opposite(): FertilizingSeason = when (this) {
        FertilizingSeason.SPRING -> FertilizingSeason.AUTUMN
        FertilizingSeason.SUMMER -> FertilizingSeason.WINTER
        FertilizingSeason.AUTUMN -> FertilizingSeason.SPRING
        FertilizingSeason.WINTER -> FertilizingSeason.SUMMER
    }
}
