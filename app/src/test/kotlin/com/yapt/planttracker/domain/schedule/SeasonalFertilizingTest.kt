package com.yapt.planttracker.domain.schedule

import com.yapt.planttracker.domain.model.Plant
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class SeasonalFertilizingTest {

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

    @Test
    fun `null current-season slot falls back to the main interval`() {
        val plant = Plant(
            name = "Fern",
            fertilizingIntervalDays = 30,
            fertilizingIntervalSummer = 14
        )

        assertEquals(
            30,
            SeasonalFertilizing.effectiveInterval(
                plant,
                LocalDate.of(2026, 4, 1),
                Hemisphere.NORTHERN
            )
        )
        assertEquals(
            14,
            SeasonalFertilizing.effectiveInterval(
                plant,
                LocalDate.of(2026, 7, 1),
                Hemisphere.NORTHERN
            )
        )
    }
}
