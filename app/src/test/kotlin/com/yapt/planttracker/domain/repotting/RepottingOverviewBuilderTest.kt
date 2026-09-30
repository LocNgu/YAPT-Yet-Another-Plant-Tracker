package com.yapt.planttracker.domain.repotting

import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.FertilizingSeason
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.domain.schedule.RepotPlanState
import com.yapt.planttracker.util.toStartOfDayMillis
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.TimeZone

class RepottingOverviewBuilderTest {

    private val originalTimeZone: TimeZone = TimeZone.getDefault()
    private val today = LocalDate.of(2026, 9, 30)
    private val north = Hemisphere.NORTHERN

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
    }

    private fun millis(year: Int, month: Int, day: Int) = LocalDate.of(year, month, day).toStartOfDayMillis()

    private fun plant(
        id: Long,
        name: String = "Plant $id",
        createdAt: LocalDate = LocalDate.of(2020, 1, 1),
        archivedAt: Long? = null,
        repotPlanSeasonStartAt: Long? = null
    ) = Plant(
        id = id,
        name = name,
        createdAt = createdAt.toStartOfDayMillis(),
        archivedAt = archivedAt,
        repotPlanSeasonStartAt = repotPlanSeasonStartAt,
        repotPlanMadeAt = repotPlanSeasonStartAt?.let { millis(2026, 1, 1) }
    )

    private fun build(
        plants: List<Plant>,
        lastRepot: Map<Long, Long> = emptyMap(),
        threshold: RepottingOverviewThreshold = RepottingOverviewThreshold.TWO_YEARS,
        on: LocalDate = today
    ) = RepottingOverviewBuilder.build(plants, lastRepot, threshold, on, north)

    @Test
    fun `last repot wins over createdAt as the anchor`() {
        val p = plant(1, createdAt = LocalDate.of(2015, 1, 1))
        val result = build(listOf(p), mapOf(1L to millis(2026, 1, 1)))
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `never repotted plant falls back to createdAt for year chips`() {
        val p = plant(1, createdAt = LocalDate.of(2023, 3, 1))
        val item = build(listOf(p)).items.single()
        assertEquals(millis(2023, 3, 1), item.anchorAtMillis)
        assertTrue(item.neverRepotted)
    }

    @Test
    fun `repotted plant carries its last repot and is not flagged never repotted`() {
        val p = plant(1)
        val item = build(listOf(p), mapOf(1L to millis(2023, 3, 1))).items.single()
        assertEquals(millis(2023, 3, 1), item.anchorAtMillis)
        assertFalse(item.neverRepotted)
    }

    @Test
    fun `year boundary is inclusive on the exact anniversary`() {
        val p = plant(1)
        val repot = mapOf(1L to millis(2024, 9, 30))
        assertEquals(1, build(listOf(p), repot, RepottingOverviewThreshold.TWO_YEARS, today).count)
        assertEquals(
            0,
            build(listOf(p), repot, RepottingOverviewThreshold.TWO_YEARS, today.minusDays(1)).count
        )
    }

    @Test
    fun `one day short of the anniversary is excluded`() {
        val p = plant(1)
        val repot = mapOf(1L to millis(2024, 10, 1))
        assertEquals(0, build(listOf(p), repot, RepottingOverviewThreshold.TWO_YEARS).count)
    }

    @Test
    fun `Feb 29 anchor reaches one year on Feb 28 and two years on Feb 28`() {
        val p = plant(1)
        val repot = mapOf(1L to millis(2024, 2, 29))
        val oneYear = RepottingOverviewThreshold.ONE_YEAR
        assertEquals(0, build(listOf(p), repot, oneYear, LocalDate.of(2025, 2, 27)).count)
        assertEquals(1, build(listOf(p), repot, oneYear, LocalDate.of(2025, 2, 28)).count)
        val threeYears = RepottingOverviewThreshold.THREE_YEARS
        assertEquals(0, build(listOf(p), repot, threeYears, LocalDate.of(2027, 2, 27)).count)
        assertEquals(1, build(listOf(p), repot, threeYears, LocalDate.of(2027, 2, 28)).count)
    }

    @Test
    fun `Feb 29 anchor into a leap year matches on Feb 29 itself`() {
        val p = plant(1)
        val repot = mapOf(1L to millis(2020, 2, 29))
        val fourYearsLess = RepottingOverviewThreshold.THREE_YEARS
        assertEquals(1, build(listOf(p), repot, fourYearsLess, LocalDate.of(2024, 2, 29)).count)
    }

    @Test
    fun `calendar years not 365 day multiples across a leap day`() {
        val p = plant(1)
        val repot = mapOf(1L to millis(2023, 3, 1))
        val oneYear = RepottingOverviewThreshold.ONE_YEAR
        assertEquals(0, build(listOf(p), repot, oneYear, LocalDate.of(2024, 2, 29)).count)
        assertEquals(1, build(listOf(p), repot, oneYear, LocalDate.of(2024, 3, 1)).count)
        val twoYears = RepottingOverviewThreshold.TWO_YEARS
        assertEquals(0, build(listOf(p), repot, twoYears, LocalDate.of(2025, 2, 28)).count)
        assertEquals(1, build(listOf(p), repot, twoYears, LocalDate.of(2025, 3, 1)).count)
    }

    @Test
    fun `calendar year boundary December 31 to January 1`() {
        val p = plant(1)
        val repot = mapOf(1L to millis(2024, 1, 1))
        val twoYears = RepottingOverviewThreshold.TWO_YEARS
        assertEquals(0, build(listOf(p), repot, twoYears, LocalDate.of(2025, 12, 31)).count)
        assertEquals(1, build(listOf(p), repot, twoYears, LocalDate.of(2026, 1, 1)).count)
    }

    @Test
    fun `recently added never repotted plant is in Never but not in year chips`() {
        val recent = plant(1, createdAt = today.minusMonths(6))
        val plants = listOf(recent)
        assertEquals(1, build(plants, threshold = RepottingOverviewThreshold.NEVER).count)
        for (chip in listOf(
            RepottingOverviewThreshold.ONE_YEAR,
            RepottingOverviewThreshold.TWO_YEARS,
            RepottingOverviewThreshold.THREE_YEARS
        )) {
            assertEquals(chip.name, 0, build(plants, threshold = chip).count)
        }
    }

    @Test
    fun `Never excludes any plant with a REPOT log however old`() {
        val plants = listOf(plant(1), plant(2))
        val result = build(plants, mapOf(1L to millis(2010, 1, 1)), RepottingOverviewThreshold.NEVER)
        assertEquals(listOf(2L), result.items.map { it.plant.id })
        assertTrue(result.items.single().neverRepotted)
    }

    @Test
    fun `year chips are nested - larger threshold is a subset`() {
        val plants = listOf(plant(1), plant(2), plant(3), plant(4))
        val lastRepot = mapOf(
            1L to millis(2026, 6, 1),
            2L to millis(2025, 6, 1),
            3L to millis(2024, 6, 1),
            4L to millis(2023, 6, 1)
        )
        fun ids(t: RepottingOverviewThreshold) = build(plants, lastRepot, t).items.map { it.plant.id }.toSet()
        assertEquals(setOf(2L, 3L, 4L), ids(RepottingOverviewThreshold.ONE_YEAR))
        assertEquals(setOf(3L, 4L), ids(RepottingOverviewThreshold.TWO_YEARS))
        assertEquals(setOf(4L), ids(RepottingOverviewThreshold.THREE_YEARS))
    }

    @Test
    fun `planned plant is grouped and excluded from list and count`() {
        val planned = plant(1, repotPlanSeasonStartAt = millis(2027, 3, 1))
        val other = plant(2)
        val result = build(listOf(planned, other))
        assertEquals(listOf(1L), result.planned.map { it.plant.id })
        assertEquals(listOf(2L), result.items.map { it.plant.id })
        assertEquals(1, result.count)
    }

    @Test
    fun `planned plant appears under every chip including Never`() {
        val planned = plant(1, repotPlanSeasonStartAt = millis(2027, 3, 1))
        for (chip in RepottingOverviewThreshold.entries) {
            val result = build(listOf(planned), threshold = chip)
            assertEquals(chip.name, 1, result.planned.size)
            assertEquals(chip.name, 0, result.count)
        }
    }

    @Test
    fun `planned item carries season year and upcoming state`() {
        val planned = plant(1, repotPlanSeasonStartAt = millis(2027, 3, 1))
        val item = build(listOf(planned)).planned.single()
        assertEquals(FertilizingSeason.SPRING, item.plan.season)
        assertEquals(2027, item.plan.year)
        assertEquals(RepotPlanState.UPCOMING, item.state)
        assertFalse(item.isOverdue)
    }

    @Test
    fun `plan in season is not overdue`() {
        val planned = plant(1, repotPlanSeasonStartAt = millis(2026, 9, 1))
        val item = build(listOf(planned)).planned.single()
        assertEquals(RepotPlanState.IN_SEASON, item.state)
        assertFalse(item.isOverdue)
    }

    @Test
    fun `plan whose season has ended still shows as overdue`() {
        val planned = plant(1, repotPlanSeasonStartAt = millis(2026, 3, 1))
        val item = build(listOf(planned)).planned.single()
        assertEquals(RepotPlanState.SEASON_ENDED, item.state)
        assertTrue(item.isOverdue)
    }

    @Test
    fun `plan flips to overdue the day after the season ends`() {
        val planned = plant(1, repotPlanSeasonStartAt = millis(2026, 3, 1))
        assertEquals(
            RepotPlanState.IN_SEASON,
            build(listOf(planned), on = LocalDate.of(2026, 5, 31)).planned.single().state
        )
        assertEquals(
            RepotPlanState.SEASON_ENDED,
            build(listOf(planned), on = LocalDate.of(2026, 6, 1)).planned.single().state
        )
    }

    @Test
    fun `planned group uses the supplied hemisphere`() {
        val planned = plant(1, repotPlanSeasonStartAt = millis(2027, 3, 1))
        val south = RepottingOverviewBuilder.build(
            listOf(planned),
            emptyMap(),
            RepottingOverviewThreshold.TWO_YEARS,
            today,
            Hemisphere.SOUTHERN
        )
        assertEquals(FertilizingSeason.AUTUMN, south.planned.single().plan.season)
    }

    @Test
    fun `planned group sorts by season start then name`() {
        val later = plant(1, name = "A", repotPlanSeasonStartAt = millis(2027, 6, 1))
        val earlyB = plant(2, name = "B", repotPlanSeasonStartAt = millis(2027, 3, 1))
        val earlyA = plant(3, name = "a", repotPlanSeasonStartAt = millis(2027, 3, 1))
        val result = build(listOf(later, earlyB, earlyA))
        assertEquals(listOf(3L, 2L, 1L), result.planned.map { it.plant.id })
    }

    @Test
    fun `archived plants are excluded everywhere`() {
        val archived = plant(1, archivedAt = millis(2026, 1, 1))
        val archivedPlanned = plant(2, archivedAt = millis(2026, 1, 1), repotPlanSeasonStartAt = millis(2027, 3, 1))
        for (chip in RepottingOverviewThreshold.entries) {
            val result = build(listOf(archived, archivedPlanned), threshold = chip)
            assertTrue(chip.name, result.items.isEmpty())
            assertTrue(chip.name, result.planned.isEmpty())
            assertEquals(chip.name, 0, result.count)
        }
    }

    @Test
    fun `plant without a repotting interval is listed like any other`() {
        val noInterval = plant(1).copy(repottingIntervalDays = null)
        val withInterval = plant(2).copy(repottingIntervalDays = 365)
        val result = build(listOf(noInterval, withInterval))
        assertEquals(setOf(1L, 2L), result.items.map { it.plant.id }.toSet())
    }

    @Test
    fun `items sort oldest anchor first`() {
        val plants = listOf(plant(1), plant(2), plant(3))
        val lastRepot = mapOf(
            1L to millis(2023, 6, 1),
            2L to millis(2021, 6, 1),
            3L to millis(2022, 6, 1)
        )
        val result = build(plants, lastRepot)
        assertEquals(listOf(2L, 3L, 1L), result.items.map { it.plant.id })
    }

    @Test
    fun `never repotted and repotted plants interleave by anchor`() {
        val old = plant(1, createdAt = LocalDate.of(2019, 1, 1))
        val repotted = plant(2)
        val result = build(listOf(repotted, old), mapOf(2L to millis(2022, 1, 1)))
        assertEquals(listOf(1L, 2L), result.items.map { it.plant.id })
    }

    @Test
    fun `equal anchors tie-break by name case-insensitively then id`() {
        val plants = listOf(
            plant(1, name = "banana"),
            plant(2, name = "Apple"),
            plant(4, name = "cherry"),
            plant(3, name = "cherry")
        )
        val sameDay = millis(2022, 1, 1)
        val result = build(plants, plants.associate { it.id to sameDay })
        assertEquals(listOf("Apple", "banana", "cherry", "cherry"), result.items.map { it.plant.name })
        assertEquals(listOf(3L, 4L), result.items.map { it.plant.id }.takeLast(2))
    }

    @Test
    fun `count matches the list size and ignores planned plants`() {
        val plants = listOf(plant(1), plant(2), plant(3, repotPlanSeasonStartAt = millis(2027, 3, 1)))
        val result = build(plants, threshold = RepottingOverviewThreshold.NEVER)
        assertEquals(result.items.size, result.count)
        assertEquals(2, result.count)
    }

    @Test
    fun `no plants yields an empty overview`() {
        val result = build(emptyList())
        assertTrue(result.planned.isEmpty())
        assertTrue(result.items.isEmpty())
        assertEquals(0, result.count)
    }

    @Test
    fun `stored threshold name round-trips and unknown values fall back to two years`() {
        for (t in RepottingOverviewThreshold.entries) {
            assertEquals(t, RepottingOverviewThreshold.fromStoredName(t.name))
        }
        assertEquals(RepottingOverviewThreshold.TWO_YEARS, RepottingOverviewThreshold.fromStoredName(null))
        assertEquals(RepottingOverviewThreshold.TWO_YEARS, RepottingOverviewThreshold.fromStoredName(""))
        assertEquals(RepottingOverviewThreshold.TWO_YEARS, RepottingOverviewThreshold.fromStoredName("FIVE_YEARS"))
    }
}
