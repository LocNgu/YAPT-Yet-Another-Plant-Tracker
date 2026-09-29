package com.yapt.planttracker.domain.schedule

import com.yapt.planttracker.util.toLocalDate
import com.yapt.planttracker.util.toStartOfDayMillis
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A season offered by the planned-repot picker: its [year] is the year of the season's first day. */
data class RepotPlanSeason(val season: FertilizingSeason, val year: Int, val startAtMillis: Long)

enum class RepotPlanState { UPCOMING, IN_SEASON, SEASON_ENDED }

/**
 * A resolved one-off planned repot (#809, product ADR-0057). [startAtMillis] is the stored target
 * timestamp verbatim — the due date never moves if the timezone or hemisphere later changes; only the
 * derived [season]/[year] label does. [seasonEndAtMillis] is the start of day of the first day *after*
 * the season, so the plan reads due for the whole season and overdue only from that day on.
 */
data class RepotPlan(
    val season: FertilizingSeason,
    val year: Int,
    val startAtMillis: Long,
    val seasonEndAtMillis: Long
) {
    fun stateOn(nowDate: LocalDate): RepotPlanState = when {
        nowDate.isBefore(startAtMillis.toLocalDate()) -> RepotPlanState.UPCOMING
        nowDate.isBefore(seasonEndAtMillis.toLocalDate()) -> RepotPlanState.IN_SEASON
        else -> RepotPlanState.SEASON_ENDED
    }
}

/**
 * Seasonal repotting rules (#809, product ADR-0057, amending product ADR-0022's due-date rule): the
 * nearest-preferred-season shift for the recurring interval date, the picker's upcoming seasons, plan
 * resolution, and the plan-clear rule. Pure — every caller passes its own hemisphere and, where a rule
 * needs one, its own clock. Seasons
 * reuse [FertilizingSeason] and [SeasonalFertilizing.season]; a season is always a whole calendar quarter
 * (Mar/Jun/Sep/Dec 1 starts), which the boundary walks below derive from `season()` rather than restate.
 */
object SeasonalRepotting {

    /** Guaranteed to terminate: a non-empty proper subset of seasons has a stretch start within a year. */
    private const val MAX_MONTHS_LOOKAHEAD = 12

    /**
     * The recurring interval's raw due date ([rawDueAtMillis], `(lastRepottedAt ?: createdAt) + interval`)
     * moved into a preferred season. Every season preferred is an unconditional early-out: the raw date is
     * returned unchanged, so every existing plant stays bit-for-bit identical.
     *
     * The result is **time-stable**: a pure function of the raw date, the preferred seasons, the hemisphere
     * and the anchor — never of today's date, so there is deliberately no clock parameter. An overdue plant
     * therefore stays overdue until it is repotted (or the plan or preferred seasons change), and the date
     * never jumps to a later stretch just because the raw date slipped into the past.
     *
     * - A raw date already inside a preferred season is unchanged, past or future.
     * - Any other raw date, past or future, snaps to the first day of the *nearest* preferred stretch
     *   (contiguous run of preferred seasons, wrapping the year boundary), measured to the stretch's first
     *   day; equidistant goes to the later one. A nearest candidate at or before [anchorAtMillis]'s day
     *   (the last repot, or `createdAt`) falls back to the next stretch forward.
     *
     * The shifted date can therefore land in the past: that is the overdue state, the ordinary
     * due-then-overdue rule (due on its first day, overdue after it).
     */
    fun nextPreferredDueAtMillis(
        rawDueAtMillis: Long,
        preferredSeasons: Set<FertilizingSeason>,
        hemisphere: Hemisphere,
        anchorAtMillis: Long
    ): Long {
        val unrestricted = preferredSeasons.isEmpty() || preferredSeasons.containsAll(FertilizingSeason.entries)
        val rawDate = rawDueAtMillis.toLocalDate()
        return if (unrestricted || SeasonalFertilizing.season(rawDate, hemisphere) in preferredSeasons) {
            rawDueAtMillis
        } else {
            shiftToNearestStretch(rawDate, preferredSeasons, hemisphere, anchorAtMillis)
        }
    }

    private fun shiftToNearestStretch(
        rawDate: LocalDate,
        preferredSeasons: Set<FertilizingSeason>,
        hemisphere: Hemisphere,
        anchorAtMillis: Long
    ): Long {
        val forward = stretchStartAfter(rawDate, preferredSeasons, hemisphere)
        val backward = stretchStartOnOrBefore(rawDate, preferredSeasons, hemisphere)
        val nearest = if (ChronoUnit.DAYS.between(backward, rawDate) < ChronoUnit.DAYS.between(rawDate, forward)) {
            backward
        } else {
            forward
        }
        val candidate = if (nearest.isAfter(anchorAtMillis.toLocalDate())) nearest else forward
        return candidate.toStartOfDayMillis()
    }

    private fun isStretchStart(
        monthStart: LocalDate,
        preferredSeasons: Set<FertilizingSeason>,
        hemisphere: Hemisphere
    ): Boolean = SeasonalFertilizing.season(monthStart, hemisphere) in preferredSeasons &&
        SeasonalFertilizing.season(monthStart.minusMonths(1), hemisphere) !in preferredSeasons

    /** The first stretch start strictly after [date]'s month — bounded, see [MAX_MONTHS_LOOKAHEAD]. */
    private fun stretchStartAfter(
        date: LocalDate,
        preferredSeasons: Set<FertilizingSeason>,
        hemisphere: Hemisphere
    ): LocalDate {
        var monthStart = date.withDayOfMonth(1)
        repeat(MAX_MONTHS_LOOKAHEAD) {
            monthStart = monthStart.plusMonths(1)
            if (isStretchStart(monthStart, preferredSeasons, hemisphere)) return monthStart
        }
        return monthStart
    }

    /** The latest stretch start on or before [date]'s month — bounded, see [MAX_MONTHS_LOOKAHEAD]. */
    private fun stretchStartOnOrBefore(
        date: LocalDate,
        preferredSeasons: Set<FertilizingSeason>,
        hemisphere: Hemisphere
    ): LocalDate {
        var monthStart = date.withDayOfMonth(1)
        repeat(MAX_MONTHS_LOOKAHEAD) {
            if (isStretchStart(monthStart, preferredSeasons, hemisphere)) return monthStart
            monthStart = monthStart.minusMonths(1)
        }
        return monthStart
    }

    /**
     * The picker's choices: the next four seasons after the one [nowDate] is in (the current season is
     * "repot now", not a plan), each with the year of its first day — e.g. on 2026-09-29 in the northern
     * hemisphere, "Winter 2026, Spring 2027, Summer 2027, Autumn 2027".
     */
    fun upcomingSeasons(nowDate: LocalDate, hemisphere: Hemisphere): List<RepotPlanSeason> {
        val seasons = mutableListOf<RepotPlanSeason>()
        var start = nextSeasonStartAfter(nowDate, hemisphere)
        repeat(FertilizingSeason.entries.size) {
            seasons += RepotPlanSeason(
                SeasonalFertilizing.season(start, hemisphere),
                start.year,
                start.toStartOfDayMillis()
            )
            start = nextSeasonStartAfter(start, hemisphere)
        }
        return seasons
    }

    /**
     * Resolves the stored [planSeasonStartAt] into a [RepotPlan], or `null` when there is no plan. The
     * season is the one whose first day is nearest the stored timestamp's local date — nearest rather than
     * "the one containing it", so a timezone change of up to a day between planning and reading can't
     * shrink the season to a single day. [RepotPlan.startAtMillis] stays the stored value untouched.
     */
    fun resolvePlan(planSeasonStartAt: Long?, hemisphere: Hemisphere): RepotPlan? {
        val startAt = planSeasonStartAt ?: return null
        val date = startAt.toLocalDate()
        val floor = seasonStartOnOrBefore(date, hemisphere)
        val next = nextSeasonStartAfter(date, hemisphere)
        val sinceFloor = ChronoUnit.DAYS.between(floor, date)
        val untilNext = ChronoUnit.DAYS.between(date, next)
        val seasonStart = if (sinceFloor <= untilNext) floor else next
        return RepotPlan(
            season = SeasonalFertilizing.season(seasonStart, hemisphere),
            year = seasonStart.year,
            startAtMillis = startAt,
            seasonEndAtMillis = nextSeasonStartAfter(seasonStart, hemisphere).toStartOfDayMillis()
        )
    }

    /**
     * Whether a newly inserted REPOT log dated [repotLoggedAt] clears a plan made at [planMadeAt]: its
     * local calendar day must be on or after the plan-made day (technical ADR-0013), so a repot backdated
     * to before the plan was made leaves it alone (#679-style). A plan with no recorded made-at (only
     * possible from hand-edited data) has nothing to compare against, so a repot clears it.
     */
    fun repotLogClearsPlan(planMadeAt: Long?, repotLoggedAt: Long): Boolean =
        planMadeAt == null || !repotLoggedAt.toLocalDate().isBefore(planMadeAt.toLocalDate())

    private fun seasonStartOnOrBefore(date: LocalDate, hemisphere: Hemisphere): LocalDate {
        var start = date.withDayOfMonth(1)
        val season = SeasonalFertilizing.season(start, hemisphere)
        while (SeasonalFertilizing.season(start.minusMonths(1), hemisphere) == season) start = start.minusMonths(1)
        return start
    }

    private fun nextSeasonStartAfter(date: LocalDate, hemisphere: Hemisphere): LocalDate {
        val season = SeasonalFertilizing.season(date, hemisphere)
        var start = date.withDayOfMonth(1).plusMonths(1)
        while (SeasonalFertilizing.season(start, hemisphere) == season) start = start.plusMonths(1)
        return start
    }
}
