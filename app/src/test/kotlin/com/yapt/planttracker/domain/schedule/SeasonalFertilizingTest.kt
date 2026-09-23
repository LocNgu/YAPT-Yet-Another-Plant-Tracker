package com.yapt.planttracker.domain.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

class SeasonalFertilizingTest {

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @Test
    fun `northern season boundaries use the expected discrete slots`() {
        val cases = mapOf(
            LocalDate.of(2026, 2, 28) to FertilizingSeason.WINTER,
            LocalDate.of(2026, 3, 1) to FertilizingSeason.SPRING,
            LocalDate.of(2026, 5, 31) to FertilizingSeason.SPRING,
            LocalDate.of(2026, 6, 1) to FertilizingSeason.SUMMER,
            LocalDate.of(2026, 8, 31) to FertilizingSeason.SUMMER,
            LocalDate.of(2026, 9, 1) to FertilizingSeason.AUTUMN,
            LocalDate.of(2026, 11, 30) to FertilizingSeason.AUTUMN,
            LocalDate.of(2026, 12, 1) to FertilizingSeason.WINTER
        )

        cases.forEach { (date, expected) ->
            assertEquals(expected, SeasonalFertilizing.season(date, Hemisphere.NORTHERN))
        }
    }

    @Test
    fun `southern hemisphere shifts every season by half a year`() {
        val cases = mapOf(
            LocalDate.of(2026, 2, 28) to FertilizingSeason.SUMMER,
            LocalDate.of(2026, 3, 1) to FertilizingSeason.AUTUMN,
            LocalDate.of(2026, 6, 1) to FertilizingSeason.WINTER,
            LocalDate.of(2026, 9, 1) to FertilizingSeason.SPRING,
            LocalDate.of(2026, 12, 1) to FertilizingSeason.SUMMER
        )

        cases.forEach { (date, expected) ->
            assertEquals(expected, SeasonalFertilizing.season(date, Hemisphere.SOUTHERN))
        }
    }

    // --- encode/decode (#795) ---

    @Test
    fun `encode returns null when every season is selected`() {
        assertNull(SeasonalFertilizing.encode(FertilizingSeason.entries.toSet()))
    }

    @Test
    fun `encode writes a subset in canonical enum order regardless of set iteration order`() {
        val seasons = setOf(FertilizingSeason.WINTER, FertilizingSeason.SPRING)
        assertEquals("SPRING,WINTER", SeasonalFertilizing.encode(seasons))
    }

    @Test
    fun `decode null returns every season`() {
        assertEquals(FertilizingSeason.entries.toSet(), SeasonalFertilizing.decode(null))
    }

    @Test
    fun `decode round-trips an encoded subset`() {
        val seasons = setOf(FertilizingSeason.AUTUMN, FertilizingSeason.WINTER)
        assertEquals(seasons, SeasonalFertilizing.decode(SeasonalFertilizing.encode(seasons)))
    }

    @Test
    fun `decode drops unknown tokens but keeps the recognized ones`() {
        assertEquals(setOf(FertilizingSeason.SPRING), SeasonalFertilizing.decode("SPRING,BOGUS"))
    }

    @Test
    fun `decode falls back to every season for an empty or entirely unparseable string`() {
        assertEquals(FertilizingSeason.entries.toSet(), SeasonalFertilizing.decode(""))
        assertEquals(FertilizingSeason.entries.toSet(), SeasonalFertilizing.decode("BOGUS,NONSENSE"))
    }

    // --- nextActiveDueAtMillis: the move-into-an-active-season due-date rule (#795, product ADR-0046) ---
    // Every case below with a raw date that is still in the future relative to `nowDate` exercises the
    // simple forward shift (unchanged if the raw date's own season is active, else shift forward from
    // its own month); `nowDate` is picked before `raw` in those to keep that branch exercised.

    @Test
    fun `future raw date already inside an active season is left unchanged, millisecond for millisecond`() {
        val raw = utcMillis(2026, 4, 15)
        val result = SeasonalFertilizing.nextActiveDueAtMillis(
            raw,
            setOf(FertilizingSeason.SPRING),
            Hemisphere.NORTHERN,
            LocalDate.of(2026, 3, 1)
        )
        assertEquals(raw, result)
    }

    @Test
    fun `every season active leaves a long-overdue raw date unchanged — rule 1 is unconditional`() {
        val raw = utcMillis(2025, 1, 15)
        val result = SeasonalFertilizing.nextActiveDueAtMillis(
            raw,
            FertilizingSeason.entries.toSet(),
            Hemisphere.NORTHERN,
            LocalDate.of(2026, 6, 1) // Over 13 months after raw.
        )
        assertEquals(raw, result)
    }

    @Test
    fun `future raw date on the first day of an inactive season shifts to the next active season`() {
        val raw = utcMillis(2026, 6, 1) // First day of northern summer.
        val result = SeasonalFertilizing.nextActiveDueAtMillis(
            raw,
            setOf(FertilizingSeason.AUTUMN),
            Hemisphere.NORTHERN,
            LocalDate.of(2026, 5, 1)
        )
        assertEquals(utcMillis(2026, 9, 1), result)
    }

    @Test
    fun `future raw date on the last day of an inactive season shifts to the next active season`() {
        val raw = utcMillis(2026, 8, 31) // Last day of northern summer.
        val result = SeasonalFertilizing.nextActiveDueAtMillis(
            raw,
            setOf(FertilizingSeason.AUTUMN),
            Hemisphere.NORTHERN,
            LocalDate.of(2026, 8, 1)
        )
        assertEquals(utcMillis(2026, 9, 1), result)
    }

    @Test
    fun `future raw date year wrap shifts a northern spring raw date into autumn`() {
        val raw = utcMillis(2026, 3, 10)
        val result = SeasonalFertilizing.nextActiveDueAtMillis(
            raw,
            setOf(FertilizingSeason.AUTUMN, FertilizingSeason.WINTER),
            Hemisphere.NORTHERN,
            LocalDate.of(2026, 1, 1)
        )
        assertEquals(utcMillis(2026, 9, 1), result)
    }

    @Test
    fun `future raw date southern hemisphere shifts using the opposite season mapping`() {
        // March 10 is southern autumn; only Spring (southern Sep-Nov) is active.
        val raw = utcMillis(2026, 3, 10)
        val result = SeasonalFertilizing.nextActiveDueAtMillis(
            raw,
            setOf(FertilizingSeason.SPRING),
            Hemisphere.SOUTHERN,
            LocalDate.of(2026, 1, 1)
        )
        assertEquals(utcMillis(2026, 9, 1), result)
    }

    // --- Fix-round (#795): a past-or-present raw date must be evaluated against today's season, not
    // its own — the raw date's own season can be active while a long inactive gap has since opened up.

    @Test
    fun `past raw date during an inactive current season shifts forward to the next active month`() {
        // Spring+Summer active; last fertilized in August (Summer, active) but never again — querying
        // in October (Autumn, inactive) must not read the plant as overdue since that August date.
        val raw = utcMillis(2026, 8, 27)
        val activeSeasons = setOf(FertilizingSeason.SPRING, FertilizingSeason.SUMMER)
        val result = SeasonalFertilizing.nextActiveDueAtMillis(
            raw,
            activeSeasons,
            Hemisphere.NORTHERN,
            LocalDate.of(2026, 10, 1)
        )
        assertEquals(utcMillis(2027, 3, 1), result)
    }

    @Test
    fun `past raw date before the active run start returns the run start regardless of the day within it`() {
        val raw = utcMillis(2026, 8, 27)
        val activeSeasons = setOf(FertilizingSeason.SPRING, FertilizingSeason.SUMMER)
        val runStart = utcMillis(2027, 3, 1)

        assertEquals(
            runStart,
            SeasonalFertilizing.nextActiveDueAtMillis(raw, activeSeasons, Hemisphere.NORTHERN, LocalDate.of(2027, 3, 1))
        )
        assertEquals(
            runStart,
            SeasonalFertilizing.nextActiveDueAtMillis(raw, activeSeasons, Hemisphere.NORTHERN, LocalDate.of(2027, 3, 5))
        )
    }

    @Test
    fun `past raw date already inside the current active run is left unchanged, still overdue`() {
        val raw = utcMillis(2026, 7, 5)
        val activeSeasons = setOf(FertilizingSeason.SUMMER)
        val result = SeasonalFertilizing.nextActiveDueAtMillis(
            raw,
            activeSeasons,
            Hemisphere.NORTHERN,
            LocalDate.of(2026, 7, 20)
        )
        assertEquals(raw, result)
    }

    @Test
    fun `southern hemisphere variant of the overdue-across-an-inactive-season fix`() {
        // Southern Autumn+Winter = Mar-Aug; raw lands in southern Winter (Aug, active), then the gap
        // opens into southern Spring (Sep-Nov), which is inactive.
        val raw = utcMillis(2026, 8, 27)
        val activeSeasons = setOf(FertilizingSeason.AUTUMN, FertilizingSeason.WINTER)
        val result = SeasonalFertilizing.nextActiveDueAtMillis(
            raw,
            activeSeasons,
            Hemisphere.SOUTHERN,
            LocalDate.of(2026, 11, 1)
        )
        assertEquals(utcMillis(2027, 3, 1), result)
    }

    @Test
    fun `year-wrapping active run correctly identifies its own start`() {
        // Autumn+Winter active (north) bridges Sep through Feb, wrapping the year boundary.
        val activeSeasons = setOf(FertilizingSeason.AUTUMN, FertilizingSeason.WINTER)
        val nowDate = LocalDate.of(2027, 1, 10)
        val runStart = utcMillis(2026, 9, 1)

        val rawInsideRun = utcMillis(2026, 10, 1)
        assertEquals(
            rawInsideRun,
            SeasonalFertilizing.nextActiveDueAtMillis(rawInsideRun, activeSeasons, Hemisphere.NORTHERN, nowDate)
        )

        val rawBeforeRun = utcMillis(2026, 8, 1)
        assertEquals(
            runStart,
            SeasonalFertilizing.nextActiveDueAtMillis(rawBeforeRun, activeSeasons, Hemisphere.NORTHERN, nowDate)
        )
    }
}

private fun utcMillis(year: Int, month: Int, day: Int): Long =
    LocalDate.of(year, month, day).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
