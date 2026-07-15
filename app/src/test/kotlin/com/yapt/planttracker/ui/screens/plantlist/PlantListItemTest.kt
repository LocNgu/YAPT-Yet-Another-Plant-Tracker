package com.yapt.planttracker.ui.screens.plantlist

import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantCareStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class PlantListItemTest {

    private val now = 1_700_000_000_000L // 2023-11-14 22:13:20 UTC

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private fun statusWithWateringDueIn(id: Long, days: Long?): PlantCareStatus {
        val dueAt = days?.let { now + TimeUnit.DAYS.toMillis(it) }
        return PlantCareStatus(
            plant = Plant(id = id, name = "Plant$id", createdAt = 0L, updatedAt = 0L),
            lastWateredAt = null,
            lastFertilizedAt = null,
            daysSinceLastWatering = null,
            nextWateringDueAt = dueAt,
            isOverdue = false,
            isDueSoon = false,
            nextFertilizingDueAt = null,
            isFertilizingOverdue = false,
            isFertilizingDueSoon = false,
            totalCareLogs = 0
        )
    }

    private fun statusWithFertilizingDueIn(id: Long, days: Long?): PlantCareStatus {
        val dueAt = days?.let { now + TimeUnit.DAYS.toMillis(it) }
        return PlantCareStatus(
            plant = Plant(id = id, name = "Plant$id", createdAt = 0L, updatedAt = 0L),
            lastWateredAt = null,
            lastFertilizedAt = null,
            daysSinceLastWatering = null,
            nextWateringDueAt = null,
            isOverdue = false,
            isDueSoon = false,
            nextFertilizingDueAt = dueAt,
            isFertilizingOverdue = false,
            isFertilizingDueSoon = false,
            totalCareLogs = 0
        )
    }

    private fun headerBuckets(items: List<PlantListItem>): List<DateBucket> =
        items.filterIsInstance<PlantListItem.DateHeader>().map { it.bucket }

    @Test
    fun `ALPHABETICAL produces flat list with no headers`() {
        val statuses = listOf(
            statusWithWateringDueIn(1L, -2L),
            statusWithWateringDueIn(2L, 0L),
            statusWithWateringDueIn(3L, null)
        )

        val result = groupPlantsByDueDate(statuses, SortOrder(SortOption.ALPHABETICAL, SortDirection.ASC), now)

        assertEquals(3, result.size)
        assertTrue(result.all { it is PlantListItem.PlantRow })
    }

    @Test
    fun `RECENTLY_ADDED produces flat list with no headers`() {
        val statuses = listOf(statusWithWateringDueIn(1L, -2L))

        val result = groupPlantsByDueDate(statuses, SortOrder(SortOption.RECENTLY_ADDED, SortDirection.DESC), now)

        assertEquals(listOf(PlantListItem.PlantRow(statuses[0])), result)
    }

    @Test
    fun `WATERING_DUE DESC assigns each plant to the correct bucket in canonical order`() {
        val overdue = statusWithWateringDueIn(1L, -2L)
        val today = statusWithWateringDueIn(2L, 0L)
        val tomorrow = statusWithWateringDueIn(3L, 1L)
        val plusTwo = statusWithWateringDueIn(4L, 2L)
        val plusThree = statusWithWateringDueIn(5L, 3L)
        val later = statusWithWateringDueIn(6L, 10L)
        val notScheduled = statusWithWateringDueIn(7L, null)
        val statuses = listOf(overdue, today, tomorrow, plusTwo, plusThree, later, notScheduled)

        val result = groupPlantsByDueDate(statuses, SortOrder(SortOption.WATERING_DUE, SortDirection.DESC), now)

        val expectedBuckets = listOf(
            DateBucket.Overdue,
            DateBucket.Today,
            DateBucket.Tomorrow,
            DateBucket.Dated((now + TimeUnit.DAYS.toMillis(2)).toLocalDateEpochDay()),
            DateBucket.Dated((now + TimeUnit.DAYS.toMillis(3)).toLocalDateEpochDay()),
            DateBucket.Later,
            DateBucket.NotScheduled
        )
        assertEquals(expectedBuckets, headerBuckets(result))

        val expectedRows = statuses.map { PlantListItem.PlantRow(it) }
        assertEquals(expectedRows, result.filterIsInstance<PlantListItem.PlantRow>())
    }

    @Test
    fun `WATERING_DUE groups multiple plants due the same day under a single header`() {
        val today1 = statusWithWateringDueIn(1L, 0L)
        val today2 = statusWithWateringDueIn(2L, 0L)

        val result = groupPlantsByDueDate(
            listOf(today1, today2),
            SortOrder(SortOption.WATERING_DUE, SortDirection.DESC),
            now
        )

        assertEquals(
            listOf(
                PlantListItem.DateHeader(DateBucket.Today),
                PlantListItem.PlantRow(today1),
                PlantListItem.PlantRow(today2)
            ),
            result
        )
    }

    @Test
    fun `FERTILIZING_DUE groups by nextFertilizingDueAt`() {
        val overdue = statusWithFertilizingDueIn(1L, -1L)
        val today = statusWithFertilizingDueIn(2L, 0L)

        val result = groupPlantsByDueDate(
            listOf(overdue, today),
            SortOrder(SortOption.FERTILIZING_DUE, SortDirection.DESC),
            now
        )

        assertEquals(
            listOf(
                PlantListItem.DateHeader(DateBucket.Overdue),
                PlantListItem.PlantRow(overdue),
                PlantListItem.DateHeader(DateBucket.Today),
                PlantListItem.PlantRow(today)
            ),
            result
        )
    }

    @Test
    fun `BOTH_DUE groups by nextWateringDueAt and ignores direction`() {
        val overdue = statusWithWateringDueIn(1L, -2L)
        val later = statusWithWateringDueIn(2L, 10L)
        val statuses = listOf(overdue, later)

        val ascResult = groupPlantsByDueDate(statuses, SortOrder(SortOption.BOTH_DUE, SortDirection.ASC), now)
        val descResult = groupPlantsByDueDate(statuses, SortOrder(SortOption.BOTH_DUE, SortDirection.DESC), now)

        val expectedBuckets = listOf(DateBucket.Overdue, DateBucket.Later)
        assertEquals(expectedBuckets, headerBuckets(ascResult))
        assertEquals(expectedBuckets, headerBuckets(descResult))
    }

    @Test
    fun `empty list produces empty result for any sort option`() {
        for (option in SortOption.entries) {
            assertEquals(
                emptyList<PlantListItem>(),
                groupPlantsByDueDate(emptyList(), SortOrder(option, SortDirection.ASC), now)
            )
        }
    }

    @Test
    fun `toggling direction reverses the whole bucket sequence`() {
        val overdue = statusWithWateringDueIn(1L, -2L)
        val today = statusWithWateringDueIn(2L, 0L)
        val later = statusWithWateringDueIn(3L, 10L)
        val statuses = listOf(overdue, today, later)

        val descResult = groupPlantsByDueDate(statuses, SortOrder(SortOption.WATERING_DUE, SortDirection.DESC), now)
        val ascResult = groupPlantsByDueDate(statuses, SortOrder(SortOption.WATERING_DUE, SortDirection.ASC), now)

        val descBuckets = headerBuckets(descResult)
        assertEquals(listOf(DateBucket.Overdue, DateBucket.Today, DateBucket.Later), descBuckets)
        assertEquals(descBuckets.reversed(), headerBuckets(ascResult))
    }

    @Test
    fun `never-watered plant (nextWateringDueAt = now, per CareSchedule#428) lands in Today bucket`() {
        val neverWatered = PlantCareStatus(
            plant = Plant(id = 1L, name = "NeverWatered", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L),
            lastWateredAt = null,
            lastFertilizedAt = null,
            daysSinceLastWatering = null,
            nextWateringDueAt = now,
            isOverdue = false,
            isDueSoon = true,
            nextFertilizingDueAt = null,
            isFertilizingOverdue = false,
            isFertilizingDueSoon = false,
            totalCareLogs = 0
        )

        val result = groupPlantsByDueDate(
            listOf(neverWatered),
            SortOrder(SortOption.WATERING_DUE, SortDirection.DESC),
            now
        )

        assertEquals(listOf(DateBucket.Today), headerBuckets(result))
    }

    @Test
    fun `never-fertilized plant with createdAt 30+ days ago (per CareSchedule#428) lands in Overdue bucket`() {
        val createdAt = now - TimeUnit.DAYS.toMillis(45)
        val overdueDueAt = createdAt + TimeUnit.DAYS.toMillis(30)
        val neverFertilized = PlantCareStatus(
            plant = Plant(id = 1L, name = "NeverFertilized", fertilizingIntervalDays = 14, createdAt = createdAt, updatedAt = createdAt),
            lastWateredAt = null,
            lastFertilizedAt = null,
            daysSinceLastWatering = null,
            nextWateringDueAt = null,
            isOverdue = false,
            isDueSoon = false,
            nextFertilizingDueAt = overdueDueAt,
            isFertilizingOverdue = true,
            isFertilizingDueSoon = false,
            totalCareLogs = 0
        )

        val result = groupPlantsByDueDate(
            listOf(neverFertilized),
            SortOrder(SortOption.FERTILIZING_DUE, SortDirection.DESC),
            now
        )

        assertEquals(listOf(DateBucket.Overdue), headerBuckets(result))
    }

    @Test
    fun `ASC direction moves the Not scheduled bucket to the front`() {
        val overdue = statusWithWateringDueIn(1L, -2L)
        val notScheduled = statusWithWateringDueIn(2L, null)
        val statuses = listOf(overdue, notScheduled)

        val result = groupPlantsByDueDate(statuses, SortOrder(SortOption.WATERING_DUE, SortDirection.ASC), now)

        assertEquals(listOf(DateBucket.NotScheduled, DateBucket.Overdue), headerBuckets(result))
    }
}

private fun Long.toLocalDateEpochDay(): Long =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
