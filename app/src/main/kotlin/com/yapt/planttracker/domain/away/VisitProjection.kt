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
import com.yapt.planttracker.util.toNoonMillis
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
    val resting: List<Plant>,
    /** Scheduled, non-resting plants due after [includesDueBy] (or removed from [listed] by the owner): addable. */
    val notListed: List<ProjectedPlant> = emptyList(),
    /** What each [resting] plant becomes if the owner adds it: due on the visit day. Keyed by plant id. */
    val restingAsListed: Map<Long, ProjectedPlant> = emptyMap()
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

    /** How far past [VisitProjection.includesDueBy] the not-listed plants reach, in the same aggregator call. */
    const val NOT_LISTED_HORIZON_DAYS = 366L

    fun project(input: VisitProjectionInput): VisitProjection {
        val active = input.plants.filter { it.isVisible }
        val includesDueBy = input.visitDay.plusDays(((input.gapDays - 1) / 2).toLong().coerceAtLeast(0L))
        val simulated = simulatedWaterings(input, active)
        val due = SitterDueDates.wateringsDueBy(
            plants = active.withClearedOverrides(simulated, input.careLogs),
            careLogs = input.careLogs + simulated.map { (plantId, day) ->
                SitterDueDates.syntheticWaterLog(plantId, day)
            },
            today = input.today,
            horizonEnd = includesDueBy.plusDays(NOT_LISTED_HORIZON_DAYS),
            seasonalAmplitude = input.seasonalAmplitude,
            hemisphere = input.hemisphere
        )
        val items = WateringOverviewBuilder
            .build(active, emptyMap(), input.seasonalAmplitude, input.today.toStartOfDayMillis(), input.hemisphere)
            .sections
            .flatMap { it.items.map { item -> item to it.group } }
        val itemByPlant = items.associate { (item, _) -> item.plant.id to item }
        val missed = missedLastTime(input)
        val projected = due.map { watering ->
            val interval = itemByPlant[watering.plant.id]?.intervalDays
            ProjectedPlant(
                plant = watering.plant,
                dueDate = watering.dueDate,
                usualIntervalDays = interval,
                missedLastTime = watering.plant.id in missed,
                longerThanUsual = interval != null && SitterRhythm.isLongerThanUsual(input.gapDays, interval)
            )
        }
        val (listed, beyond) = projected.partition { it.dueDate <= includesDueBy }
        val listedIds = listed.mapTo(HashSet()) { it.plant.id }
        val resting = items
            .filter { (_, group) -> group == WateringOverviewGroup.RESTING }
            .map { (item, _) -> item.plant }
            .filter { it.id !in listedIds }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
        val restingIds = resting.mapTo(HashSet()) { it.id }
        val notListed = beyond.filter { it.plant.id !in restingIds }
        val restingAsListed = resting.associate { plant ->
            plant.id to ProjectedPlant(
                plant = plant,
                dueDate = input.visitDay,
                usualIntervalDays = itemByPlant[plant.id]?.intervalDays,
                missedLastTime = plant.id in missed,
                longerThanUsual = false
            )
        }
        return VisitProjection(
            visitDay = input.visitDay,
            gapDays = input.gapDays,
            includesDueBy = includesDueBy,
            listed = listed,
            resting = resting,
            notListed = notListed,
            restingAsListed = restingAsListed
        )
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
            val clears = day != null && day.toNoonMillis() >= (newestReal[plant.id] ?: Long.MIN_VALUE)
            if (clears && plant.wateringDueDateOverride != null) {
                plant.copy(wateringDueDateOverride = null, wateringDueDateOverrideMadeAt = null)
            } else {
                plant
            }
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

/** The owner's edits to a suggested list: plants unticked from [VisitProjection.listed] and plants added to it. */
data class SitterSelection(val added: Set<Long> = emptySet(), val removed: Set<Long> = emptySet())

private val projectedOrder = compareBy<ProjectedPlant>({ it.dueDate }, { it.plant.name.lowercase() }, { it.plant.id })

/**
 * The suggestion after the owner's edits (product ADR-0077). An added resting plant moves onto the list, due
 * on the visit day, and off the "don't water these" page; a removed plant is kept in [VisitProjection.notListed]
 * so it can be shown unticked. Ids that resolve to nothing, and overrides that no longer matter, are ignored.
 */
fun VisitProjection.applySelection(selection: SitterSelection): VisitProjection {
    val removedListed = listed.filter { it.plant.id in selection.removed }
    val addedNotListed = notListed.filter { it.plant.id in selection.added }
    val addedResting = resting.filter { it.id in selection.added }.map { plant ->
        restingAsListed[plant.id]
            ?: ProjectedPlant(plant, visitDay, null, missedLastTime = false, longerThanUsual = false)
    }
    val newListed = (listed.filter { it.plant.id !in selection.removed } + addedNotListed + addedResting)
        .sortedWith(projectedOrder)
    val newNotListed = (notListed.filter { it.plant.id !in selection.added } + removedListed)
        .sortedWith(projectedOrder)
    return copy(
        listed = newListed,
        resting = resting.filter { it.id !in selection.added },
        notListed = newNotListed
    )
}

/** The selection that reproduces [saved] from [projection]: what was shared, as edits to today's suggestion. */
fun seedSelection(projection: VisitProjection, saved: Set<Long>): SitterSelection {
    val listedIds = projection.listed.mapTo(HashSet()) { it.plant.id }
    val known = listedIds +
        projection.notListed.map { it.plant.id } +
        projection.resting.map { it.id }
    return SitterSelection(
        added = (saved - listedIds).filterTo(HashSet()) { it in known },
        removed = listedIds - saved
    )
}
