package com.yapt.planttracker.domain.watering

import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringScheduleMode
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.util.toLocalDate
import com.yapt.planttracker.util.toStartOfDayMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class WateringOverviewBuilderTest {

    private val summer = LocalDate.of(2026, 7, 4).toStartOfDayMillis() + TimeUnit.HOURS.toMillis(12)
    private val winter = LocalDate.of(2027, 1, 5).toStartOfDayMillis() + TimeUnit.HOURS.toMillis(12)
    private val hemisphere = Hemisphere.NORTHERN

    @Suppress("LongParameterList")
    private fun plant(
        id: Long,
        name: String = "Plant $id",
        interval: Int? = 7,
        base: Double? = null,
        pinned: Boolean = false,
        archivedAt: Long? = null,
        dormancyStart: Int? = null,
        dormancyEnd: Int? = null,
        dormantCadence: Int? = null,
        override: Long? = null
    ) = Plant(
        id = id,
        name = name,
        wateringIntervalDays = interval,
        wateringBaseIntervalDays = base,
        pinIntervalToBase = pinned,
        archivedAt = archivedAt,
        dormancyStartMonth = dormancyStart,
        dormancyEndMonth = dormancyEnd,
        dormantWateringIntervalDays = dormantCadence,
        wateringDueDateOverride = override
    )

    private fun build(
        plants: List<Plant>,
        lastWatered: Map<Long, Long> = emptyMap(),
        amplitude: Double = 0.0,
        now: Long = summer
    ) = WateringOverviewBuilder.build(plants, lastWatered, amplitude, now, hemisphere)

    private fun WateringOverview.group(group: WateringOverviewGroup) =
        sections.firstOrNull { it.group == group }?.items.orEmpty()

    private fun WateringOverview.ids(group: WateringOverviewGroup) = group(group).map { it.plant.id }

    @Test
    fun `band edges fall on 3-4, 7-8 and 14-15`() {
        val overview = build(
            listOf(1, 3, 4, 7, 8, 14, 15, 60).mapIndexed { index, days -> plant(index + 1L, interval = days) }
        )

        assertEquals(listOf(1L, 2L), overview.ids(WateringOverviewGroup.EVERY_1_3_DAYS))
        assertEquals(listOf(3L, 4L), overview.ids(WateringOverviewGroup.EVERY_4_7_DAYS))
        assertEquals(listOf(5L, 6L), overview.ids(WateringOverviewGroup.EVERY_8_14_DAYS))
        assertEquals(listOf(7L, 8L), overview.ids(WateringOverviewGroup.EVERY_15_PLUS_DAYS))
    }

    @Test
    fun `sections come in page order and empty groups are omitted`() {
        val overview = build(
            listOf(
                plant(1, interval = null),
                plant(2, interval = 20),
                plant(3, interval = 2),
                plant(4, dormancyStart = 6, dormancyEnd = 8)
            )
        )

        assertEquals(
            listOf(
                WateringOverviewGroup.EVERY_1_3_DAYS,
                WateringOverviewGroup.EVERY_15_PLUS_DAYS,
                WateringOverviewGroup.RESTING,
                WateringOverviewGroup.NO_SCHEDULE
            ),
            overview.sections.map { it.group }
        )
    }

    @Test
    fun `an empty or all-archived list builds no sections and no counts`() {
        val overview = build(listOf(plant(1, archivedAt = 5L)))

        assertTrue(overview.sections.isEmpty())
        assertEquals(0, overview.totalCount)
    }

    @Test
    fun `archived plants never appear and every active plant appears exactly once`() {
        val plants = listOf(
            plant(1, interval = 2),
            plant(2, interval = null),
            plant(3, archivedAt = 9L),
            plant(4, dormancyStart = 6, dormancyEnd = 8),
            plant(5, interval = 30)
        )

        val overview = build(plants)

        val listed = overview.sections.flatMap { s -> s.items.map { it.plant.id } }
        assertEquals(listOf(1L, 5L, 4L, 2L), listed)
        assertEquals(4, overview.totalCount)
    }

    @Test
    fun `hidden plants never appear in the watering overview`() {
        val overview = build(listOf(plant(1, interval = 2), plant(2, interval = 2).copy(hiddenAt = 5L)))

        assertEquals(listOf(1L), overview.sections.flatMap { s -> s.items.map { it.plant.id } })
        assertEquals(1, overview.totalCount)
    }

    @Test
    fun `counts sum to the total and resting plants are not on a schedule`() {
        val overview = build(
            listOf(
                plant(1, interval = 2),
                plant(2, interval = 9),
                plant(3, dormancyStart = 6, dormancyEnd = 8),
                plant(4, interval = null),
                plant(5, interval = null)
            )
        )

        assertEquals(5, overview.totalCount)
        assertEquals(2, overview.scheduledCount)
        assertEquals(1, overview.restingCount)
        assertEquals(2, overview.noScheduleCount)
    }

    @Test
    fun `a plant without an interval has no schedule even with a dormancy window and shows no due date`() {
        val overview = build(
            listOf(plant(1, interval = null, dormancyStart = 6, dormancyEnd = 8, override = summer + 1000))
        )

        val item = overview.group(WateringOverviewGroup.NO_SCHEDULE).single()
        assertNull(item.intervalDays)
        assertNull(item.nextDueAt)
        assertTrue(overview.group(WateringOverviewGroup.RESTING).isEmpty())
    }

    @Test
    fun `a suspended dormant plant rests, reads as paused and has no interval or due date`() {
        val overview = build(listOf(plant(1, interval = 7, dormancyStart = 6, dormancyEnd = 8)))

        val item = overview.group(WateringOverviewGroup.RESTING).single()
        assertTrue(item.isPaused)
        assertEquals(WateringScheduleMode.DORMANT_SUSPENDED, item.mode)
        assertNull(item.intervalDays)
        assertNull(item.nextDueAt)
    }

    @Test
    fun `a dormant plant with a cadence rests showing its fixed cadence and next due date`() {
        val lastWatered = summer - TimeUnit.DAYS.toMillis(10)
        val plant = plant(1, interval = 7, base = 7.0, dormancyStart = 6, dormancyEnd = 8, dormantCadence = 28)

        val item = build(listOf(plant), mapOf(1L to lastWatered), amplitude = 0.5)
            .group(WateringOverviewGroup.RESTING).single()

        assertFalse(item.isPaused)
        assertEquals(WateringScheduleMode.DORMANT_CADENCE, item.mode)
        assertEquals(28, item.intervalDays)
        val expected = CareSchedule.computeStatus(
            plant = plant,
            lastWateredAt = lastWatered,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = summer,
            seasonalAmplitude = 0.5,
            hemisphere = hemisphere
        ).nextWateringDueAt
        assertEquals(expected, item.nextDueAt)
        assertEquals(lastWatered.toLocalDate().plusDays(28), item.nextDueAt!!.toLocalDate())
    }

    @Test
    fun `a dormancy window that is not active leaves the plant in its band`() {
        val overview = build(listOf(plant(1, interval = 5, dormancyStart = 11, dormancyEnd = 2)))

        assertEquals(listOf(1L), overview.ids(WateringOverviewGroup.EVERY_4_7_DAYS))
    }

    @Test
    fun `an unpinned plant shows the seasonal effective interval and can change band with the season`() {
        val plant = plant(1, interval = 6, base = 6.0)

        val inWinter = build(listOf(plant), amplitude = 0.5, now = winter)
        val inSummer = build(listOf(plant), amplitude = 0.5, now = summer)

        val expectedWinter = CareSchedule.effectiveWateringIntervalDaysForDisplay(
            plant,
            winter.toLocalDate(),
            0.5,
            hemisphere
        )
        val expectedSummer = CareSchedule.effectiveWateringIntervalDaysForDisplay(
            plant,
            summer.toLocalDate(),
            0.5,
            hemisphere
        )
        assertEquals(expectedWinter, inWinter.group(WateringOverviewGroup.EVERY_8_14_DAYS).single().intervalDays)
        assertEquals(expectedSummer, inSummer.group(WateringOverviewGroup.EVERY_1_3_DAYS).single().intervalDays)
        assertTrue(expectedWinter != expectedSummer)
    }

    @Test
    fun `a pinned plant and amplitude Off both show the stored literal`() {
        val pinned = plant(1, interval = 6, base = 6.0, pinned = true)
        val unpinned = plant(2, interval = 6, base = 6.0)

        val withAmplitude = build(listOf(pinned, unpinned), amplitude = 0.5, now = winter)
        val amplitudeOff = build(listOf(unpinned), amplitude = 0.0, now = winter)

        assertEquals(6, withAmplitude.group(WateringOverviewGroup.EVERY_4_7_DAYS).single().intervalDays)
        assertEquals(listOf(2L), withAmplitude.ids(WateringOverviewGroup.EVERY_8_14_DAYS))
        assertEquals(6, amplitudeOff.group(WateringOverviewGroup.EVERY_4_7_DAYS).single().intervalDays)
    }

    @Test
    fun `a never-watered plant takes its due date from the schedule rather than inventing one`() {
        val plant = plant(1, interval = 7)

        val item = build(listOf(plant)).group(WateringOverviewGroup.EVERY_4_7_DAYS).single()

        val expected = CareSchedule.computeStatus(
            plant = plant,
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = summer,
            seasonalAmplitude = 0.0,
            hemisphere = hemisphere
        ).nextWateringDueAt
        assertEquals(expected, item.nextDueAt)
    }

    @Test
    fun `a watered plant is due its interval after the last watering`() {
        val lastWatered = summer - TimeUnit.DAYS.toMillis(2)

        val item = build(listOf(plant(1, interval = 7)), mapOf(1L to lastWatered))
            .group(WateringOverviewGroup.EVERY_4_7_DAYS).single()

        assertEquals(lastWatered.toLocalDate().plusDays(7), item.nextDueAt!!.toLocalDate())
    }

    @Test
    fun `a band sorts by interval then name case-insensitively then id`() {
        val overview = build(
            listOf(
                plant(1, "banana", interval = 5),
                plant(2, "Apple", interval = 6),
                plant(3, "apple", interval = 5),
                plant(4, "Cherry", interval = 4),
                plant(5, "apple", interval = 5)
            )
        )

        assertEquals(listOf(4L, 3L, 5L, 1L, 2L), overview.ids(WateringOverviewGroup.EVERY_4_7_DAYS))
    }

    @Test
    fun `resting sorts by cadence with suspended plants last then by name, and no schedule by name`() {
        val overview = build(
            listOf(
                plant(1, "Zed", dormancyStart = 6, dormancyEnd = 8),
                plant(2, "Beta", dormancyStart = 6, dormancyEnd = 8, dormantCadence = 35),
                plant(3, "Alpha", dormancyStart = 6, dormancyEnd = 8, dormantCadence = 35),
                plant(4, "Gamma", dormancyStart = 6, dormancyEnd = 8, dormantCadence = 28),
                plant(5, "Yam", interval = null),
                plant(6, "Aloe", interval = null)
            )
        )

        assertEquals(listOf(4L, 3L, 2L, 1L), overview.ids(WateringOverviewGroup.RESTING))
        assertEquals(listOf(6L, 5L), overview.ids(WateringOverviewGroup.NO_SCHEDULE))
    }
}
