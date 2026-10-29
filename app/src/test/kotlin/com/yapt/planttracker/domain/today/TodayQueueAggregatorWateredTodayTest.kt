package com.yapt.planttracker.domain.today

import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.FertilizerType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.Hemisphere
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The "Watered today" list (#939, product ADR-0071): one entry per visible plant with a WATER log on the
 * aggregator's today, newest first, independent of every horizon.
 */
class TodayQueueAggregatorWateredTodayTest {

    private val today = LocalDate.of(2026, 9, 27)

    private fun at(date: LocalDate, time: LocalTime = LocalTime.NOON): Long =
        date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun plant(
        id: Long,
        name: String = "Plant $id",
        wateringIntervalDays: Int? = null,
        archivedAt: Long? = null,
        hiddenAt: Long? = null
    ) = Plant(
        id = id,
        name = name,
        wateringIntervalDays = wateringIntervalDays,
        archivedAt = archivedAt,
        hiddenAt = hiddenAt,
        createdAt = at(today.minusDays(90)),
        updatedAt = at(today.minusDays(90)),
        pinIntervalToBase = true
    )

    private fun log(plantId: Long, type: CareType, loggedAt: Long) =
        CareLog(plantId = plantId, careType = type, loggedAt = loggedAt)

    private fun water(plantId: Long, loggedAt: Long) = log(plantId, CareType.WATER, loggedAt)

    private fun input(
        plants: List<Plant>,
        logs: List<CareLog>,
        day: LocalDate = today
    ) = TodayQueueInput(
        plants = plants,
        careLogs = logs,
        customReminders = emptyList(),
        issues = emptyList(),
        photos = emptyList(),
        today = day,
        seasonalAmplitude = 0.0,
        photoReminderEnabled = false,
        hemisphere = Hemisphere.NORTHERN
    )

    private fun wateredToday(plants: List<Plant>, logs: List<CareLog>, day: LocalDate = today) =
        TodayQueueAggregator.build(input(plants, logs, day)).wateredToday

    private fun List<TodayWateredPlant>.ids() = map { it.plant.id }

    @Test
    fun `only a WATER log on today counts, from midnight to the last minute`() {
        val plants = (1L..5L).map { plant(it) }
        val logs = listOf(
            water(1, at(today.minusDays(1), LocalTime.of(23, 59))),
            water(2, at(today, LocalTime.MIDNIGHT)),
            water(3, at(today, LocalTime.of(23, 59))),
            water(4, at(today.plusDays(1), LocalTime.MIDNIGHT)),
            water(5, at(today.minusDays(3)))
        )

        assertEquals(listOf(3L, 2L), wateredToday(plants, logs).ids())
    }

    @Test
    fun `archived and hidden plants are left out`() {
        val plants = listOf(
            plant(1),
            plant(2, archivedAt = at(today.minusDays(1))),
            plant(3, hiddenAt = at(today.minusDays(1)))
        )
        val logs = plants.map { water(it.id, at(today)) }

        assertEquals(listOf(1L), wateredToday(plants, logs).ids())
    }

    @Test
    fun `a log for a plant that is not in the input adds nothing`() {
        assertEquals(emptyList<TodayWateredPlant>(), wateredToday(listOf(plant(1)), listOf(water(99, at(today)))))
    }

    @Test
    fun `two WATER logs for one plant today give one entry with the newer time`() {
        val older = at(today, LocalTime.of(8, 0))
        val newer = at(today, LocalTime.of(17, 30))

        val result = wateredToday(listOf(plant(1)), listOf(water(1, newer), water(1, older)))

        assertEquals(1, result.size)
        assertEquals(newer, result.single().wateredAt)
    }

    @Test
    fun `the WATER log that comes with a combined water-and-fertilize action yields one entry`() {
        val fern = plant(1, wateringIntervalDays = 7).copy(useLiquidFertilizer = true)
        val loggedAt = at(today, LocalTime.of(9, 0))
        val logs = listOf(
            water(1, loggedAt),
            CareLog(
                plantId = 1,
                careType = CareType.FERTILIZE,
                loggedAt = loggedAt,
                fertilizerType = FertilizerType.UNSPECIFIED
            )
        )

        val result = wateredToday(listOf(fern), logs)

        assertEquals(listOf(1L), result.ids())
        assertEquals(loggedAt, result.single().wateredAt)
    }

    @Test
    fun `every other care type is ignored`() {
        val types = listOf(
            CareType.FERTILIZE,
            CareType.REPOT,
            CareType.PHOTO,
            CareType.CUSTOM,
            CareType.CHECK,
            CareType.MIST,
            CareType.NOTE
        )
        val plants = types.indices.map { plant(it + 1L) }
        val logs = types.mapIndexed { index, type -> log(index + 1L, type, at(today)) }

        assertEquals(emptyList<TodayWateredPlant>(), wateredToday(plants, logs))
    }

    @Test
    fun `entries are ordered newest first, then by lowercase name, then by plant id`() {
        val plants = listOf(
            plant(1, name = "Fern"),
            plant(2, name = "aloe"),
            plant(3, name = "Cactus"),
            plant(4, name = "Bean"),
            plant(5, name = "Bean")
        )
        val morning = at(today, LocalTime.of(8, 0))
        val noon = at(today, LocalTime.NOON)
        val evening = at(today, LocalTime.of(19, 0))
        val logs = listOf(
            water(1, noon),
            water(2, evening),
            water(3, morning),
            water(4, noon),
            water(5, noon)
        )

        assertEquals(listOf(2L, 4L, 5L, 1L, 3L), wateredToday(plants, logs).ids())
    }

    @Test
    fun `the same inputs on the next day yield an empty list`() {
        val plants = listOf(plant(1), plant(2))
        val logs = plants.map { water(it.id, at(today)) }

        assertEquals(2, wateredToday(plants, logs).size)
        assertEquals(emptyList<TodayWateredPlant>(), wateredToday(plants, logs, today.plusDays(1)))
    }

    @Test
    fun `the list does not depend on either horizon`() {
        val plants = listOf(plant(1, wateringIntervalDays = 1), plant(2, wateringIntervalDays = 30))
        val logs = plants.map { water(it.id, at(today)) }
        val base = input(plants, logs)

        val default = TodayQueueAggregator.build(base)
        val widened = TodayQueueAggregator.build(base.copy(horizonEnd = today.plusDays(42)))
        val narrowed = TodayQueueAggregator.build(base.copy(horizonEnd = today, nonWateringHorizonEnd = today))

        assertEquals(default.wateredToday, widened.wateredToday)
        assertEquals(default.wateredToday, narrowed.wateredToday)
        assertEquals(listOf(1L, 2L), default.wateredToday.ids().sorted())
    }

    @Test
    fun `a plant watered today with a one day interval is also a Watering tomorrow task`() {
        val fern = plant(1, wateringIntervalDays = 1)

        val snapshot = TodayQueueAggregator.build(input(listOf(fern), listOf(water(1, at(today)))))

        assertEquals(listOf(1L), snapshot.wateredToday.ids())
        val task = snapshot.tasks.single()
        assertEquals(TodayCareKind.WATER, task.kind)
        assertEquals(TodayTaskBucket.Upcoming(today.plusDays(1).toEpochDay()), task.bucket)
    }

    @Test
    fun `hasTasksDueNow is true for an Overdue or a Today task`() {
        val overdue = TodayQueueAggregator.build(
            input(listOf(plant(1, wateringIntervalDays = 3)), listOf(water(1, at(today.minusDays(10)))))
        )
        val dueToday = TodayQueueAggregator.build(
            input(listOf(plant(1, wateringIntervalDays = 3)), listOf(water(1, at(today.minusDays(3)))))
        )

        assertEquals(TodayTaskBucket.Overdue, overdue.tasks.single().bucket)
        assertTrue(overdue.hasTasksDueNow)
        assertEquals(TodayTaskBucket.Today, dueToday.tasks.single().bucket)
        assertTrue(dueToday.hasTasksDueNow)
    }

    @Test
    fun `hasTasksDueNow is false with only Upcoming tasks and with no tasks`() {
        val upcomingOnly = TodayQueueAggregator.build(
            input(listOf(plant(1, wateringIntervalDays = 1)), listOf(water(1, at(today))))
        )
        val none = TodayQueueAggregator.build(input(listOf(plant(1)), emptyList()))

        assertTrue(upcomingOnly.tasks.all { it.bucket is TodayTaskBucket.Upcoming })
        assertEquals(1, upcomingOnly.tasks.size)
        assertFalse(upcomingOnly.hasTasksDueNow)
        assertTrue(none.tasks.isEmpty())
        assertFalse(none.hasTasksDueNow)
    }
}
