package com.yapt.planttracker.domain.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

class SeasonalWateringTest {

    @Test
    fun `amplitude 0 collapses season to a flat 1_0 every day of the year`() {
        val dates = listOf(
            LocalDate.of(2024, 1, 1),
            LocalDate.of(2024, 4, 15),
            LocalDate.of(2024, 7, 1),
            LocalDate.of(2024, 12, 31)
        )
        for (date in dates) {
            assertEquals(1.0, SeasonalWatering.season(date, 0.0, Hemisphere.NORTHERN), 1e-9)
            assertEquals(1.0, SeasonalWatering.season(date, 0.0, Hemisphere.SOUTHERN), 1e-9)
        }
    }

    @Test
    fun `amplitude 0_5 bounds season between 0_5 and 1_5`() {
        var min = Double.MAX_VALUE
        var max = Double.MIN_VALUE
        for (day in 1..365) {
            val date = LocalDate.of(2023, 1, 1).plusDays((day - 1).toLong())
            val season = SeasonalWatering.season(date, 0.5, Hemisphere.NORTHERN)
            min = minOf(min, season)
            max = maxOf(max, season)
        }
        assertTrue("min=$min should be >= 0.5", min >= 0.5 - 1e-6)
        assertTrue("max=$max should be <= 1.5", max <= 1.5 + 1e-6)
        // Near the peak (day ~5) season should approach the amplitude's max.
        assertTrue(SeasonalWatering.season(LocalDate.of(2023, 1, 5), 0.5, Hemisphere.NORTHERN) > 1.49)
    }

    @Test
    fun `season peaks near day 5 for northern hemisphere`() {
        val peakDate = LocalDate.of(2023, 1, 5)
        val seasonAtPeak = SeasonalWatering.season(peakDate, 0.35, Hemisphere.NORTHERN)
        val seasonAMonthLater = SeasonalWatering.season(peakDate.plusDays(30), 0.35, Hemisphere.NORTHERN)
        val seasonAMonthEarlier = SeasonalWatering.season(peakDate.minusDays(30), 0.35, Hemisphere.NORTHERN)
        assertTrue(seasonAtPeak > seasonAMonthLater)
        assertTrue(seasonAtPeak > seasonAMonthEarlier)
    }

    @Test
    fun `hemisphere flip shifts the peak by roughly half a year`() {
        val date = LocalDate.of(2023, 1, 5)
        val northernAtDate = SeasonalWatering.season(date, 0.35, Hemisphere.NORTHERN)
        val southernAtDate = SeasonalWatering.season(date, 0.35, Hemisphere.SOUTHERN)
        // Northern peaks near Jan 5; southern should be near its trough at the same calendar date.
        assertTrue(northernAtDate > southernAtDate)

        val southernAtItsPeak = SeasonalWatering.season(date.plusDays(182), 0.35, Hemisphere.SOUTHERN)
        assertTrue(southernAtItsPeak > southernAtDate)
        assertEquals(northernAtDate, southernAtItsPeak, 1e-9)
    }

    @Test
    fun `hemisphere is derived from the timezone id allowlist`() {
        assertEquals(Hemisphere.SOUTHERN, SeasonalWatering.hemisphereForTimeZoneId("Australia/Sydney"))
        assertEquals(Hemisphere.SOUTHERN, SeasonalWatering.hemisphereForTimeZoneId("Pacific/Auckland"))
        assertEquals(Hemisphere.SOUTHERN, SeasonalWatering.hemisphereForTimeZoneId("America/Argentina/Buenos_Aires"))
        assertEquals(Hemisphere.SOUTHERN, SeasonalWatering.hemisphereForTimeZoneId("America/Sao_Paulo"))
        assertEquals(Hemisphere.SOUTHERN, SeasonalWatering.hemisphereForTimeZoneId("Africa/Johannesburg"))
        assertEquals(Hemisphere.SOUTHERN, SeasonalWatering.hemisphereForTimeZoneId("Indian/Reunion"))
        assertEquals(Hemisphere.NORTHERN, SeasonalWatering.hemisphereForTimeZoneId("Europe/London"))
        assertEquals(Hemisphere.NORTHERN, SeasonalWatering.hemisphereForTimeZoneId("America/New_York"))
        assertEquals(Hemisphere.NORTHERN, SeasonalWatering.hemisphereForTimeZoneId("UTC"))
        // Equatorial/unmatched defaults to northern (#569) — low-stakes since seasonality is weak there.
        assertEquals(Hemisphere.NORTHERN, SeasonalWatering.hemisphereForTimeZoneId("Africa/Lagos"))
    }

    @Test
    fun `day-of-year wraparound from Dec 31 to Jan 1 is continuous, not a discontinuity`() {
        val dec31 = LocalDate.of(2023, 12, 31)
        val jan1 = LocalDate.of(2024, 1, 1)
        val seasonDec31 = SeasonalWatering.season(dec31, 0.35, Hemisphere.NORTHERN)
        val seasonJan1 = SeasonalWatering.season(jan1, 0.35, Hemisphere.NORTHERN)
        assertTrue(abs(seasonDec31 - seasonJan1) < 0.01)
    }

    @Test
    fun `leap year Feb 29 does not crash and stays within the amplitude bounds`() {
        val feb29 = LocalDate.of(2024, 2, 29)
        val season = SeasonalWatering.season(feb29, 0.35, Hemisphere.NORTHERN)
        assertTrue(season in 0.65 - 1e-6..1.35 + 1e-6)
    }

    @Test
    fun `leap year day-of-year wraparound around New Year stays continuous`() {
        val dec31LeapYear = LocalDate.of(2024, 12, 31)
        val jan1NextYear = LocalDate.of(2025, 1, 1)
        val seasonDec31 = SeasonalWatering.season(dec31LeapYear, 0.35, Hemisphere.NORTHERN)
        val seasonJan1 = SeasonalWatering.season(jan1NextYear, 0.35, Hemisphere.NORTHERN)
        assertTrue(abs(seasonDec31 - seasonJan1) < 0.01)
    }

    @Test
    fun `effectiveInterval rounds and clamps to 1 through 180`() {
        val date = LocalDate.of(2023, 7, 5)
        assertEquals(
            1,
            SeasonalWatering.effectiveInterval(1.0, date, 0.5, Hemisphere.NORTHERN)
        )
        assertEquals(
            180,
            SeasonalWatering.effectiveInterval(200.0, date, 0.0, Hemisphere.NORTHERN)
        )
        // At the exact peak day, season = 1 + amplitude, so 10 * 1.35 = 13.5, rounding up to 14.
        val rounded = SeasonalWatering.effectiveInterval(10.0, LocalDate.of(2023, 1, 5), 0.35, Hemisphere.NORTHERN)
        assertEquals(14, rounded)
    }

    @Test
    fun `deseasonalize is the inverse of effectiveInterval's multiplication`() {
        val date = LocalDate.of(2023, 6, 21)
        val amplitude = 0.35
        val hemisphere = Hemisphere.NORTHERN
        val base = 7.0
        val seasonal = base * SeasonalWatering.season(date, amplitude, hemisphere)
        val recoveredBase = SeasonalWatering.deseasonalize(seasonal, date, amplitude, hemisphere)
        assertEquals(base, recoveredBase, 1e-9)
    }

    @Test
    fun `deseasonalizeToDays rounds and floors at 1`() {
        val date = LocalDate.of(2023, 1, 5)
        val result = SeasonalWatering.deseasonalizeToDays(1, date, 0.5, Hemisphere.NORTHERN)
        assertTrue(result >= 1)
    }
}
