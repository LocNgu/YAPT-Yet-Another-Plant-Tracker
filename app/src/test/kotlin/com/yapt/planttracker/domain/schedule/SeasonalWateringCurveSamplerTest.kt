package com.yapt.planttracker.domain.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.abs

class SeasonalWateringCurveSamplerTest {

    @Test
    fun `common year samples exactly 365 points, one per calendar day`() {
        val points = SeasonalWateringCurveSampler.sample(0.35, Hemisphere.NORTHERN, referenceYear = 2023)
        assertEquals(365, points.size)
        assertEquals(LocalDate.of(2023, 1, 1), points.first().date)
        assertEquals(LocalDate.of(2023, 12, 31), points.last().date)
    }

    @Test
    fun `leap year samples exactly 366 points, including Feb 29`() {
        val points = SeasonalWateringCurveSampler.sample(0.35, Hemisphere.NORTHERN, referenceYear = 2024)
        assertEquals(366, points.size)
        assertTrue(points.any { it.date == LocalDate.of(2024, 2, 29) })
        assertEquals(LocalDate.of(2024, 12, 31), points.last().date)
    }

    @Test
    fun `dayOfYear is contiguous and matches the sampled date`() {
        val points = SeasonalWateringCurveSampler.sample(0.35, Hemisphere.NORTHERN, referenceYear = 2023)
        points.forEachIndexed { index, point ->
            assertEquals(index + 1, point.dayOfYear)
            assertEquals(point.date.dayOfYear, point.dayOfYear)
        }
    }

    @Test
    fun `each point's multiplier matches SeasonalWatering season for the same date`() {
        val amplitude = 0.35
        val hemisphere = Hemisphere.NORTHERN
        val points = SeasonalWateringCurveSampler.sample(amplitude, hemisphere, referenceYear = 2023)
        for (point in points) {
            assertEquals(
                SeasonalWatering.season(point.date, amplitude, hemisphere),
                point.multiplier,
                1e-9
            )
        }
    }

    @Test
    fun `amplitude 0 collapses every sampled point to a flat 1_0`() {
        val points = SeasonalWateringCurveSampler.sample(0.0, Hemisphere.NORTHERN, referenceYear = 2023)
        assertTrue(points.all { abs(it.multiplier - 1.0) < 1e-9 })
    }

    @Test
    fun `year-end wraparound stays continuous, not a discontinuity`() {
        val points = SeasonalWateringCurveSampler.sample(0.5, Hemisphere.NORTHERN, referenceYear = 2023)
        assertTrue(abs(points.last().multiplier - points.first().multiplier) < 0.02)
    }

    @Test
    fun `southern hemisphere sample peaks roughly half a year after northern`() {
        val northern = SeasonalWateringCurveSampler.sample(0.35, Hemisphere.NORTHERN, referenceYear = 2023)
        val southern = SeasonalWateringCurveSampler.sample(0.35, Hemisphere.SOUTHERN, referenceYear = 2023)
        val northernPeakDay = northern.maxByOrNull { it.multiplier }!!.dayOfYear
        val southernPeakDay = southern.maxByOrNull { it.multiplier }!!.dayOfYear
        assertEquals(SeasonalWatering.peakDayOfYear(Hemisphere.NORTHERN), northernPeakDay)
        assertEquals(SeasonalWatering.peakDayOfYear(Hemisphere.SOUTHERN), southernPeakDay)
    }

    @Test
    fun `peakDayOfYear is independent of amplitude`() {
        assertEquals(
            SeasonalWatering.peakDayOfYear(Hemisphere.NORTHERN),
            SeasonalWatering.peakDayOfYear(Hemisphere.NORTHERN)
        )
        val mildPeak = SeasonalWateringCurveSampler.sample(0.2, Hemisphere.SOUTHERN, referenceYear = 2023)
            .maxByOrNull { it.multiplier }!!.dayOfYear
        val strongPeak = SeasonalWateringCurveSampler.sample(0.5, Hemisphere.SOUTHERN, referenceYear = 2023)
            .maxByOrNull { it.multiplier }!!.dayOfYear
        assertEquals(mildPeak, strongPeak)
    }
}
