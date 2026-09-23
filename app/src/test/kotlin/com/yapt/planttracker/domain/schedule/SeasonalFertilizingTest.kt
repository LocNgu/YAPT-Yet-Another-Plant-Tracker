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

    @Test
    fun `raw date already inside an active season is left unchanged, millisecond for millisecond`() {
        val raw = utcMillis(2026, 4, 15)
        val result = SeasonalFertilizing.nextActiveDueAtMillis(
            raw,
            setOf(FertilizingSeason.SPRING),
            Hemisphere.NORTHERN
        )
        assertEquals(raw, result)
    }

    @Test
    fun `every season active leaves the raw date unchanged`() {
        val raw = utcMillis(2026, 1, 15)
        val result = SeasonalFertilizing.nextActiveDueAtMillis(
            raw,
            FertilizingSeason.entries.toSet(),
            Hemisphere.NORTHERN
        )
        assertEquals(raw, result)
    }

    @Test
    fun `raw date on the first day of an inactive season shifts to the next active season`() {
        val raw = utcMillis(2026, 6, 1) // First day of northern summer.
        val result = SeasonalFertilizing.nextActiveDueAtMillis(
            raw,
            setOf(FertilizingSeason.AUTUMN),
            Hemisphere.NORTHERN
        )
        assertEquals(utcMillis(2026, 9, 1), result)
    }

    @Test
    fun `raw date on the last day of an inactive season shifts to the next active season`() {
        val raw = utcMillis(2026, 8, 31) // Last day of northern summer.
        val result = SeasonalFertilizing.nextActiveDueAtMillis(
            raw,
            setOf(FertilizingSeason.AUTUMN),
            Hemisphere.NORTHERN
        )
        assertEquals(utcMillis(2026, 9, 1), result)
    }

    @Test
    fun `year wrap shifts a northern spring raw date into autumn`() {
        val raw = utcMillis(2026, 3, 10)
        val result = SeasonalFertilizing.nextActiveDueAtMillis(
            raw,
            setOf(FertilizingSeason.AUTUMN, FertilizingSeason.WINTER),
            Hemisphere.NORTHERN
        )
        assertEquals(utcMillis(2026, 9, 1), result)
    }

    @Test
    fun `southern hemisphere shifts using the opposite season mapping`() {
        // March 10 is southern autumn; only Spring (southern Sep-Nov) is active.
        val raw = utcMillis(2026, 3, 10)
        val result = SeasonalFertilizing.nextActiveDueAtMillis(
            raw,
            setOf(FertilizingSeason.SPRING),
            Hemisphere.SOUTHERN
        )
        assertEquals(utcMillis(2026, 9, 1), result)
    }
}

private fun utcMillis(year: Int, month: Int, day: Int): Long =
    LocalDate.of(year, month, day).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
