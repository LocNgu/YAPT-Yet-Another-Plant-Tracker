package com.yapt.planttracker.domain.today

import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Away mode's Care-queue plumbing (#897, product ADR-0065): `TodayQueueInput.awayPeriods` -> `WateringTaskAction`. */
class TodayQueueAggregatorAwayTest {

    private val today = LocalDate.of(2026, 7, 20)
    private val zone = ZoneId.systemDefault()
    private val trip = AwayPeriod(id = 1L, firstDay = LocalDate.of(2026, 7, 1), lastDay = LocalDate.of(2026, 7, 15))

    private fun millis(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private val plant = Plant(id = 1L, name = "Fern", wateringIntervalDays = 7, createdAt = millis(today.minusDays(90)))

    private fun waterTask(lastWatered: LocalDate, periods: List<AwayPeriod>): TodayCareTask {
        val snapshot = TodayQueueAggregator.build(
            TodayQueueInput(
                plants = listOf(plant),
                careLogs = listOf(CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = millis(lastWatered))),
                customReminders = emptyList(),
                issues = emptyList(),
                photos = emptyList(),
                today = today,
                seasonalAmplitude = 0.0,
                photoReminderEnabled = false,
                awayPeriods = periods
            )
        )
        return snapshot.tasks.single { it.kind == TodayCareKind.WATER }
    }

    @Test
    fun `a watering gap that crosses the trip skips the reason prompt`() {
        val action = requireNotNull(waterTask(LocalDate.of(2026, 6, 25), listOf(trip)).wateringAction)

        assertTrue(action.isAwaySpanning)
        assertFalse(action.isOnSchedule)
        assertTrue(action.skipsReasonPrompt)
    }

    @Test
    fun `the same off-schedule gap without a trip still asks`() {
        val action = requireNotNull(waterTask(LocalDate.of(2026, 6, 25), emptyList()).wateringAction)

        assertFalse(action.isAwaySpanning)
        assertFalse(action.skipsReasonPrompt)
    }

    @Test
    fun `a gap that starts after the trip is not away-spanning`() {
        val action = requireNotNull(waterTask(LocalDate.of(2026, 7, 16), listOf(trip)).wateringAction)

        assertFalse(action.isAwaySpanning)
    }

    @Test
    fun `the trip changes no due date or bucket`() {
        val away = waterTask(LocalDate.of(2026, 6, 25), listOf(trip))
        val home = waterTask(LocalDate.of(2026, 6, 25), emptyList())

        assertEquals(home.dueAt, away.dueAt)
        assertEquals(home.bucket, away.bucket)
    }

    @Test
    fun `skipsReasonPrompt is true for each clause alone`() {
        val base = WateringTaskAction(
            isOnSchedule = false,
            isGapLong = false,
            isDormancySpanning = false,
            computedDueAt = null,
            effectiveDueAt = null
        )

        assertFalse(base.skipsReasonPrompt)
        assertTrue(base.copy(isOnSchedule = true).skipsReasonPrompt)
        assertTrue(base.copy(isDormancySpanning = true).skipsReasonPrompt)
        assertTrue(base.copy(isAwaySpanning = true).skipsReasonPrompt)
    }
}
