package com.yapt.planttracker.domain.schedule

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

class SeasonalRepottingTest {

    private val originalTimeZone: TimeZone = TimeZone.getDefault()

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
    }

    private val spring = setOf(FertilizingSeason.SPRING)
    private val north = Hemisphere.NORTHERN

    private fun day(year: Int, month: Int, dayOfMonth: Int): LocalDate = LocalDate.of(year, month, dayOfMonth)

    private fun startOfDay(date: LocalDate): Long = date.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()

    private fun noon(date: LocalDate): Long = date.atTime(12, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()

    private fun shifted(
        raw: LocalDate,
        preferred: Set<FertilizingSeason>,
        anchor: LocalDate,
        hemisphere: Hemisphere = north
    ): Long = SeasonalRepotting.nextPreferredDueAtMillis(noon(raw), preferred, hemisphere, noon(anchor))

    // --- nextPreferredDueAtMillis: guards and early-outs ---

    @Test
    fun `every season preferred is an unconditional early-out, however overdue`() {
        val raw = noon(day(2020, 1, 15))
        val result = SeasonalRepotting.nextPreferredDueAtMillis(
            rawDueAtMillis = raw,
            preferredSeasons = FertilizingSeason.entries.toSet(),
            hemisphere = north,
            anchorAtMillis = noon(day(2019, 1, 15))
        )
        assertEquals(raw, result)
    }

    @Test
    fun `an empty preferred set is treated as unrestricted`() {
        val raw = noon(day(2027, 1, 15))
        val result = SeasonalRepotting.nextPreferredDueAtMillis(
            rawDueAtMillis = raw,
            preferredSeasons = emptySet(),
            hemisphere = north,
            anchorAtMillis = noon(day(2025, 1, 15))
        )
        assertEquals(raw, result)
    }

    @Test
    fun `a raw date already inside a preferred season is unchanged`() {
        val raw = day(2027, 4, 20)
        assertEquals(noon(raw), shifted(raw, spring, anchor = day(2025, 4, 20)))
    }

    @Test
    fun `a past raw date inside a preferred season stays overdue, never moved`() {
        val raw = day(2026, 4, 5)
        assertEquals(noon(raw), shifted(raw, spring, anchor = day(2024, 4, 5)))
    }

    // --- nearest preferred stretch (raw dates outside a preferred season) ---

    @Test
    fun `the issue example snaps to the nearest spring, March 2027, not March 2028`() {
        val anchor = day(2025, 6, 15)
        val raw = anchor.plusDays(730)
        assertEquals(day(2027, 6, 15), raw)
        assertEquals(startOfDay(day(2027, 3, 1)), shifted(raw, spring, anchor = anchor))
    }

    @Test
    fun `a raw date closer to the next stretch snaps forward`() {
        val result = shifted(day(2026, 12, 20), spring, anchor = day(2025, 12, 20))
        assertEquals(startOfDay(day(2027, 3, 1)), result)
    }

    @Test
    fun `an equidistant raw date goes to the later stretch`() {
        // 2027-03-01 to 2027-08-31 and 2027-08-31 to 2028-03-01 are both 183 days.
        val result = shifted(day(2027, 8, 31), spring, anchor = day(2025, 8, 31))
        assertEquals(startOfDay(day(2028, 3, 1)), result)
    }

    @Test
    fun `an equidistant raw date between two stretches goes to the later one`() {
        // Spring + autumn preferred: Mar 1 to Jun 1 and Jun 1 to Sep 1 are both 92 days.
        val both = setOf(FertilizingSeason.SPRING, FertilizingSeason.AUTUMN)
        val result = shifted(day(2027, 6, 1), both, anchor = day(2025, 6, 1))
        assertEquals(startOfDay(day(2027, 9, 1)), result)
    }

    @Test
    fun `non-adjacent preferred seasons pick the nearest of their stretch starts`() {
        val both = setOf(FertilizingSeason.SPRING, FertilizingSeason.AUTUMN)
        val summer = shifted(day(2027, 7, 15), both, anchor = day(2025, 7, 15))
        val winter = shifted(day(2027, 1, 20), both, anchor = day(2025, 1, 20))
        assertEquals(startOfDay(day(2027, 9, 1)), summer)
        assertEquals(startOfDay(day(2027, 3, 1)), winter)
    }

    @Test
    fun `a winter-only preference snaps back across the year boundary to December 1`() {
        val winter = setOf(FertilizingSeason.WINTER)
        val result = shifted(day(2027, 4, 15), winter, anchor = day(2025, 4, 15))
        assertEquals(startOfDay(day(2026, 12, 1)), result)
    }

    @Test
    fun `a raw date inside a stretch that wraps the year boundary is unchanged`() {
        val autumnWinter = setOf(FertilizingSeason.AUTUMN, FertilizingSeason.WINTER)
        val raw = day(2027, 1, 15)
        assertEquals(noon(raw), shifted(raw, autumnWinter, anchor = day(2025, 1, 15)))
    }

    @Test
    fun `a stretch spanning the year boundary is measured to its first day, September 1`() {
        val autumnWinter = setOf(FertilizingSeason.AUTUMN, FertilizingSeason.WINTER)
        val result = shifted(day(2027, 5, 10), autumnWinter, anchor = day(2025, 5, 10))
        assertEquals(startOfDay(day(2027, 9, 1)), result)
    }

    @Test
    fun `southern hemisphere spring is September to November`() {
        val result = shifted(
            raw = day(2027, 6, 15),
            preferred = spring,
            anchor = day(2025, 6, 15),
            hemisphere = Hemisphere.SOUTHERN
        )
        assertEquals(startOfDay(day(2027, 9, 1)), result)
    }

    // --- guards ---

    @Test
    fun `a nearest candidate on or before the last repot falls back to the next stretch forward`() {
        // Repotted Apr 10 2026 with a 91-day interval: raw Jul 10 2026. The nearest spring start is
        // Mar 1 2026, before the repot itself, so the next spring is used.
        val anchor = day(2026, 4, 10)
        val result = shifted(anchor.plusDays(91), spring, anchor = anchor)
        assertEquals(startOfDay(day(2027, 3, 1)), result)
    }

    @Test
    fun `a nearest candidate on the repot day itself also falls back forward`() {
        val anchor = day(2026, 3, 1)
        val result = shifted(day(2026, 7, 10), spring, anchor = anchor)
        assertEquals(startOfDay(day(2027, 3, 1)), result)
    }

    @Test
    fun `a nearest stretch start before the raw date is kept, so the date reads overdue not due-today`() {
        // Raw Jul 10 2026 is outside spring; the nearest spring start is Mar 1 2026, which precedes it.
        // The shift doesn't push that forward — a date in the past is simply the overdue state.
        val result = shifted(day(2026, 7, 10), spring, anchor = day(2024, 7, 10))
        assertEquals(startOfDay(day(2026, 3, 1)), result)
    }

    @Test
    fun `a raw date just before a preferred season snaps forward to it`() {
        val result = shifted(day(2026, 2, 10), spring, anchor = day(2024, 2, 10))
        assertEquals(startOfDay(day(2026, 3, 1)), result)
    }

    @Test
    fun `a raw date after a preferred season snaps back to it, not forward to the next year`() {
        val result = shifted(day(2026, 7, 1), spring, anchor = day(2024, 7, 1))
        assertEquals(startOfDay(day(2026, 3, 1)), result)
    }

    @Test
    fun `a raw date in autumn is nearer next spring than the one just gone`() {
        val result = shifted(day(2026, 10, 5), spring, anchor = day(2024, 10, 5))
        assertEquals(startOfDay(day(2027, 3, 1)), result)
    }

    @Test
    fun `an old anchor with a nearest candidate at or before it still falls forward`() {
        // Repotted Apr 10 2024, raw Jul 10 2024: the nearest spring start (Mar 1 2024) precedes the repot,
        // so the next spring (Mar 1 2025) is used even though that is long past by now.
        val anchor = day(2024, 4, 10)
        val result = shifted(anchor.plusDays(91), spring, anchor = anchor)
        assertEquals(startOfDay(day(2025, 3, 1)), result)
    }

    // --- minimum gap: a candidate must fall at least half the interval after the anchor ---

    @Test
    fun `a plant repotted just before a preferred stretch waits for the next one, not 45 days later`() {
        // Repotted Jan 15 2026, 180 days: raw Jul 14 2026. The nearest spring start is Mar 1 2026, only 45
        // days after the repot (< 90), so the next spring is used.
        val anchor = day(2026, 1, 15)
        val raw = anchor.plusDays(180)
        assertEquals(day(2026, 7, 14), raw)
        assertEquals(startOfDay(day(2027, 3, 1)), shifted(raw, spring, anchor = anchor))
    }

    @Test
    fun `the minimum-gap example mirrors in the southern hemisphere`() {
        // Southern spring starts Sep 1: repotted Jul 15 2026 (48 days before it), raw Jan 11 2027.
        val anchor = day(2026, 7, 15)
        val raw = anchor.plusDays(180)
        assertEquals(day(2027, 1, 11), raw)
        val result = shifted(raw, spring, anchor = anchor, hemisphere = Hemisphere.SOUTHERN)
        assertEquals(startOfDay(day(2027, 9, 1)), result)
    }

    @Test
    fun `a candidate exactly half the interval after the anchor is accepted`() {
        // Anchor Nov 21 2025, 200 days: raw Jun 9 2026; Mar 1 2026 is exactly 100 days after the anchor.
        val anchor = day(2025, 11, 21)
        val raw = anchor.plusDays(200)
        assertEquals(day(2026, 6, 9), raw)
        assertEquals(startOfDay(day(2026, 3, 1)), shifted(raw, spring, anchor = anchor))
    }

    @Test
    fun `a candidate one day short of half the interval after the anchor is rejected`() {
        // One day later: Mar 1 2026 is now 99 days after the anchor, short of 200 / 2 = 100.
        val anchor = day(2025, 11, 22)
        val raw = anchor.plusDays(200)
        assertEquals(day(2026, 6, 10), raw)
        assertEquals(startOfDay(day(2027, 3, 1)), shifted(raw, spring, anchor = anchor))
    }

    @Test
    fun `half the interval uses integer division, so an odd interval rounds the minimum down`() {
        // 201 days: minimum 100, and Mar 1 2026 is exactly 100 days after the anchor.
        val anchor = day(2025, 11, 21)
        val raw = anchor.plusDays(201)
        assertEquals(startOfDay(day(2026, 3, 1)), shifted(raw, spring, anchor = anchor))
    }

    @Test
    fun `a rejected candidate in a two-stretch year falls forward to the next stretch`() {
        // Summer + Winter preferred (stretch starts Jun 1 and Dec 1). Anchor Oct 1 2026, 151 days: raw
        // Mar 1 2027. The nearest start is Dec 1 2026, only 61 days after the repot (< 75), so it falls
        // forward to Jun 1 2027 — the only other candidate, which is always far enough.
        val summerWinter = setOf(FertilizingSeason.SUMMER, FertilizingSeason.WINTER)
        val anchor = day(2026, 10, 1)
        val raw = anchor.plusDays(151)
        assertEquals(day(2027, 3, 1), raw)
        assertEquals(startOfDay(day(2027, 6, 1)), shifted(raw, summerWinter, anchor = anchor))
    }

    @Test
    fun `a nearest candidate comfortably past half the interval is kept in a two-stretch year`() {
        val both = setOf(FertilizingSeason.SPRING, FertilizingSeason.AUTUMN)
        val anchor = day(2026, 1, 15)
        assertEquals(startOfDay(day(2026, 9, 1)), shifted(anchor.plusDays(180), both, anchor = anchor))
    }

    @Test
    fun `a year-wrapping stretch rejected for being too close falls forward a full year`() {
        // Autumn + Winter preferred (stretch start Sep 1). Anchor Jul 1 2026, 244 days: raw Mar 2 2027,
        // nearest Sep 1 2026 (182 days back vs 183 forward) is only 62 days after the repot (< 122).
        val autumnWinter = setOf(FertilizingSeason.AUTUMN, FertilizingSeason.WINTER)
        val anchor = day(2026, 7, 1)
        val raw = anchor.plusDays(244)
        assertEquals(day(2027, 3, 2), raw)
        assertEquals(startOfDay(day(2027, 9, 1)), shifted(raw, autumnWinter, anchor = anchor))
    }

    @Test
    fun `the minimum gap never moves a raw date that is already in a preferred season`() {
        // Repotted Jan 15, raw Mar 16 is in spring: unchanged even though it is only 60 days after the repot.
        val anchor = day(2026, 1, 15)
        val raw = anchor.plusDays(60)
        assertEquals(noon(raw), shifted(raw, spring, anchor = anchor))
    }

    // --- upcomingSeasons ---

    @Test
    fun `upcoming seasons after autumn 2026 in the north`() {
        val seasons = SeasonalRepotting.upcomingSeasons(day(2026, 9, 29), north)
        assertEquals(
            listOf(
                RepotPlanSeason(FertilizingSeason.WINTER, 2026, startOfDay(day(2026, 12, 1))),
                RepotPlanSeason(FertilizingSeason.SPRING, 2027, startOfDay(day(2027, 3, 1))),
                RepotPlanSeason(FertilizingSeason.SUMMER, 2027, startOfDay(day(2027, 6, 1))),
                RepotPlanSeason(FertilizingSeason.AUTUMN, 2027, startOfDay(day(2027, 9, 1)))
            ),
            seasons
        )
    }

    @Test
    fun `upcoming seasons in mid-winter start with the coming spring and end with next winter`() {
        val seasons = SeasonalRepotting.upcomingSeasons(day(2027, 1, 10), north)
        assertEquals(
            listOf(
                FertilizingSeason.SPRING,
                FertilizingSeason.SUMMER,
                FertilizingSeason.AUTUMN,
                FertilizingSeason.WINTER
            ),
            seasons.map { it.season }
        )
        assertEquals(listOf(2027, 2027, 2027, 2027), seasons.map { it.year })
        assertEquals(startOfDay(day(2027, 12, 1)), seasons.last().startAtMillis)
    }

    @Test
    fun `on a season's first day that season is current and the next one is offered first`() {
        val seasons = SeasonalRepotting.upcomingSeasons(day(2027, 3, 1), north)
        assertEquals(FertilizingSeason.SUMMER, seasons.first().season)
        assertEquals(startOfDay(day(2027, 6, 1)), seasons.first().startAtMillis)
    }

    @Test
    fun `on the last day of a season the next season is offered first`() {
        val seasons = SeasonalRepotting.upcomingSeasons(day(2027, 2, 28), north)
        assertEquals(FertilizingSeason.SPRING, seasons.first().season)
        assertEquals(startOfDay(day(2027, 3, 1)), seasons.first().startAtMillis)
    }

    @Test
    fun `upcoming seasons follow the southern hemisphere`() {
        val seasons = SeasonalRepotting.upcomingSeasons(day(2026, 9, 29), Hemisphere.SOUTHERN)
        assertEquals(
            listOf(
                FertilizingSeason.SUMMER,
                FertilizingSeason.AUTUMN,
                FertilizingSeason.WINTER,
                FertilizingSeason.SPRING
            ),
            seasons.map { it.season }
        )
        assertEquals(listOf(2026, 2027, 2027, 2027), seasons.map { it.year })
    }

    // --- resolvePlan / RepotPlan.stateOn ---

    @Test
    fun `no stored plan resolves to null`() {
        assertNull(SeasonalRepotting.resolvePlan(null, north))
    }

    @Test
    fun `a spring plan resolves with its season, year and the day after the season as its end`() {
        val stored = startOfDay(day(2027, 3, 1))
        val plan = SeasonalRepotting.resolvePlan(stored, north)!!
        assertEquals(FertilizingSeason.SPRING, plan.season)
        assertEquals(2027, plan.year)
        assertEquals(stored, plan.startAtMillis)
        assertEquals(startOfDay(day(2027, 6, 1)), plan.seasonEndAtMillis)
    }

    @Test
    fun `a winter plan wraps the year boundary and is labelled with the year of its first day`() {
        val plan = SeasonalRepotting.resolvePlan(startOfDay(day(2026, 12, 1)), north)!!
        assertEquals(FertilizingSeason.WINTER, plan.season)
        assertEquals(2026, plan.year)
        assertEquals(startOfDay(day(2027, 3, 1)), plan.seasonEndAtMillis)
    }

    @Test
    fun `a hemisphere change relabels the plan but never moves its due date or season end`() {
        val stored = startOfDay(day(2027, 3, 1))
        val northPlan = SeasonalRepotting.resolvePlan(stored, Hemisphere.NORTHERN)!!
        val southPlan = SeasonalRepotting.resolvePlan(stored, Hemisphere.SOUTHERN)!!
        assertEquals(FertilizingSeason.SPRING, northPlan.season)
        assertEquals(FertilizingSeason.AUTUMN, southPlan.season)
        assertEquals(northPlan.startAtMillis, southPlan.startAtMillis)
        assertEquals(northPlan.seasonEndAtMillis, southPlan.seasonEndAtMillis)
    }

    @Test
    fun `a stored start a day early from a timezone shift still resolves to the intended season`() {
        val plan = SeasonalRepotting.resolvePlan(startOfDay(day(2027, 2, 28)), north)!!
        assertEquals(FertilizingSeason.SPRING, plan.season)
        assertEquals(2027, plan.year)
        assertEquals(startOfDay(day(2027, 6, 1)), plan.seasonEndAtMillis)
    }

    @Test
    fun `plan state is upcoming before the season, in season through its last day, ended the day after`() {
        val plan = SeasonalRepotting.resolvePlan(startOfDay(day(2027, 3, 1)), north)!!
        assertEquals(RepotPlanState.UPCOMING, plan.stateOn(day(2027, 2, 28)))
        assertEquals(RepotPlanState.IN_SEASON, plan.stateOn(day(2027, 3, 1)))
        assertEquals(RepotPlanState.IN_SEASON, plan.stateOn(day(2027, 5, 31)))
        assertEquals(RepotPlanState.SEASON_ENDED, plan.stateOn(day(2027, 6, 1)))
        assertEquals(RepotPlanState.SEASON_ENDED, plan.stateOn(day(2028, 1, 1)))
    }

    // --- repotLogClearsPlan ---

    @Test
    fun `a repot on the plan-made day clears the plan, even earlier in that day`() {
        val madeAt = day(2026, 9, 29).atTime(15, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val loggedAt = day(2026, 9, 29).atTime(8, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertTrue(SeasonalRepotting.repotLogClearsPlan(madeAt, loggedAt))
    }

    @Test
    fun `a repot after the plan-made day clears the plan`() {
        assertTrue(SeasonalRepotting.repotLogClearsPlan(noon(day(2026, 9, 29)), noon(day(2026, 10, 30))))
    }

    @Test
    fun `a repot backdated to before the plan-made day does not clear the plan`() {
        assertFalse(SeasonalRepotting.repotLogClearsPlan(noon(day(2026, 9, 29)), noon(day(2026, 9, 28))))
    }

    @Test
    fun `the plan-made comparison uses the local calendar day, not the instant`() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        // 2026-06-10T03:00Z is Jun 9 23:00 in New York; 2026-06-09T20:00Z is Jun 9 16:00 there —
        // an earlier instant on the same local day, so it clears.
        val madeAt = LocalDate.of(2026, 6, 10).atTime(3, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val loggedAt = LocalDate.of(2026, 6, 9).atTime(20, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertTrue(SeasonalRepotting.repotLogClearsPlan(madeAt, loggedAt))
    }

    @Test
    fun `a plan with no recorded made-at is cleared by any repot`() {
        assertTrue(SeasonalRepotting.repotLogClearsPlan(null, noon(day(2020, 1, 1))))
    }
}
