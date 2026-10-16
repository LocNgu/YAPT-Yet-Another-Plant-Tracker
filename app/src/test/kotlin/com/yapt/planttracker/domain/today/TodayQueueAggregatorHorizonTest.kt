package com.yapt.planttracker.domain.today

import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.FertilizingSeason
import com.yapt.planttracker.domain.schedule.Hemisphere
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/** `TodayQueueInput.horizonEnd` (#910): a caller-widened Upcoming window over the same next-occurrence tasks. */
class TodayQueueAggregatorHorizonTest {

    private val originalTimeZone: TimeZone = TimeZone.getDefault()
    private val today = LocalDate.of(2026, 9, 27)

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

    private fun millis(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Suppress("LongParameterList")
    private fun plant(
        id: Long = 1L,
        wateringIntervalDays: Int? = null,
        fertilizingIntervalDays: Int? = null,
        useLiquidFertilizer: Boolean = false,
        fertilizingSeasons: Set<FertilizingSeason> = FertilizingSeason.entries.toSet(),
        dormancyStartMonth: Int? = null,
        dormancyEndMonth: Int? = null,
        dormantWateringIntervalDays: Int? = null
    ) = Plant(
        id = id,
        name = "Plant $id",
        wateringIntervalDays = wateringIntervalDays,
        fertilizingIntervalDays = fertilizingIntervalDays,
        useLiquidFertilizer = useLiquidFertilizer,
        fertilizingSeasons = fertilizingSeasons,
        createdAt = millis(today.minusDays(60)),
        updatedAt = millis(today.minusDays(60)),
        dormancyStartMonth = dormancyStartMonth,
        dormancyEndMonth = dormancyEndMonth,
        dormantWateringIntervalDays = dormantWateringIntervalDays,
        pinIntervalToBase = true
    )

    private fun log(plantId: Long, type: CareType, date: LocalDate) =
        CareLog(plantId = plantId, careType = type, loggedAt = millis(date))

    private fun build(
        plants: List<Plant>,
        logs: List<CareLog>,
        inputToday: LocalDate = today,
        horizonEnd: LocalDate? = null
    ): TodayQueueSnapshot {
        val base = TodayQueueInput(
            plants = plants,
            careLogs = logs,
            customReminders = emptyList(),
            issues = emptyList(),
            photos = emptyList(),
            today = inputToday,
            seasonalAmplitude = 0.0,
            photoReminderEnabled = false,
            hemisphere = Hemisphere.NORTHERN
        )
        return TodayQueueAggregator.build(if (horizonEnd == null) base else base.copy(horizonEnd = horizonEnd))
    }

    @Test
    fun `default horizon end is three days after today`() {
        val input = TodayQueueInput(
            plants = emptyList(),
            careLogs = emptyList(),
            customReminders = emptyList(),
            issues = emptyList(),
            photos = emptyList(),
            today = today,
            seasonalAmplitude = 0.0,
            photoReminderEnabled = false
        )

        assertEquals(today.plusDays(3), input.horizonEnd)
    }

    @Test
    fun `a task ten days out appears as upcoming with a wider horizon and not by default`() {
        val fern = plant(wateringIntervalDays = 10)
        val logs = listOf(log(fern.id, CareType.WATER, today))

        assertTrue(build(listOf(fern), logs).tasks.isEmpty())

        val widened = build(listOf(fern), logs, horizonEnd = today.plusDays(10)).tasks.single()
        assertEquals(TodayCareKind.WATER, widened.kind)
        assertEquals(TodayTaskBucket.Upcoming(today.plusDays(10).toEpochDay()), widened.bucket)
    }

    @Test
    fun `the horizon end day itself is included and the day after is excluded`() {
        val fern = plant(wateringIntervalDays = 10)
        val logs = listOf(log(fern.id, CareType.WATER, today))

        assertEquals(1, build(listOf(fern), logs, horizonEnd = today.plusDays(10)).tasks.size)
        assertTrue(build(listOf(fern), logs, horizonEnd = today.plusDays(9)).tasks.isEmpty())
    }

    @Test
    fun `default horizon includes day three and excludes day four`() {
        val three = plant(id = 1L, wateringIntervalDays = 3)
        val four = plant(id = 2L, wateringIntervalDays = 4)
        val logs = listOf(log(three.id, CareType.WATER, today), log(four.id, CareType.WATER, today))

        val byDefault = build(listOf(three, four), logs)
        assertEquals(listOf("water:1"), byDefault.tasks.map { it.id })

        val widened = build(listOf(three, four), logs, horizonEnd = today.plusDays(4))
        assertEquals(listOf("water:1", "water:2"), widened.tasks.map { it.id })
    }

    @Test
    fun `a horizon on or before today leaves overdue and today and drops upcoming`() {
        val overdue = plant(id = 1L, wateringIntervalDays = 3)
        val due = plant(id = 2L, wateringIntervalDays = 3)
        val upcoming = plant(id = 3L, wateringIntervalDays = 3)
        val logs = listOf(
            log(overdue.id, CareType.WATER, today.minusDays(5)),
            log(due.id, CareType.WATER, today.minusDays(3)),
            log(upcoming.id, CareType.WATER, today)
        )

        val tasks = build(listOf(overdue, due, upcoming), logs, horizonEnd = today.minusDays(2)).tasks

        assertEquals(
            mapOf("water:1" to TodayTaskBucket.Overdue, "water:2" to TodayTaskBucket.Today),
            tasks.associate { it.id to it.bucket }
        )
    }

    @Test
    fun `each plant still yields only its next occurrence however wide the horizon`() {
        val fern = plant(wateringIntervalDays = 2)
        val logs = listOf(log(fern.id, CareType.WATER, today))

        val tasks = build(listOf(fern), logs, horizonEnd = today.plusDays(42)).tasks

        assertEquals(listOf(TodayTaskBucket.Upcoming(today.plusDays(2).toEpochDay())), tasks.map { it.bucket })
    }

    @Test
    fun `a dormant cadence plant appears on its cadence date inside a wider horizon only`() {
        val october = LocalDate.of(2026, 10, 5)
        val cactus = plant(
            wateringIntervalDays = 30,
            dormancyStartMonth = 10,
            dormancyEndMonth = 10,
            dormantWateringIntervalDays = 14
        )
        val logs = listOf(log(cactus.id, CareType.WATER, october))

        assertTrue(build(listOf(cactus), logs, inputToday = october).tasks.isEmpty())

        val task = build(listOf(cactus), logs, inputToday = october, horizonEnd = october.plusDays(14)).tasks.single()
        assertEquals(TodayCareKind.WATER, task.kind)
        assertEquals(TodayTaskBucket.Upcoming(october.plusDays(14).toEpochDay()), task.bucket)
    }

    @Test
    fun `a suspended dormant plant never appears however wide the horizon`() {
        val october = LocalDate.of(2026, 10, 5)
        val cactus = plant(
            wateringIntervalDays = 3,
            fertilizingIntervalDays = 3,
            dormancyStartMonth = 10,
            dormancyEndMonth = 10
        )
        val logs = listOf(
            log(cactus.id, CareType.WATER, october),
            log(cactus.id, CareType.FERTILIZE, october)
        )

        val tasks = build(listOf(cactus), logs, inputToday = october, horizonEnd = october.plusDays(120)).tasks

        assertTrue(tasks.isEmpty())
    }

    @Test
    fun `fertilizing that lands inside a dormancy window is omitted over a wide horizon`() {
        val fern = plant(fertilizingIntervalDays = 20, dormancyStartMonth = 10, dormancyEndMonth = 10)
        val logs = listOf(log(fern.id, CareType.FERTILIZE, today))

        assertTrue(build(listOf(fern), logs, horizonEnd = today.plusDays(30)).tasks.isEmpty())
    }

    @Test
    fun `fertilizing in an inactive season is omitted over a wide horizon`() {
        val springSummer = setOf(FertilizingSeason.SPRING, FertilizingSeason.SUMMER)
        val logs = listOf(log(1L, CareType.FERTILIZE, today))
        val horizon = today.plusDays(60)

        val inactive = plant(fertilizingIntervalDays = 10, fertilizingSeasons = springSummer)
        assertTrue(build(listOf(inactive), logs, horizonEnd = horizon).tasks.isEmpty())

        val allSeasons = plant(fertilizingIntervalDays = 10)
        val control = build(listOf(allSeasons), logs, horizonEnd = horizon).tasks.single()
        assertEquals(TodayCareKind.FERTILIZE, control.kind)
    }

    @Test
    fun `liquid fertilizer combines with watering over a wide horizon and never stands alone`() {
        val combined = plant(
            id = 1L,
            useLiquidFertilizer = true,
            wateringIntervalDays = 20,
            fertilizingIntervalDays = 15
        )
        val waterOnly = plant(
            id = 2L,
            useLiquidFertilizer = true,
            wateringIntervalDays = 20,
            fertilizingIntervalDays = 25
        )
        val plants = listOf(combined, waterOnly)
        val logs = plants.flatMap {
            listOf(log(it.id, CareType.WATER, today), log(it.id, CareType.FERTILIZE, today))
        }

        val wide = build(plants, logs, horizonEnd = today.plusDays(30)).tasks.associate { it.id to it.kind }
        assertEquals(
            mapOf(
                "water_fertilize:1" to TodayCareKind.WATER_AND_FERTILIZE,
                "water:2" to TodayCareKind.WATER
            ),
            wide
        )

        val fertilizerOnlyInside = build(plants, logs, horizonEnd = today.plusDays(16)).tasks
        assertTrue(fertilizerOnlyInside.isEmpty())
    }

    @Test
    fun `a horizon spanning a DST fall-back counts calendar days`() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val lastWatered = LocalDate.of(2026, 10, 28)
        val due = LocalDate.of(2026, 11, 7)
        val fern = plant(wateringIntervalDays = 10)
        val logs = listOf(log(fern.id, CareType.WATER, lastWatered))

        val task = build(listOf(fern), logs, inputToday = lastWatered, horizonEnd = due).tasks.single()

        assertEquals(TodayTaskBucket.Upcoming(due.toEpochDay()), task.bucket)
        assertTrue(build(listOf(fern), logs, inputToday = lastWatered, horizonEnd = due.minusDays(1)).tasks.isEmpty())
    }
}
