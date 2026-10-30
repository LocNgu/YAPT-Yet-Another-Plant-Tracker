package com.yapt.planttracker.domain.away

import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.util.toLocalDate
import com.yapt.planttracker.util.toNoonMillis
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** What the two lining-up rules read: the plants, their logs and the trip's first and last day. */
internal data class TripShiftInput(
    val plants: List<Plant>,
    val careLogs: List<CareLog>,
    val tripFirstDay: LocalDate,
    val tripLastDay: LocalDate,
    val seasonalAmplitude: Double,
    val hemisphere: Hemisphere
)

/**
 * Lining waterings up around a trip (product ADR-0076): a plant that would otherwise come due during the trip is
 * either held until the last day at home or watered on it, so it lasts the whole trip. Pure and based only on
 * the plant's own effective interval. These are suggestions: nothing moves a due date or writes a plant row, and
 * every date still comes from [SitterDueDates].
 *
 * Everything is evaluated as of `L`, the last day at home, whatever day it is today (the convention
 * [VisitProjector] uses): interval, cadence and due dates are `L`'s. Hidden, archived, resting, unscheduled and
 * never-watered plants are never suggested.
 */
internal object TripWateringShifts {

    // "At least 70% of the interval has elapsed", kept in integers: 10 * elapsed >= 7 * interval.
    private const val ELAPSED_DENOMINATOR = 10
    private const val ELAPSED_NUMERATOR = 7

    private data class Candidate(val plant: Plant, val intervalDays: Int, val lastWateredAt: Long)

    /**
     * Whether a plant with [intervalDays] watered on the last day at home needs no watering before the
     * owner is back: `L + I >= E`. A plant due on the trip's last day counts, because the owner is home that day.
     */
    fun lastsWholeTrip(intervalDays: Int, tripFirstDay: LocalDate, tripLastDay: LocalDate): Boolean =
        SitterRhythm.lastDayAtHome(tripFirstDay).plusDays(intervalDays.toLong()) >= tripLastDay

    /**
     * Plants to hold for the last day at home, shown only while [today] is before it: due before `L`, still
     * on schedule when watered on `L` (the same 15% gate every watering surface uses, so none opens "Why was
     * it late?"), and due again inside the trip if left alone. By due date, then name, then id.
     */
    fun holds(input: TripShiftInput, today: LocalDate): List<WateringDue> {
        val lastDay = SitterRhythm.lastDayAtHome(input.tripFirstDay)
        if (today >= lastDay) return emptyList()
        val asOf = AsOfLastDay(input)
        val lastDayMillis = lastDay.toNoonMillis()
        return asOf.dueBy(lastDay).filter { due ->
            val candidate = asOf.candidates.getValue(due.plant.id)
            due.dueDate < lastDay &&
                CareSchedule.isWateringOnScheduleAt(candidate.lastWateredAt, candidate.intervalDays, lastDayMillis) &&
                due.dueDate.plusDays(candidate.intervalDays.toLong()) < input.tripLastDay
        }
    }

    /**
     * Plants to add to Check the soil on the last day at home: past [firstVisit] (so in neither existing list)
     * yet due inside the trip, and at least 70% through their interval. By due date, then name, then id.
     */
    fun bringForward(input: TripShiftInput, firstVisit: LocalDate): List<WateringDue> {
        val asOf = AsOfLastDay(input)
        return asOf.dueBy(maxOf(input.tripLastDay, firstVisit)).filter { due ->
            val candidate = asOf.candidates.getValue(due.plant.id)
            val elapsedDays = ChronoUnit.DAYS.between(candidate.lastWateredAt.toLocalDate(), asOf.lastDay)
            due.dueDate > firstVisit &&
                due.dueDate < input.tripLastDay &&
                ELAPSED_DENOMINATOR * elapsedDays >= ELAPSED_NUMERATOR * candidate.intervalDays
        }
    }

    /** The plants both rules can apply to, with `L`'s interval and each one's newest WATER, and the logs up to `L`. */
    private class AsOfLastDay(private val input: TripShiftInput) {
        val lastDay: LocalDate = SitterRhythm.lastDayAtHome(input.tripFirstDay)
        private val logs = input.careLogs.filter { it.loggedAt <= SitterDueDates.endOfDayMillis(lastDay) }
        val candidates: Map<Long, Candidate> = findCandidates()

        fun dueBy(horizonEnd: LocalDate): List<WateringDue> = SitterDueDates.wateringsDueBy(
            plants = candidates.values.map { it.plant },
            careLogs = logs,
            today = lastDay,
            horizonEnd = horizonEnd,
            seasonalAmplitude = input.seasonalAmplitude,
            hemisphere = input.hemisphere
        )

        private fun findCandidates(): Map<Long, Candidate> {
            val newestWater = logs.asSequence()
                .filter { it.careType == CareType.WATER }
                .groupBy { it.plantId }
                .mapValues { (_, waterLogs) -> waterLogs.maxOf { it.loggedAt } }
            val usual = SitterRhythm.usualIntervals(
                plants = input.plants.filter { it.isVisible },
                day = lastDay,
                seasonalAmplitude = input.seasonalAmplitude,
                hemisphere = input.hemisphere
            )
            return usual
                .filter { lastsWholeTrip(it.usualIntervalDays, input.tripFirstDay, input.tripLastDay) }
                .mapNotNull { flagged ->
                    newestWater[flagged.plant.id]?.let { Candidate(flagged.plant, flagged.usualIntervalDays, it) }
                }
                .associateBy { it.plant.id }
        }
    }
}
