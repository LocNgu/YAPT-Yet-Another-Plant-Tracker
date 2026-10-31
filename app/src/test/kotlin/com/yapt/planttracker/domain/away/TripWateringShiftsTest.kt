package com.yapt.planttracker.domain.away

import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.ui.screens.plantdetail.WateringGateInputs
import com.yapt.planttracker.ui.screens.plantdetail.WateringGateResult
import com.yapt.planttracker.ui.screens.plantdetail.evaluateWateringGate
import com.yapt.planttracker.ui.screens.plantdetail.isChosenDateOnSchedule
import com.yapt.planttracker.util.toNoonMillis
import com.yapt.planttracker.util.toStartOfDayMillis
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * Hold for the last day and bring forward on it (#969, product ADR-0076): the worked boundary table, who is left
 * out, the calendar-day maths across a DST fall-back, and the hold rule's tie to the reason-prompt gate.
 * `L` is the last day at home and `E = L + tripDays` the trip's last day.
 */
class TripWateringShiftsTest {

    private val originalTimeZone: TimeZone = TimeZone.getDefault()
    private val lastDay = LocalDate.of(2026, 7, 15)

    @Before
    fun pinTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

    private fun millis(day: LocalDate, hour: Int = 12, minute: Int = 0): Long =
        day.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun plant(id: Long = 1, interval: Int? = 21, name: String = "Plant $id") = Plant(
        id = id,
        name = name,
        wateringIntervalDays = interval,
        pinIntervalToBase = true,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun water(plantId: Long, day: LocalDate, hour: Int = 12, minute: Int = 0) =
        CareLog(plantId = plantId, careType = CareType.WATER, loggedAt = millis(day, hour, minute))

    private fun input(
        plants: List<Plant>,
        logs: List<CareLog>,
        tripDays: Int,
        last: LocalDate = lastDay
    ) = TripShiftInput(
        plants = plants,
        careLogs = logs,
        tripFirstDay = last.plusDays(1),
        tripLastDay = last.plusDays(tripDays.toLong()),
        seasonalAmplitude = 0.0,
        hemisphere = Hemisphere.NORTHERN
    )

    private fun holds(
        plants: List<Plant>,
        logs: List<CareLog>,
        tripDays: Int,
        last: LocalDate = lastDay,
        today: LocalDate = last.minusDays(2)
    ) = TripWateringShifts.holds(input(plants, logs, tripDays, last), today)

    private fun bringForward(
        plants: List<Plant>,
        logs: List<CareLog>,
        tripDays: Int,
        visitEveryDays: Int,
        last: LocalDate = lastDay
    ) = TripWateringShifts.bringForward(
        input(plants, logs, tripDays, last),
        firstVisit = last.plusDays(visitEveryDays.toLong())
    )

    /** One plant of [interval] last watered [daysAgo] days before L. */
    private fun heldIds(interval: Int, tripDays: Int, daysAgo: Int): List<Long> = holds(
        listOf(plant(interval = interval)),
        listOf(water(1, lastDay.minusDays(daysAgo.toLong()))),
        tripDays
    ).map { it.plant.id }

    private fun broughtIds(interval: Int, tripDays: Int, daysAgo: Int, visitEveryDays: Int): List<Long> =
        bringForward(
            listOf(plant(interval = interval)),
            listOf(water(1, lastDay.minusDays(daysAgo.toLong()))),
            tripDays,
            visitEveryDays
        ).map { it.plant.id }

    // --- lasts the whole trip ---

    @Test
    fun `a plant lasts the whole trip when its interval is at least the trip length`() {
        val first = lastDay.plusDays(1)
        val last = lastDay.plusDays(21)

        assertTrue(TripWateringShifts.lastsWholeTrip(21, first, last))
        assertTrue(TripWateringShifts.lastsWholeTrip(22, first, last))
        assertFalse(TripWateringShifts.lastsWholeTrip(20, first, last))
    }

    @Test
    fun `a one-day trip is lasted by every interval`() {
        val day = lastDay.plusDays(1)

        assertTrue(TripWateringShifts.lastsWholeTrip(1, day, day))
    }

    // --- hold: the worked boundary table ---

    @Test
    fun `a 21-day plant due three days before departure is held for a 21-day trip`() {
        // Watered 24 days before L: due L-3, three days late (within 15% of 21), and due again on L+18.
        val held = holds(listOf(plant()), listOf(water(1, lastDay.minusDays(24))), tripDays = 21)

        assertEquals(listOf(1L), held.map { it.plant.id })
        assertEquals(lastDay.minusDays(3), held.single().dueDate)
    }

    @Test
    fun `one day further outside the tolerance it is no longer a hold`() {
        assertTrue(heldIds(interval = 21, tripDays = 21, daysAgo = 24).isNotEmpty())
        assertTrue(heldIds(interval = 21, tripDays = 21, daysAgo = 25).isEmpty())
    }

    @Test
    fun `a trip one day longer than the interval never holds`() {
        assertTrue(heldIds(interval = 21, tripDays = 22, daysAgo = 24).isEmpty())
        assertTrue(heldIds(interval = 20, tripDays = 21, daysAgo = 23).isEmpty())
    }

    @Test
    fun `a 7-day plant due the day before departure is held for a 7-day trip`() {
        assertEquals(listOf(1L), heldIds(interval = 7, tripDays = 7, daysAgo = 8))
        assertTrue(heldIds(interval = 7, tripDays = 8, daysAgo = 8).isEmpty())
    }

    // --- hold: other boundaries ---

    @Test
    fun `a plant due on the last day at home or later is not held`() {
        assertTrue(heldIds(interval = 21, tripDays = 21, daysAgo = 21).isEmpty())
        assertTrue(heldIds(interval = 21, tripDays = 21, daysAgo = 10).isEmpty())
    }

    @Test
    fun `a plant that lasts the trip when watered on schedule needs no hold`() {
        // 30-day plant, 31 days since watering: due L-1, and due again L+29, after a 21-day trip ends.
        assertTrue(heldIds(interval = 30, tripDays = 21, daysAgo = 31).isEmpty())
    }

    @Test
    fun `a one-day trip holds nothing`() {
        assertTrue(heldIds(interval = 7, tripDays = 1, daysAgo = 8).isEmpty())
    }

    @Test
    fun `there is no hold on the last day at home or after it`() {
        val plants = listOf(plant())
        val logs = listOf(water(1, lastDay.minusDays(24)))

        assertEquals(1, holds(plants, logs, 21, today = lastDay.minusDays(1)).size)
        assertTrue(holds(plants, logs, 21, today = lastDay).isEmpty())
        assertTrue(holds(plants, logs, 21, today = lastDay.plusDays(1)).isEmpty())
    }

    @Test
    fun `holds are ordered by due date, then name, then id`() {
        val plants = listOf(
            plant(1, name = "Zed"),
            plant(2, name = "alpha"),
            plant(3, name = "Beta"),
            plant(4, name = "beta")
        )
        val logs = listOf(
            water(1, lastDay.minusDays(24)),
            water(2, lastDay.minusDays(23)),
            water(3, lastDay.minusDays(24)),
            water(4, lastDay.minusDays(24))
        )

        assertEquals(listOf(3L, 4L, 1L, 2L), holds(plants, logs, 21).map { it.plant.id })
    }

    // --- bring forward: the worked boundary table ---

    @Test
    fun `a 21-day plant 15 days in is brought forward past a 4-day first visit`() {
        // Due L+6, after the first visit on L+4 and inside the 21-day trip; 150 >= 147.
        val brought = bringForward(
            listOf(plant()),
            listOf(water(1, lastDay.minusDays(15))),
            tripDays = 21,
            visitEveryDays = 4
        )

        assertEquals(listOf(1L), brought.map { it.plant.id })
        assertEquals(lastDay.plusDays(6), brought.single().dueDate)
    }

    @Test
    fun `one day short of 70 percent it is not brought forward`() {
        assertTrue(broughtIds(interval = 21, tripDays = 21, daysAgo = 14, visitEveryDays = 4).isEmpty())
    }

    @Test
    fun `a plant the first visit already reaches is left to the existing lists`() {
        assertTrue(broughtIds(interval = 21, tripDays = 21, daysAgo = 15, visitEveryDays = 7).isEmpty())
    }

    @Test
    fun `a 28-day plant 20 days in is brought forward past a 7-day first visit`() {
        assertEquals(listOf(1L), broughtIds(interval = 28, tripDays = 28, daysAgo = 20, visitEveryDays = 7))
    }

    @Test
    fun `a trip longer than the interval never brings forward`() {
        assertTrue(broughtIds(interval = 21, tripDays = 22, daysAgo = 15, visitEveryDays = 4).isEmpty())
        assertTrue(broughtIds(interval = 20, tripDays = 21, daysAgo = 14, visitEveryDays = 4).isEmpty())
    }

    @Test
    fun `a plant due on the trip's last day is not brought forward but the day before is`() {
        // 28-day plant on a 7-day trip: watered 22 days ago is due L+6 (= E - 1), 21 days ago is due L+7 (= E).
        assertEquals(listOf(1L), broughtIds(interval = 28, tripDays = 7, daysAgo = 22, visitEveryDays = 4))
        assertTrue(broughtIds(interval = 28, tripDays = 7, daysAgo = 21, visitEveryDays = 4).isEmpty())
    }

    @Test
    fun `brought-forward plants are ordered by due date, then name, then id`() {
        val plants = listOf(plant(1, name = "Zed"), plant(2, name = "Alpha"), plant(3, name = "Mid"))
        val logs = listOf(
            water(1, lastDay.minusDays(15)),
            water(2, lastDay.minusDays(15)),
            water(3, lastDay.minusDays(16))
        )

        val brought = bringForward(plants, logs, tripDays = 21, visitEveryDays = 4)

        assertEquals(listOf(3L, 2L, 1L), brought.map { it.plant.id })
    }

    // --- who is left out of both rules ---

    // Getters, not values: the logs' instants depend on the time zone, which is pinned in @Before.
    private val holdCase get() = plant() to listOf(water(1, lastDay.minusDays(24)))
    private val bringForwardCase get() = plant() to listOf(water(1, lastDay.minusDays(15)))

    private fun excludedByBothRules(change: (Plant) -> Plant) {
        val (holdPlant, holdLogs) = holdCase
        val (broughtPlant, broughtLogs) = bringForwardCase
        assertTrue(holds(listOf(change(holdPlant)), holdLogs, 21).isEmpty())
        assertTrue(bringForward(listOf(change(broughtPlant)), broughtLogs, 21, 4).isEmpty())
    }

    @Test
    fun `the controls are suggested, so the exclusions below are the plant's doing`() {
        val (holdPlant, holdLogs) = holdCase
        val (broughtPlant, broughtLogs) = bringForwardCase

        assertEquals(1, holds(listOf(holdPlant), holdLogs, 21).size)
        assertEquals(1, bringForward(listOf(broughtPlant), broughtLogs, 21, 4).size)
    }

    @Test
    fun `hidden and archived plants are never suggested`() {
        excludedByBothRules { it.copy(hiddenAt = 1L) }
        excludedByBothRules { it.copy(archivedAt = 1L) }
    }

    @Test
    fun `a plant with watering suspended for dormancy is never suggested`() {
        excludedByBothRules { it.copy(dormancyStartMonth = 6, dormancyEndMonth = 8) }
    }

    @Test
    fun `a plant on the dormant cadence is never suggested`() {
        excludedByBothRules {
            it.copy(dormancyStartMonth = 6, dormancyEndMonth = 8, dormantWateringIntervalDays = 21)
        }
    }

    @Test
    fun `a plant without a watering interval is never suggested`() {
        excludedByBothRules { it.copy(wateringIntervalDays = null) }
    }

    @Test
    fun `a plant with no WATER log is never suggested`() {
        val noLogs = emptyList<CareLog>()
        val fertilizedOnly = listOf(CareLog(plantId = 1, careType = CareType.FERTILIZE, loggedAt = millis(lastDay)))

        assertTrue(holds(listOf(plant()), noLogs, 21).isEmpty())
        assertTrue(bringForward(listOf(plant()), noLogs, 21, 4).isEmpty())
        assertTrue(holds(listOf(plant()), fertilizedOnly, 21).isEmpty())
        assertTrue(bringForward(listOf(plant()), fertilizedOnly, 21, 4).isEmpty())
    }

    @Test
    fun `a liquid-fertilizer plant is suggested for its watering`() {
        val liquid = plant().copy(useLiquidFertilizer = true, fertilizingIntervalDays = 14)

        val held = holds(
            listOf(liquid),
            listOf(water(1, lastDay.minusDays(24)), fertilize(1, lastDay.minusDays(24))),
            21
        )
        val brought = bringForward(
            listOf(liquid),
            listOf(water(1, lastDay.minusDays(15)), fertilize(1, lastDay.minusDays(15))),
            21,
            4
        )

        assertEquals(lastDay.minusDays(3), held.single().dueDate)
        assertEquals(lastDay.plusDays(6), brought.single().dueDate)
    }

    private fun fertilize(plantId: Long, day: LocalDate) =
        CareLog(plantId = plantId, careType = CareType.FERTILIZE, loggedAt = millis(day))

    @Test
    fun `an override that pushes the due date to the trip's last day drops a bring-forward`() {
        val tripLastDay = lastDay.plusDays(21)
        val (broughtPlant, broughtLogs) = bringForwardCase

        val onLastDay = broughtPlant.copy(wateringDueDateOverride = tripLastDay.toStartOfDayMillis())
        val dayBefore = broughtPlant.copy(wateringDueDateOverride = tripLastDay.minusDays(1).toStartOfDayMillis())

        assertTrue(bringForward(listOf(onLastDay), broughtLogs, 21, 4).isEmpty())
        assertEquals(
            tripLastDay.minusDays(1),
            bringForward(listOf(dayBefore), broughtLogs, 21, 4).single().dueDate
        )
    }

    @Test
    fun `an override shows in the hold's due date but the real gap still gates it`() {
        val (holdPlant, holdLogs) = holdCase

        val rescheduled = holdPlant.copy(wateringDueDateOverride = lastDay.minusDays(1).toStartOfDayMillis())
        val pastLastDay = holdPlant.copy(wateringDueDateOverride = lastDay.toStartOfDayMillis())

        assertEquals(lastDay.minusDays(1), holds(listOf(rescheduled), holdLogs, 21).single().dueDate)
        assertTrue(holds(listOf(pastLastDay), holdLogs, 21).isEmpty())
    }

    // --- DST: calendar days, never milliseconds ---

    @Test
    fun `a hold is judged in calendar days across the autumn clock change`() {
        // Berlin falls back on 2026-10-25: W (Oct 3-4) is in summer time, L (Oct 28) in winter time.
        val last = LocalDate.of(2026, 10, 28)
        val plants = listOf(plant())
        fun held(wateredDaysAgo: Long, hour: Int, minute: Int) = holds(
            plants,
            listOf(water(1, last.minusDays(wateredDaysAgo), hour, minute)),
            tripDays = 21,
            last = last
        ).isNotEmpty()

        assertTrue(held(24, 12, 0))
        assertTrue(held(24, 23, 30))
        // 25 calendar days, though only 24 days and 13.5 hours (plus the extra hour) of milliseconds.
        assertFalse(held(25, 23, 30))
        assertFalse(held(25, 12, 0))
    }

    @Test
    fun `a bring-forward is judged in calendar days across the autumn clock change`() {
        // W (Oct 18) is in summer time, L (Nov 2) in winter time.
        val last = LocalDate.of(2026, 11, 2)
        val plants = listOf(plant())
        fun brought(wateredDaysAgo: Long, hour: Int, minute: Int) = bringForward(
            plants,
            listOf(water(1, last.minusDays(wateredDaysAgo), hour, minute)),
            tripDays = 21,
            visitEveryDays = 4,
            last = last
        ).isNotEmpty()

        assertTrue(brought(15, 12, 0))
        // 15 calendar days, though only 14 days and 12.5 hours (plus the extra hour) of milliseconds.
        assertTrue(brought(15, 23, 30))
        assertFalse(brought(14, 0, 30))
    }

    // --- the property: a hold is on schedule by the real reason-prompt gate ---

    @Test
    fun `every hold is on schedule for the reason gate and none that is on schedule is missed`() {
        for (interval in 1..MAX_INTERVAL_DAYS) {
            for (tripDays in 1..MAX_TRIP_DAYS) {
                assertHoldsMatchTheGate(interval, tripDays)
            }
        }
    }

    /** One plant per gap (days from the last watering to L), all with the same [interval]. */
    private fun assertHoldsMatchTheGate(interval: Int, tripDays: Int) {
        val noon = lastDay.toNoonMillis()
        val gaps = 1..MAX_GAP_DAYS
        val plants = gaps.map { gap -> plant(gap.toLong(), interval) }
        val logs = gaps.map { gap -> water(gap.toLong(), lastDay.minusDays(gap.toLong())) }
        val held = holds(plants, logs, tripDays).map { it.plant.id.toInt() }.toSet()
        for (gap in gaps) {
            val lastWateredAt = logs[gap - 1].loggedAt
            val onSchedule = isChosenDateOnSchedule(plants[gap - 1], lastWateredAt, noon, 0.0)
            // Due before L (gap > interval), and due again inside the trip when left alone.
            val expected = interval >= tripDays && gap > interval && 2 * interval - gap < tripDays && onSchedule
            val label = "interval=$interval trip=$tripDays gap=$gap"
            assertEquals(label, expected, gap in held)
            if (gap in held) {
                assertTrue(label, CareSchedule.isWateringOnScheduleAt(lastWateredAt, interval, noon))
                assertEquals(label, WateringGateResult.Log(null), gateResult(plants[gap - 1], noon, lastWateredAt))
            }
        }
    }

    private fun gateResult(plant: Plant, loggedAt: Long, previousWateringAt: Long) = evaluateWateringGate(
        WateringGateInputs(plant, 0.0, emptyList()),
        loggedAt,
        previousWateringAt,
        soilTooDry = false
    )

    @Test
    fun `the tolerance edge where the arithmetic is exactly 15 percent follows the real gate`() {
        // 20-day plant watered 23 days before L is exactly 15% late (3 of 20).
        val onEdge = heldIds(interval = 20, tripDays = 20, daysAgo = 23).isNotEmpty()
        val expected = CareSchedule.isWateringOnScheduleAt(
            millis(lastDay.minusDays(23)),
            20,
            lastDay.toNoonMillis()
        )

        assertEquals(expected, onEdge)
    }

    private companion object {
        const val MAX_INTERVAL_DAYS = 40
        const val MAX_TRIP_DAYS = 45
        const val MAX_GAP_DAYS = 60
    }
}
