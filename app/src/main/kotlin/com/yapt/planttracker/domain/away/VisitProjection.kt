package com.yapt.planttracker.domain.away

import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.SitterVisit
import com.yapt.planttracker.domain.model.SitterVisitState
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.domain.watering.WateringOverviewBuilder
import com.yapt.planttracker.domain.watering.WateringOverviewGroup
import com.yapt.planttracker.util.toStartOfDayMillis
import java.time.LocalDate

/**
 * Everything the projection reads. [visits] are the trip's visits; [visitDay] is the day being planned and
 * [gapDays] the expected distance to the visit after it.
 */
data class VisitProjectionInput(
    val plants: List<Plant>,
    val careLogs: List<CareLog>,
    val trip: AwayPeriod,
    val visits: List<SitterVisit>,
    val visitDay: LocalDate,
    val gapDays: Int,
    val today: LocalDate,
    val seasonalAmplitude: Double,
    val hemisphere: Hemisphere
)

data class ProjectedPlant(
    val plant: Plant,
    val dueDate: LocalDate,
    val usualIntervalDays: Int?,
    val missedLastTime: Boolean,
    val longerThanUsual: Boolean
)

data class VisitProjection(
    val visitDay: LocalDate,
    val gapDays: Int,
    /** The last due date the list reaches: "the list includes anything due by ...". */
    val includesDueBy: LocalDate,
    val listed: List<ProjectedPlant>,
    /** Resting plants (dormant, or watering suspended) that are not on the list: the "don't water these" page. */
    val resting: List<Plant>
)

/**
 * Which plants a sitter waters on one visit (product ADR-0065). Pure; due dates come only from
 * [TodayQueueAggregator][com.yapt.planttracker.domain.today.TodayQueueAggregator], widened to the end of the
 * list's reach, with every earlier visit and every pre-trip watering not yet logged simulated as a WATER log.
 *
 * The matching rule — due on or before the visit day *v*, or due strictly before *v + g/2* — is "due on or
 * before *v + (g - 1) / 2*" in whole days, so a tie at exactly *v + g/2* goes to the next visit and the plan
 * leans dry.
 */
object VisitProjector {

    fun project(input: VisitProjectionInput): VisitProjection {
        val active = input.plants.filter { it.archivedAt == null }
        val includesDueBy = input.visitDay.plusDays(((input.gapDays - 1) / 2).toLong().coerceAtLeast(0L))
        val simulated = simulatedWaterings(input, active)
        val due = SitterDueDates.wateringsDueBy(
            plants = active.withClearedOverrides(simulated, input.careLogs),
            careLogs = input.careLogs + simulated.map { (plantId, day) ->
                SitterDueDates.syntheticWaterLog(plantId, day)
            },
            today = input.today,
            horizonEnd = includesDueBy,
            seasonalAmplitude = input.seasonalAmplitude,
            hemisphere = input.hemisphere
        )
        val items = WateringOverviewBuilder
            .build(active, emptyMap(), input.seasonalAmplitude, input.today.toStartOfDayMillis(), input.hemisphere)
            .sections
            .flatMap { it.items.map { item -> item to it.group } }
        val itemByPlant = items.associate { (item, _) -> item.plant.id to item }
        val missed = missedLastTime(input)
        val listed = due.map { watering ->
            val interval = itemByPlant[watering.plant.id]?.intervalDays
            ProjectedPlant(
                plant = watering.plant,
                dueDate = watering.dueDate,
                usualIntervalDays = interval,
                missedLastTime = watering.plant.id in missed,
                longerThanUsual = interval != null && SitterRhythm.isLongerThanUsual(input.gapDays, interval)
            )
        }
        val listedIds = listed.mapTo(HashSet()) { it.plant.id }
        val resting = items
            .filter { (_, group) -> group == WateringOverviewGroup.RESTING }
            .map { (item, _) -> item.plant }
            .filter { it.id !in listedIds }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
        return VisitProjection(input.visitDay, input.gapDays, includesDueBy, listed, resting)
    }

    /**
     * plantId -> the latest day a watering is simulated on. A visit's plants count by its state (a confirmed
     * visit only the watered ones, an unconfirmed one all of them, one that didn't happen none); and every
     * plant due on or before the last day at home with no logged watering since is assumed watered that day.
     */
    private fun simulatedWaterings(input: VisitProjectionInput, active: List<Plant>): Map<Long, LocalDate> {
        val simulated = HashMap<Long, LocalDate>()
        fun simulate(plantId: Long, day: LocalDate) {
            val current = simulated[plantId]
            if (current == null || day > current) simulated[plantId] = day
        }
        val lastDayAtHome = SitterRhythm.lastDayAtHome(input.trip.firstDay)
        SitterDueDates.wateringsDueBy(
            plants = active,
            careLogs = input.careLogs.filter { it.loggedAt <= SitterDueDates.endOfDayMillis(lastDayAtHome) },
            today = lastDayAtHome,
            horizonEnd = lastDayAtHome,
            seasonalAmplitude = input.seasonalAmplitude,
            hemisphere = input.hemisphere
        ).forEach { simulate(it.plant.id, lastDayAtHome) }
        for (visit in earlierVisits(input)) {
            for (visitPlant in visit.plants) {
                val counts = when (visit.state) {
                    SitterVisitState.PLANNED -> true
                    SitterVisitState.CONFIRMED -> visitPlant.watered == true
                    SitterVisitState.DID_NOT_HAPPEN -> false
                }
                if (counts) simulate(visitPlant.plantId, visit.day)
            }
        }
        return simulated
    }

    /** A real watering clears an active reschedule when it is the plant's newest; so does a simulated one. */
    private fun List<Plant>.withClearedOverrides(
        simulated: Map<Long, LocalDate>,
        careLogs: List<CareLog>
    ): List<Plant> {
        val newestReal = careLogs.asSequence()
            .filter { it.careType == CareType.WATER }
            .groupBy { it.plantId }
            .mapValues { (_, logs) -> logs.maxOf { it.loggedAt } }
        return map { plant ->
            val day = simulated[plant.id]
            val clears = day != null && SitterDueDates.noonMillis(day) >= (newestReal[plant.id] ?: Long.MIN_VALUE)
            if (clears && plant.wateringDueDateOverride != null) plant.copy(wateringDueDateOverride = null) else plant
        }
    }

    /**
     * The visits before the planned day, plus any other (confirmed or not-held) visit on it; a same-day
     * PLANNED one is the one being replaced.
     */
    private fun earlierVisits(input: VisitProjectionInput): List<SitterVisit> = input.visits.filter {
        it.day < input.visitDay || (it.day == input.visitDay && it.state != SitterVisitState.PLANNED)
    }

    /** Plants unticked at the visit just before this one, when that visit was confirmed. */
    private fun missedLastTime(input: VisitProjectionInput): Set<Long> {
        val previous = earlierVisits(input)
            .filter { it.state != SitterVisitState.DID_NOT_HAPPEN }
            .maxWithOrNull(compareBy({ it.day }, { it.id }))
        if (previous == null || previous.state != SitterVisitState.CONFIRMED) return emptySet()
        return previous.plants.filter { it.watered == false }.mapTo(HashSet()) { it.plantId }
    }
}
