package com.yapt.planttracker.domain.schedule

import com.yapt.planttracker.util.toLocalDate
import com.yapt.planttracker.util.toStartOfDayMillis
import java.time.LocalDate

/** Which quarter of the year fertilizing can be active in (#286, product ADR-0045/product ADR-0046). */
enum class FertilizingSeason {
    SPRING,
    SUMMER,
    AUTUMN,
    WINTER
}

/**
 * Hemisphere-aware season selection, active-season storage encoding, and the move-into-an-active-
 * season due-date rule for fertilizing (#795, product ADR-0046 — replacing #286's four discrete
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
     * [rawDueAtMillis] (`lastFertilizedAt + interval`, or the first-fertilize grace date) unchanged
     * when its own calendar day already falls in an [activeSeasons] month; otherwise shifted forward
     * to the start of day (system default zone) of the 1st of the first following month whose season
     * is active (#795, product ADR-0046). A plant is therefore never due or overdue during an inactive
     * season — on re-entry it is due on the first day of the active season, not overdue from whenever
     * the raw date fell.
     */
    @Suppress("ReturnCount")
    fun nextActiveDueAtMillis(
        rawDueAtMillis: Long,
        activeSeasons: Set<FertilizingSeason>,
        hemisphere: Hemisphere
    ): Long {
        val rawDate = rawDueAtMillis.toLocalDate()
        if (season(rawDate, hemisphere) in activeSeasons) return rawDueAtMillis
        var candidate = rawDate.withDayOfMonth(1).plusMonths(1)
        repeat(MAX_MONTHS_LOOKAHEAD) {
            if (season(candidate, hemisphere) in activeSeasons) {
                return candidate.toStartOfDayMillis()
            }
            candidate = candidate.plusMonths(1)
        }
        return candidate.toStartOfDayMillis()
    }

    private fun FertilizingSeason.opposite(): FertilizingSeason = when (this) {
        FertilizingSeason.SPRING -> FertilizingSeason.AUTUMN
        FertilizingSeason.SUMMER -> FertilizingSeason.WINTER
        FertilizingSeason.AUTUMN -> FertilizingSeason.SPRING
        FertilizingSeason.WINTER -> FertilizingSeason.SUMMER
    }
}
