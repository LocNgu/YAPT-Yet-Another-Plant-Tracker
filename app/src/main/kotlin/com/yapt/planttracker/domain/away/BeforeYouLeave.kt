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
    /**
     * Due from halfway up to the first visit, then plants due later inside the trip that are about 70% through
     * their interval (product ADR-0076). Water only if the soil is dry. Unticked by default.
     */
    val checkTheSoil: List<WateringDue>,
    val canWaitCount: Int
)

data class BeforeYouLeaveInput(
    val plants: List<Plant>,
    val careLogs: List<CareLog>,
    val today: LocalDate,
    val visitEveryDays: Int?,
    val seasonalAmplitude: Double,
    val hemisphere: Hemisphere,
    /** The trip being left for: [today] is the day before [tripFirstDay]. */
    val tripFirstDay: LocalDate,
    val tripLastDay: LocalDate
)

/**
 * Before you leave (product ADR-0065): on the last day at home, which plants to water now. Due dates come
 * from the Care queue's aggregator, widened to the first visit, with no simulated waterings: the owner is
 * about to do the real ones. It leans dry: a plant due exactly halfway is only a "check the soil".
 *
 * On top of that, a plant that would otherwise come due inside the trip and is about 70% through its interval
 * is added to Check the soil, so it can last the whole trip ([TripWateringShifts.bringForward], product
 * ADR-0076). Those plants are due after the first visit, so they are never in the two lists above.
 */
object BeforeYouLeave {

    fun plan(input: BeforeYouLeaveInput): BeforeYouLeavePlan {
        val gap = (input.visitEveryDays ?: SitterRhythm.DEFAULT_EVERY_DAYS).coerceAtLeast(1)
        val firstVisit = input.today.plusDays(gap.toLong())
        val lastWaterTodayDay = input.today.plusDays(((gap - 1) / 2).toLong())
        val active = input.plants.filter { it.isVisible }
        val due = SitterDueDates.wateringsDueBy(
            plants = active,
            careLogs = input.careLogs,
            today = input.today,
            horizonEnd = firstVisit,
            seasonalAmplitude = input.seasonalAmplitude,
            hemisphere = input.hemisphere
        )
        val (water, check) = due.partition { it.dueDate <= lastWaterTodayDay }
        val listed = due.mapTo(HashSet()) { it.plant.id }
        val broughtForward = TripWateringShifts.bringForward(
            TripShiftInput(
                plants = active,
                careLogs = input.careLogs,
                tripFirstDay = input.tripFirstDay,
                tripLastDay = input.tripLastDay,
                seasonalAmplitude = input.seasonalAmplitude,
                hemisphere = input.hemisphere
            ),
            firstVisit
        ).filter { it.plant.id !in listed }
        return BeforeYouLeavePlan(
            firstVisit = firstVisit,
            waterToday = water,
            checkTheSoil = check + broughtForward,
            canWaitCount = active.size - due.size - broughtForward.size
        )
    }
}
