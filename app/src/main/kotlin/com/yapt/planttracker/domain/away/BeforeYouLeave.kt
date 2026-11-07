package com.yapt.planttracker.domain.away

import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.util.toLocalDate
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
    /** Plants in neither list that are not [wateredTodayCount]. */
    val canWaitCount: Int,
    /** Visible plants with a WATER log today (#1018): in neither list, since one more watering would be rejected. */
    val wateredTodayCount: Int = 0
)

data class BeforeYouLeaveInput(
    val plants: List<Plant>,
    val careLogs: List<CareLog>,
    /**
     * The day being looked at. [BeforeYouLeave.plan] is for the last day at home only, so there it is the day
     * before [tripFirstDay]; [BeforeYouLeave.holds] takes any earlier day of the lead-in window.
     */
    val today: LocalDate,
    val visitEveryDays: Int?,
    val seasonalAmplitude: Double,
    val hemisphere: Hemisphere,
    /** The trip being left for. */
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
 *
 * A plant already watered today is in neither list (#1018): with a widened horizon a short interval comes due again
 * before the first visit, but the same-day duplicate guard would reject a second watering, and the owner has just
 * done the real one. It is counted on its own, not as "can wait".
 *
 * In the days before the last day at home, [holds] lists the plants to hold until it (product ADR-0076).
 */
object BeforeYouLeave {

    /**
     * Plants to hold for the last day at home ([TripWateringShifts.holds]): due before it, still on schedule when
     * watered then, and due again inside the trip if left alone. Only while [BeforeYouLeaveInput.today] is before
     * the last day; on it a held plant is simply due. A suggestion: nothing is logged or moved.
     */
    fun holds(input: BeforeYouLeaveInput): List<WateringDue> =
        TripWateringShifts.holds(input.toShiftInput(input.plants), input.today)

    fun plan(input: BeforeYouLeaveInput): BeforeYouLeavePlan {
        val gap = (input.visitEveryDays ?: SitterRhythm.DEFAULT_EVERY_DAYS).coerceAtLeast(1)
        val firstVisit = input.today.plusDays(gap.toLong())
        val lastWaterTodayDay = input.today.plusDays(((gap - 1) / 2).toLong())
        val visible = input.plants.filter { it.isVisible }
        val wateredToday = input.careLogs.plantsWateredOn(input.today)
        val active = visible.filter { it.id !in wateredToday }
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
        val broughtForward = TripWateringShifts.bringForward(input.toShiftInput(active), firstVisit)
            .filter { it.plant.id !in listed }
        return BeforeYouLeavePlan(
            firstVisit = firstVisit,
            waterToday = water,
            checkTheSoil = check + broughtForward,
            canWaitCount = active.size - due.size - broughtForward.size,
            wateredTodayCount = visible.size - active.size
        )
    }

    /** Ids of plants with a WATER log on [day] (local calendar day, technical ADR-0013). */
    private fun List<CareLog>.plantsWateredOn(day: LocalDate): Set<Long> =
        filter { it.careType == CareType.WATER && it.loggedAt.toLocalDate() == day }.mapTo(HashSet()) { it.plantId }

    private fun BeforeYouLeaveInput.toShiftInput(plants: List<Plant>) = TripShiftInput(
        plants = plants,
        careLogs = careLogs,
        tripFirstDay = tripFirstDay,
        tripLastDay = tripLastDay,
        seasonalAmplitude = seasonalAmplitude,
        hemisphere = hemisphere
    )
}
