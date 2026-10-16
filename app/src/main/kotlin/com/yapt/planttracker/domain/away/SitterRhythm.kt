package com.yapt.planttracker.domain.away

import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringScheduleMode
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.domain.watering.WateringOverviewBuilder
import com.yapt.planttracker.util.toStartOfDayMillis
import java.time.LocalDate

/** A plant a sitter rhythm would leave dry for longer than usual, with the interval it normally needs. */
data class RhythmFlaggedPlant(val plant: Plant, val usualIntervalDays: Int)

data class RhythmOption(val everyDays: Int, val flagged: List<RhythmFlaggedPlant>) {
    val flaggedCount: Int get() = flagged.size
}

data class RhythmEvaluation(
    val lastDayAtHome: LocalDate,
    val options: List<RhythmOption>,
    val recommendedEveryDays: Int
)

/**
 * The sitter-rhythm step of trip setup (product ADR-0065). Pure: every plant that is resting or has no
 * watering schedule on the trip's first day is left out, and the usual interval is the one the Watering
 * overview shows (`effectiveWateringIntervalDaysForDisplay()` on that day), so the two can't disagree.
 */
object SitterRhythm {

    @Suppress("MagicNumber")
    val OPTIONS: List<Int> = listOf(4, 5, 7, 10, 14)

    /** The gap used for planning when the trip has no saved rhythm. */
    const val DEFAULT_EVERY_DAYS = 7

    // "At least 1.5 times the usual interval", kept in integers: gap * 2 >= interval * 3.
    private const val LONGER_NUMERATOR = 3
    private const val LONGER_DENOMINATOR = 2

    /** Whether a plant watered once and next [gapDays] later waits at least 1.5x its usual [intervalDays]. */
    fun isLongerThanUsual(gapDays: Int, intervalDays: Int): Boolean =
        gapDays * LONGER_DENOMINATOR >= intervalDays * LONGER_NUMERATOR

    /** The day before the trip's first day, when everything due is assumed watered. */
    fun lastDayAtHome(firstDay: LocalDate): LocalDate = firstDay.minusDays(1)

    /** "The first time by ..." — the last day at home plus the rhythm. */
    fun firstVisitBy(firstDay: LocalDate, everyDays: Int): LocalDate =
        lastDayAtHome(firstDay).plusDays(everyDays.toLong())

    fun evaluate(
        plants: List<Plant>,
        firstDay: LocalDate,
        seasonalAmplitude: Double,
        hemisphere: Hemisphere
    ): RhythmEvaluation {
        val usual = usualIntervals(plants, firstDay, seasonalAmplitude, hemisphere)
        val options = OPTIONS.map { days ->
            RhythmOption(days, usual.filter { isLongerThanUsual(days, it.usualIntervalDays) })
        }
        return RhythmEvaluation(lastDayAtHome(firstDay), options, recommended(options))
    }

    /**
     * The longest rhythm with nothing flagged; when every option flags something, the fewest flagged, the
     * longest of those on a tie (fewer visits asked of the sitter for the same cost).
     */
    private fun recommended(options: List<RhythmOption>): Int {
        options.lastOrNull { it.flagged.isEmpty() }?.let { return it.everyDays }
        val fewest = options.minOf { it.flaggedCount }
        return options.last { it.flaggedCount == fewest }.everyDays
    }

    /**
     * Plants with a normal-season watering interval on [day], by name. Dormant (cadence or suspended)
     * and unscheduled plants are not part of the rhythm question.
     */
    internal fun usualIntervals(
        plants: List<Plant>,
        day: LocalDate,
        seasonalAmplitude: Double,
        hemisphere: Hemisphere
    ): List<RhythmFlaggedPlant> = WateringOverviewBuilder
        .build(plants, emptyMap(), seasonalAmplitude, day.toStartOfDayMillis(), hemisphere)
        .sections
        .flatMap { it.items }
        .filter { it.mode == WateringScheduleMode.NORMAL && it.intervalDays != null }
        .map { RhythmFlaggedPlant(it.plant, requireNotNull(it.intervalDays)) }
        .sortedWith(compareBy({ it.plant.name.lowercase() }, { it.plant.id }))
}
