package com.yapt.planttracker.domain.schedule

import com.yapt.planttracker.util.toLocalDate
import com.yapt.planttracker.util.toStartOfDayMillis
import java.time.LocalDate

/** Which quarter of the year fertilizing can be active in (#286, product ADR-0045/product ADR-0049). */
enum class FertilizingSeason {
    SPRING,
    SUMMER,
    AUTUMN,
    WINTER
}

/**
 * Hemisphere-aware season selection, active-season storage encoding, and the move-into-an-active-
 * season due-date rule for fertilizing (#795, product ADR-0049 — replacing #286's four discrete
 * per-season intervals, product ADR-0045).
 */
object SeasonalFertilizing {

    private const val SPRING_START_MONTH = 3
    private const val SPRING_END_MONTH = 5
    private const val SUMMER_START_MONTH = 6
    private const val SUMMER_END_MONTH = 8
    private const val AUTUMN_START_MONTH = 9
    private const val AUTUMN_END_MONTH = 11

    /** Guaranteed to terminate: [FertilizingSeason] has 4 members and a decoded set is never empty. */
    private const val MAX_MONTHS_LOOKAHEAD = 12

    fun season(date: LocalDate, hemisphere: Hemisphere): FertilizingSeason {
        val northernSeason = when (date.monthValue) {
            in SPRING_START_MONTH..SPRING_END_MONTH -> FertilizingSeason.SPRING
            in SUMMER_START_MONTH..SUMMER_END_MONTH -> FertilizingSeason.SUMMER
            in AUTUMN_START_MONTH..AUTUMN_END_MONTH -> FertilizingSeason.AUTUMN
            else -> FertilizingSeason.WINTER
        }
        return if (hemisphere == Hemisphere.NORTHERN) northernSeason else northernSeason.opposite()
    }

    /**
     * Comma-separated [FertilizingSeason] names in canonical enum order (#795). All four seasons
     * selected is written as `null` — the default state has exactly one storage form, matching every
     * existing plant.
     */
    fun encode(seasons: Set<FertilizingSeason>): String? {
        if (seasons.containsAll(FertilizingSeason.entries)) return null
        return FertilizingSeason.entries.filter { it in seasons }.joinToString(",") { it.name }
    }

    /**
     * The inverse of [encode]. `null` (never configured, or all four explicitly chosen) decodes to
     * every season. Unknown tokens are dropped (`runCatching`, per the "enums stored as String"
     * convention); an empty or entirely unparseable result falls back to every season rather than an
     * empty, permanently-paused set.
     */
    fun decode(raw: String?): Set<FertilizingSeason> {
        if (raw == null) return FertilizingSeason.entries.toSet()
        val parsed = raw.split(",")
            .mapNotNull { token -> runCatching { FertilizingSeason.valueOf(token.trim()) }.getOrNull() }
            .toSet()
        return parsed.ifEmpty { FertilizingSeason.entries.toSet() }
    }

    /**
     * [rawDueAtMillis] (`lastFertilizedAt + interval`, or the first-fertilize grace date), moved out
     * of an inactive season (#795, product ADR-0049). Every season selected is an unconditional
     * early-out: every existing plant stays bit-for-bit unchanged, however overdue.
     *
     * A **future** raw date (after [nowDate]) uses the simple forward shift: unchanged if its own
     * season is active, else the start of day of the 1st of the first following month whose season
     * is active.
     *
     * A raw date **on or before** [nowDate] cannot use that same rule alone — the raw date's own
     * season can be active while a long inactive gap has since opened up between it and today (e.g.
     * Spring+Summer active, last fertilized in August: the raw date lands in Summer and would
     * otherwise be returned unchanged, but the plant would then read overdue for the entire Sep–Feb
     * inactive stretch instead of "not due"). Past-or-present raw dates are instead evaluated against
     * *today's* season:
     * - [nowDate]'s season inactive → the start of day of the 1st of the first following month whose
     *   season is active (a future date — never due or overdue right now).
     * - [nowDate]'s season active → find [activeSeasons]-run's start: the 1st of the earliest month
     *   in the unbroken run of active months ending at (and including) [nowDate]'s month. The raw
     *   date is unchanged if it already falls on or after that run's start (still overdue from the
     *   real raw date, same as today); otherwise the run's start is the due date — due today if
     *   [nowDate] is inside that first month, overdue if later in it.
     */
    fun nextActiveDueAtMillis(
        rawDueAtMillis: Long,
        activeSeasons: Set<FertilizingSeason>,
        hemisphere: Hemisphere,
        nowDate: LocalDate
    ): Long {
        if (activeSeasons.containsAll(FertilizingSeason.entries)) return rawDueAtMillis
        val rawDate = rawDueAtMillis.toLocalDate()
        return if (rawDate.isAfter(nowDate)) {
            shiftFutureRawDate(rawDueAtMillis, rawDate, activeSeasons, hemisphere)
        } else {
            dueDateForPastOrPresentRaw(rawDueAtMillis, rawDate, nowDate, activeSeasons, hemisphere)
        }
    }

    private fun shiftFutureRawDate(
        rawDueAtMillis: Long,
        rawDate: LocalDate,
        activeSeasons: Set<FertilizingSeason>,
        hemisphere: Hemisphere
    ): Long {
        if (season(rawDate, hemisphere) in activeSeasons) return rawDueAtMillis
        return firstActiveMonthStartAtOrAfter(rawDate.withDayOfMonth(1).plusMonths(1), activeSeasons, hemisphere)
    }

    private fun dueDateForPastOrPresentRaw(
        rawDueAtMillis: Long,
        rawDate: LocalDate,
        nowDate: LocalDate,
        activeSeasons: Set<FertilizingSeason>,
        hemisphere: Hemisphere
    ): Long {
        if (season(nowDate, hemisphere) !in activeSeasons) {
            return firstActiveMonthStartAtOrAfter(nowDate.withDayOfMonth(1).plusMonths(1), activeSeasons, hemisphere)
        }
        val runStart = activeRunStart(nowDate, activeSeasons, hemisphere)
        return if (!rawDate.isBefore(runStart)) rawDueAtMillis else runStart.toStartOfDayMillis()
    }

    /**
     * The earliest month-start (as epoch millis, at or after [candidateMonthStart], itself already
     * the 1st of some month) whose season is active — the shared forward search both the future-raw
     * shift and the "today's season is inactive" branch use, bounded to [MAX_MONTHS_LOOKAHEAD] months.
     */
    private fun firstActiveMonthStartAtOrAfter(
        candidateMonthStart: LocalDate,
        activeSeasons: Set<FertilizingSeason>,
        hemisphere: Hemisphere
    ): Long {
        var candidate = candidateMonthStart
        repeat(MAX_MONTHS_LOOKAHEAD) {
            if (season(candidate, hemisphere) in activeSeasons) return candidate.toStartOfDayMillis()
            candidate = candidate.plusMonths(1)
        }
        return candidate.toStartOfDayMillis()
    }

    /**
     * The 1st of the earliest month in the contiguous run of [activeSeasons] months ending at (and
     * including) [monthOf]'s own month — the caller has already established [monthOf]'s season is
     * active. Walks backward one month at a time while the previous month's season is still active;
     * bounded to [MAX_MONTHS_LOOKAHEAD] steps, though it always terminates well before that since at
     * least one season is inactive at this point (an all-active [activeSeasons] never reaches here).
     */
    private fun activeRunStart(
        monthOf: LocalDate,
        activeSeasons: Set<FertilizingSeason>,
        hemisphere: Hemisphere
    ): LocalDate {
        var runStart = monthOf.withDayOfMonth(1)
        repeat(MAX_MONTHS_LOOKAHEAD) {
            val previousMonth = runStart.minusMonths(1)
            if (season(previousMonth, hemisphere) !in activeSeasons) return runStart
            runStart = previousMonth
        }
        return runStart
    }

    private fun FertilizingSeason.opposite(): FertilizingSeason = when (this) {
        FertilizingSeason.SPRING -> FertilizingSeason.AUTUMN
        FertilizingSeason.SUMMER -> FertilizingSeason.WINTER
        FertilizingSeason.AUTUMN -> FertilizingSeason.SPRING
        FertilizingSeason.WINTER -> FertilizingSeason.SUMMER
    }
}
