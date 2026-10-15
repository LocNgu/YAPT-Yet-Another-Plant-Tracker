package com.yapt.planttracker.domain.away

import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.SitterVisit
import com.yapt.planttracker.util.toLocalDate
import java.time.LocalDate
import java.time.temporal.ChronoUnit

sealed interface AwayPeriodValidation {
    data object Valid : AwayPeriodValidation
    data object FirstDayAfterLastDay : AwayPeriodValidation
    data object EndsInThePast : AwayPeriodValidation
    data object TooLong : AwayPeriodValidation
    data class OverlapsTrip(val other: AwayPeriod) : AwayPeriodValidation
    data class AnotherCurrentOrUpcomingTrip(val other: AwayPeriod) : AwayPeriodValidation
}

/**
 * Pure trip date logic (product ADR-0065, technical ADR-0038). Trips are inclusive local calendar days;
 * every instant comparison goes through [toLocalDate] (technical ADR-0013).
 */
object AwayPeriods {

    const val MAX_TRIP_DAYS = 90

    fun isAway(date: LocalDate, periods: List<AwayPeriod>): Boolean =
        periods.any { date in it.firstDay..it.lastDay }

    /**
     * Whether any trip overlaps the inclusive local-day range from [fromMillis] to [toMillis]. This is
     * the single predicate every exclusion uses. A span that doesn't run forward is never "away".
     */
    fun spansAway(periods: List<AwayPeriod>, fromMillis: Long, toMillis: Long): Boolean {
        if (toMillis <= fromMillis) return false
        val fromDay = fromMillis.toLocalDate()
        val toDay = toMillis.toLocalDate()
        return periods.any { it.firstDay <= toDay && it.lastDay >= fromDay }
    }

    /** The earliest trip that hasn't ended yet: the one in progress, else the next one. */
    fun currentOrUpcoming(periods: List<AwayPeriod>, today: LocalDate): AwayPeriod? =
        periods.filter { it.lastDay >= today }.minWithOrNull(compareBy({ it.firstDay }, { it.id }))

    /** Validates planning a trip, or editing one when [editingId] is its id (a trip never conflicts with itself). */
    fun validate(
        firstDay: LocalDate,
        lastDay: LocalDate,
        today: LocalDate,
        existing: List<AwayPeriod>,
        editingId: Long? = null
    ): AwayPeriodValidation {
        val others = existing.filter { editingId == null || it.id != editingId }
        return when {
            firstDay > lastDay -> AwayPeriodValidation.FirstDayAfterLastDay
            lastDay < today -> AwayPeriodValidation.EndsInThePast
            ChronoUnit.DAYS.between(firstDay, lastDay) + 1 > MAX_TRIP_DAYS -> AwayPeriodValidation.TooLong
            else -> {
                val overlapping = others.firstOrNull { it.firstDay <= lastDay && it.lastDay >= firstDay }
                val unfinished = others.firstOrNull { it.lastDay >= today }
                when {
                    overlapping != null -> AwayPeriodValidation.OverlapsTrip(overlapping)
                    unfinished != null -> AwayPeriodValidation.AnotherCurrentOrUpcomingTrip(unfinished)
                    else -> AwayPeriodValidation.Valid
                }
            }
        }
    }

    /**
     * "I'm back": the last day becomes yesterday, or null (remove the trip) when that falls before the
     * first day. A trip that already ended earlier keeps its own last day.
     */
    fun endEarly(period: AwayPeriod, today: LocalDate): AwayPeriod? {
        val newLastDay = minOf(period.lastDay, today.minusDays(1))
        return if (newLastDay < period.firstDay) null else period.copy(lastDay = newLastDay)
    }

    fun isVisitDayInside(day: LocalDate, firstDay: LocalDate, lastDay: LocalDate): Boolean =
        day in firstDay..lastDay

    /** The visits an edit to [firstDay]..[lastDay] strands outside the trip; the caller deletes them. */
    fun visitsOutside(visits: List<SitterVisit>, firstDay: LocalDate, lastDay: LocalDate): List<SitterVisit> =
        visits.filterNot { isVisitDayInside(it.day, firstDay, lastDay) }
}
