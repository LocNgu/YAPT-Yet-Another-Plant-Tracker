package com.yapt.planttracker.domain.away

import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.SitterVisit
import com.yapt.planttracker.util.toLocalDate
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Why a planned or edited trip can't be saved (spec Q1 on #895, product ADR-0065). */
sealed interface AwayPeriodValidation {
    data object Valid : AwayPeriodValidation
    data object EndsBeforeStart : AwayPeriodValidation
    data object AlreadyEnded : AwayPeriodValidation
    data object TooLong : AwayPeriodValidation
    data class OverlapsTrip(val other: AwayPeriod) : AwayPeriodValidation
    data class AnotherTripPlanned(val other: AwayPeriod) : AwayPeriodValidation
}

/** Pure Away mode date logic; every comparison is on local calendar days (technical ADR-0013). */
object AwayPeriods {

    const val MAX_DAYS = 90

    fun contains(period: AwayPeriod, date: LocalDate): Boolean =
        !date.isBefore(period.firstDay) && !date.isAfter(period.lastDay)

    fun isAway(date: LocalDate, periods: List<AwayPeriod>): Boolean = periods.any { contains(it, date) }

    /**
     * Whether any trip overlaps the inclusive local-day range from [fromMillis] to [toMillis] — a
     * watering gap touching an away day on either end counts. False for an empty or reversed span.
     * The one predicate every learning exclusion uses (technical ADR-0038).
     */
    fun spansAway(periods: List<AwayPeriod>, fromMillis: Long, toMillis: Long): Boolean {
        if (toMillis <= fromMillis) return false
        val from = fromMillis.toLocalDate()
        val to = toMillis.toLocalDate()
        return periods.any { !it.lastDay.isBefore(from) && !it.firstDay.isAfter(to) }
    }

    /** The trip that is ongoing or still ahead on [today]; the earliest one if there were ever several. */
    fun currentOrUpcoming(periods: List<AwayPeriod>, today: LocalDate): AwayPeriod? =
        periods.filter { !it.lastDay.isBefore(today) }.minByOrNull { it.firstDay }

    fun lengthInDays(firstDay: LocalDate, lastDay: LocalDate): Long =
        ChronoUnit.DAYS.between(firstDay, lastDay) + 1

    /**
     * Checks a trip being planned, or [editingId]'s trip being edited (it never conflicts with itself).
     * The first day may already be past while the trip is ongoing; the last day may not.
     */
    fun validate(
        firstDay: LocalDate,
        lastDay: LocalDate,
        today: LocalDate,
        existing: List<AwayPeriod>,
        editingId: Long? = null
    ): AwayPeriodValidation {
        val others = existing.filter { it.id != editingId }
        val overlapping = others.firstOrNull { !it.lastDay.isBefore(firstDay) && !it.firstDay.isAfter(lastDay) }
        val otherCurrent = currentOrUpcoming(others, today)
        return when {
            lastDay.isBefore(firstDay) -> AwayPeriodValidation.EndsBeforeStart
            lastDay.isBefore(today) -> AwayPeriodValidation.AlreadyEnded
            lengthInDays(firstDay, lastDay) > MAX_DAYS -> AwayPeriodValidation.TooLong
            overlapping != null -> AwayPeriodValidation.OverlapsTrip(overlapping)
            otherCurrent != null -> AwayPeriodValidation.AnotherTripPlanned(otherCurrent)
            else -> AwayPeriodValidation.Valid
        }
    }

    /**
     * **I'm back** on [today]: the last day becomes yesterday, or null (remove the trip) when that
     * falls before the first day. Never extends a trip that already ended.
     */
    fun endEarly(period: AwayPeriod, today: LocalDate): AwayPeriod? {
        val newLastDay = minOf(period.lastDay, today.minusDays(1))
        return if (newLastDay.isBefore(period.firstDay)) null else period.copy(lastDay = newLastDay)
    }

    /** The visits [period]'s (possibly edited) dates leave outside the trip; the caller deletes them. */
    fun visitsOutside(period: AwayPeriod, visits: List<SitterVisit>): List<SitterVisit> =
        visits.filter { it.awayPeriodId == period.id && !contains(period, it.day) }
}
