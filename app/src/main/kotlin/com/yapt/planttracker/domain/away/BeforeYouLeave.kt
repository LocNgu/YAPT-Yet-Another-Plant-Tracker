package com.yapt.planttracker.domain.away

import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.Hemisphere
import java.time.LocalDate

data class BeforeYouLeavePlan(
    /** The estimated first visit: [BeforeYouLeaveInput.today] plus the rhythm. */
    val firstVisit: LocalDate,
    /** Due strictly before halfway to the first visit: ticked by default. */
    val waterToday: List<WateringDue>,
    /** Due from halfway up to the first visit: water only if the soil is dry. Unticked by default. */
    val checkTheSoil: List<WateringDue>,
    val canWaitCount: Int
)

data class BeforeYouLeaveInput(
    val plants: List<Plant>,
    val careLogs: List<CareLog>,
    val today: LocalDate,
    val visitEveryDays: Int?,
    val seasonalAmplitude: Double,
    val hemisphere: Hemisphere
)

/**
 * Before you leave (product ADR-0065): on the last day at home, which plants to water now. Due dates come
 * from the Care queue's aggregator, widened to the first visit, with no simulated waterings: the owner is
 * about to do the real ones. It leans dry: a plant due exactly halfway is only a "check the soil".
 */
object BeforeYouLeave {

    fun plan(input: BeforeYouLeaveInput): BeforeYouLeavePlan {
        val gap = (input.visitEveryDays ?: SitterRhythm.DEFAULT_EVERY_DAYS).coerceAtLeast(1)
        val firstVisit = input.today.plusDays(gap.toLong())
        val lastWaterTodayDay = input.today.plusDays(((gap - 1) / 2).toLong())
        val active = input.plants.filter { it.archivedAt == null }
        val due = SitterDueDates.wateringsDueBy(
            plants = active,
            careLogs = input.careLogs,
            today = input.today,
            horizonEnd = firstVisit,
            seasonalAmplitude = input.seasonalAmplitude,
            hemisphere = input.hemisphere
        )
        val (water, check) = due.partition { it.dueDate <= lastWaterTodayDay }
        return BeforeYouLeavePlan(firstVisit, water, check, active.size - due.size)
    }
}
