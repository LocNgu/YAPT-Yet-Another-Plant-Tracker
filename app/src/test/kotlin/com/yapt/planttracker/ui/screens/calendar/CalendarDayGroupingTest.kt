package com.yapt.planttracker.ui.screens.calendar

import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantCareStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.TimeZone

class CalendarDayGroupingTest {

    private val today = LocalDate.of(2026, 7, 12)
    private val visibleMonth = YearMonth.of(2026, 7)

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private fun toEpochMillis(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()

    private fun status(
        id: Long = 1L,
        name: String = "Plant$id",
        nextWateringDueAt: LocalDate? = null,
        isOverdue: Boolean = false,
        nextFertilizingDueAt: LocalDate? = null,
        isFertilizingOverdue: Boolean = false,
        useLiquidFertilizer: Boolean = false
    ) = PlantCareStatus(
        plant = Plant(id = id, name = name, createdAt = 0L, updatedAt = 0L, useLiquidFertilizer = useLiquidFertilizer),
        lastWateredAt = null,
        lastFertilizedAt = null,
        daysSinceLastWatering = null,
        nextWateringDueAt = nextWateringDueAt?.let { toEpochMillis(it) },
        isOverdue = isOverdue,
        isDueSoon = nextWateringDueAt == today && !isOverdue,
        nextFertilizingDueAt = nextFertilizingDueAt?.let { toEpochMillis(it) },
        isFertilizingOverdue = isFertilizingOverdue,
        isFertilizingDueSoon = nextFertilizingDueAt == today && !isFertilizingOverdue,
        totalCareLogs = 0
    )

    @Test
    fun `empty statuses produces empty map`() {
        val result = computePlantsByDay(emptyList(), visibleMonth, today)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `overdue watering rolls to today with containsOverdue true`() {
        val s = status(nextWateringDueAt = today.minusDays(3), isOverdue = true)

        val result = computePlantsByDay(listOf(s), visibleMonth, today)

        val entry = result.getValue(today)
        assertEquals(1, entry.plants.size)
        assertTrue(entry.containsOverdue)
        assertTrue(entry.plants[0].waterDue)
        assertFalse(entry.plants[0].fertilizeDue)
    }

    @Test
    fun `overdue fertilizing rolls to today with containsOverdue true`() {
        val s = status(nextFertilizingDueAt = today.minusDays(2), isFertilizingOverdue = true)

        val result = computePlantsByDay(listOf(s), visibleMonth, today)

        val entry = result.getValue(today)
        assertEquals(1, entry.plants.size)
        assertTrue(entry.containsOverdue)
        assertFalse(entry.plants[0].waterDue)
        assertTrue(entry.plants[0].fertilizeDue)
    }

    @Test
    fun `plant with both overdue water and overdue fertilize contributes exactly once to today`() {
        val s = status(
            nextWateringDueAt = today.minusDays(1),
            isOverdue = true,
            nextFertilizingDueAt = today.minusDays(5),
            isFertilizingOverdue = true
        )

        val result = computePlantsByDay(listOf(s), visibleMonth, today)

        val entry = result.getValue(today)
        assertEquals(1, entry.plants.size)
        assertTrue(entry.containsOverdue)
        assertTrue(entry.plants[0].waterDue)
        assertTrue(entry.plants[0].fertilizeDue)
    }

    @Test
    fun `plant due today for both water and fertilize contributes exactly once, not overdue`() {
        val s = status(
            nextWateringDueAt = today,
            isOverdue = false,
            nextFertilizingDueAt = today,
            isFertilizingOverdue = false
        )

        val result = computePlantsByDay(listOf(s), visibleMonth, today)

        val entry = result.getValue(today)
        assertEquals(1, entry.plants.size)
        assertFalse(entry.containsOverdue)
        assertTrue(entry.plants[0].waterDue)
        assertTrue(entry.plants[0].fertilizeDue)
    }

    @Test
    fun `plant with two different future due dates lands on both distinct days`() {
        val waterDate = today.plusDays(5)
        val fertilizeDate = today.plusDays(10)
        val s = status(
            nextWateringDueAt = waterDate,
            nextFertilizingDueAt = fertilizeDate
        )

        val result = computePlantsByDay(listOf(s), visibleMonth, today)

        assertEquals(1, result.getValue(waterDate).plants.size)
        assertTrue(result.getValue(waterDate).plants[0].waterDue)
        assertFalse(result.getValue(waterDate).plants[0].fertilizeDue)

        assertEquals(1, result.getValue(fertilizeDate).plants.size)
        assertFalse(result.getValue(fertilizeDate).plants[0].waterDue)
        assertTrue(result.getValue(fertilizeDate).plants[0].fertilizeDue)

        assertFalse(result.containsKey(today))
    }

    @Test
    fun `skip-watering override is honoured transparently via effective due date`() {
        // CareSchedule.computeStatus() already folds wateringDueDateOverride into nextWateringDueAt;
        // the pure transform doesn't need to special-case it, it just reads the effective date.
        val overrideDate = today.plusDays(3)
        val s = status(nextWateringDueAt = overrideDate)

        val result = computePlantsByDay(listOf(s), visibleMonth, today)

        assertEquals(1, result.getValue(overrideDate).plants.size)
        assertFalse(result.containsKey(today))
    }

    @Test
    fun `only entries inside the visible month are surfaced`() {
        val inMonth = today.plusDays(2)
        val nextMonthDate = visibleMonth.plusMonths(1).atDay(5)
        val s1 = status(id = 1L, nextWateringDueAt = inMonth)
        val s2 = status(id = 2L, nextWateringDueAt = nextMonthDate)

        val result = computePlantsByDay(listOf(s1, s2), visibleMonth, today)

        assertTrue(result.containsKey(inMonth))
        assertFalse(result.containsKey(nextMonthDate))
    }

    @Test
    fun `today outside the visible month does not surface today's rollup`() {
        val s = status(nextWateringDueAt = today.minusDays(1), isOverdue = true)
        val futureMonth = visibleMonth.plusMonths(1)

        val result = computePlantsByDay(listOf(s), futureMonth, today)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `liquid-fertilizer plant with a future fertilize date produces no entry for that date`() {
        val waterDate = today.plusDays(5)
        val fertilizeDate = today.plusDays(10)
        val s = status(
            nextWateringDueAt = waterDate,
            nextFertilizingDueAt = fertilizeDate,
            useLiquidFertilizer = true
        )

        val result = computePlantsByDay(listOf(s), visibleMonth, today)

        assertFalse(result.containsKey(fertilizeDate))
        assertEquals(1, result.getValue(waterDate).plants.size)
        assertTrue(result.getValue(waterDate).plants[0].waterDue)
        assertFalse(result.getValue(waterDate).plants[0].fertilizeDue)
    }

    @Test
    fun `liquid-fertilizer plant with only fertilizing overdue does not land on today`() {
        val s = status(
            nextWateringDueAt = today.plusDays(4),
            nextFertilizingDueAt = today.minusDays(2),
            isFertilizingOverdue = true,
            useLiquidFertilizer = true
        )

        val result = computePlantsByDay(listOf(s), visibleMonth, today)

        assertFalse(result.containsKey(today))
        val entry = result.getValue(today.plusDays(4))
        assertFalse(entry.containsOverdue)
        assertTrue(entry.plants[0].waterDue)
        assertFalse(entry.plants[0].fertilizeDue)
    }

    @Test
    fun `liquid-fertilizer plant on its watering day has fertilizeDue false`() {
        val s = status(
            nextWateringDueAt = today,
            isOverdue = false,
            nextFertilizingDueAt = today,
            isFertilizingOverdue = false,
            useLiquidFertilizer = true
        )

        val result = computePlantsByDay(listOf(s), visibleMonth, today)

        val entry = result.getValue(today)
        assertEquals(1, entry.plants.size)
        assertFalse(entry.containsOverdue)
        assertTrue(entry.plants[0].waterDue)
        assertFalse(entry.plants[0].fertilizeDue)
    }

    @Test
    fun `liquid-fertilizer plant with overdue watering and overdue fertilizing rolls to today once`() {
        val s = status(
            nextWateringDueAt = today.minusDays(1),
            isOverdue = true,
            nextFertilizingDueAt = today.minusDays(1),
            isFertilizingOverdue = true,
            useLiquidFertilizer = true
        )

        val result = computePlantsByDay(listOf(s), visibleMonth, today)

        val entry = result.getValue(today)
        assertEquals(1, entry.plants.size)
        assertTrue(entry.containsOverdue)
        assertTrue(entry.plants[0].waterDue)
        assertFalse(entry.plants[0].fertilizeDue)
    }

    @Test
    fun `liquid-fertilizer plant with null watering date contributes nothing`() {
        val s = status(
            nextWateringDueAt = null,
            nextFertilizingDueAt = today.plusDays(3),
            useLiquidFertilizer = true
        )

        val result = computePlantsByDay(listOf(s), visibleMonth, today)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `regular plant future fertilize date and overdue fertilizing behaviour unchanged`() {
        val fertilizeDate = today.plusDays(7)
        val futurePlant = status(id = 1L, nextFertilizingDueAt = fertilizeDate)
        val overduePlant = status(
            id = 2L,
            nextFertilizingDueAt = today.minusDays(2),
            isFertilizingOverdue = true
        )

        val result = computePlantsByDay(listOf(futurePlant, overduePlant), visibleMonth, today)

        assertEquals(1, result.getValue(fertilizeDate).plants.size)
        assertTrue(result.getValue(fertilizeDate).plants[0].fertilizeDue)

        val todayEntry = result.getValue(today)
        assertTrue(todayEntry.containsOverdue)
        assertTrue(todayEntry.plants[0].fertilizeDue)
    }

    @Test
    fun `isOverdueEntry ignores fertilizing-overdue for liquid-fertilizer plants`() {
        val liquidFertilizeOnlyOverdue = PlantDayInfo(
            status = status(isFertilizingOverdue = true, useLiquidFertilizer = true),
            waterDue = false,
            fertilizeDue = false
        )
        val liquidWaterOverdue = PlantDayInfo(
            status = status(isOverdue = true, useLiquidFertilizer = true),
            waterDue = true,
            fertilizeDue = false
        )
        val regularFertilizeOverdue = PlantDayInfo(
            status = status(isFertilizingOverdue = true),
            waterDue = false,
            fertilizeDue = true
        )

        assertFalse(isOverdueEntry(liquidFertilizeOnlyOverdue))
        assertTrue(isOverdueEntry(liquidWaterOverdue))
        assertTrue(isOverdueEntry(regularFertilizeOverdue))
    }
}
