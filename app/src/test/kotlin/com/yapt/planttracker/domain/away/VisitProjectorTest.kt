package com.yapt.planttracker.domain.away

import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.SitterVisit
import com.yapt.planttracker.domain.model.SitterVisitPlant
import com.yapt.planttracker.domain.model.SitterVisitState
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.domain.schedule.SeasonalWatering
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
 * The sitter visit projection (#899, product ADR-0065). Due dates come from the Care queue's aggregator;
 * the cases below pin the matching rule, the simulated waterings and the notes that hang off them.
 */
class VisitProjectorTest {

    private val originalTimeZone: TimeZone = TimeZone.getDefault()
    private val today = LocalDate.of(2026, 10, 14)
    private val trip = AwayPeriod(id = 1L, firstDay = LocalDate.of(2026, 10, 8), lastDay = LocalDate.of(2026, 10, 28))

    @Before
    fun pinTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

    private fun millis(date: LocalDate): Long =
        date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Suppress("LongParameterList")
    private fun plant(
        id: Long,
        interval: Int? = 5,
        name: String = "Plant $id",
        pinned: Boolean = true,
        base: Double? = null,
        dormancyStart: Int? = null,
        dormancyEnd: Int? = null,
        cadence: Int? = null,
        liquid: Boolean = false,
        fertilizingInterval: Int? = null,
        override: LocalDate? = null,
        archived: Boolean = false,
        room: String? = null,
        photo: String? = null
    ) = Plant(
        id = id,
        name = name,
        room = room,
        coverPhotoUri = photo,
        wateringIntervalDays = interval,
        wateringBaseIntervalDays = base,
        pinIntervalToBase = pinned,
        dormancyStartMonth = dormancyStart,
        dormancyEndMonth = dormancyEnd,
        dormantWateringIntervalDays = cadence,
        useLiquidFertilizer = liquid,
        fertilizingIntervalDays = fertilizingInterval,
        wateringDueDateOverride = override?.let(::millis),
        archivedAt = if (archived) 1L else null,
        createdAt = millis(today.minusDays(200)),
        updatedAt = millis(today.minusDays(200))
    )

    private fun water(plantId: Long, date: LocalDate) =
        CareLog(plantId = plantId, careType = CareType.WATER, loggedAt = millis(date))

    private fun fertilize(plantId: Long, date: LocalDate) =
        CareLog(plantId = plantId, careType = CareType.FERTILIZE, loggedAt = millis(date))

    private fun visit(
        day: LocalDate,
        state: SitterVisitState,
        vararg plants: Pair<Long, Boolean?>
    ) = SitterVisit(
        id = day.toEpochDay(),
        awayPeriodId = trip.id,
        day = day,
        state = state,
        plants = plants.map { SitterVisitPlant(it.first, it.second) }
    )

    @Suppress("LongParameterList")
    private fun project(
        plants: List<Plant>,
        logs: List<CareLog> = emptyList(),
        visits: List<SitterVisit> = emptyList(),
        visitDay: LocalDate = today,
        gap: Int = 7,
        now: LocalDate = today,
        amplitude: Double = 0.0
    ) = VisitProjector.project(
        VisitProjectionInput(
            plants = plants,
            careLogs = logs,
            trip = trip,
            visits = visits,
            visitDay = visitDay,
            gapDays = gap,
            today = now,
            seasonalAmplitude = amplitude,
            hemisphere = Hemisphere.NORTHERN
        )
    )

    private fun VisitProjection.listedIds() = listed.map { it.plant.id }

    private fun day(dayOfMonth: Int) = LocalDate.of(2026, 10, dayOfMonth)

    @Test
    fun `a plant due on or before the visit day is listed`() {
        val result = project(
            plants = listOf(plant(1, interval = 3), plant(2, interval = 3)),
            logs = listOf(water(1, day(10)), water(2, day(11)))
        )

        assertEquals(listOf(1L, 2L), result.listedIds())
        assertEquals(listOf(day(13), day(14)), result.listed.map { it.dueDate })
    }

    @Test
    fun `a plant due strictly before halfway to the next visit is listed`() {
        val plants = listOf(plant(1, interval = 4), plant(2, interval = 5))
        val logs = listOf(water(1, day(12)), water(2, day(12)))

        val result = project(plants, logs, gap = 6)

        // v = Oct 14, g = 6: halfway is Oct 17. Due Oct 16 is before it, due Oct 17 is the tie.
        assertEquals(day(16), result.includesDueBy)
        assertEquals(listOf(1L), result.listedIds())
    }

    @Test
    fun `a plant due exactly halfway goes to the next visit`() {
        val result = project(listOf(plant(1, interval = 6)), listOf(water(1, day(11))), gap = 6)

        // Due Oct 17 = v + g/2: a tie, so the plan leans dry.
        assertTrue(result.listed.isEmpty())
    }

    @Test
    fun `an odd gap rounds the halfway point toward the next visit`() {
        val plants = listOf(plant(1, interval = 6), plant(2, interval = 7))
        val logs = listOf(water(1, day(11)), water(2, day(11)))

        val result = project(plants, logs, gap = 7)

        // v + 3.5: due Oct 17 is before it, due Oct 18 is after.
        assertEquals(day(17), result.includesDueBy)
        assertEquals(listOf(1L), result.listedIds())
    }

    @Test
    fun `a one day gap lists only what is due by the visit`() {
        val plants = listOf(plant(1, interval = 3), plant(2, interval = 4))
        val logs = listOf(water(1, day(11)), water(2, day(11)))

        val result = project(plants, logs, gap = 1)

        assertEquals(day(14), result.includesDueBy)
        assertEquals(listOf(1L), result.listedIds())
    }

    @Test
    fun `a confirmed visit counts only the plants that were watered and lists the skipped one again`() {
        val plants = listOf(plant(1, interval = 10), plant(2, interval = 3))
        val logs = listOf(water(1, day(5)), water(2, day(5)))
        val visits = listOf(visit(day(10), SitterVisitState.CONFIRMED, 1L to true, 2L to false))

        val result = project(plants, logs, visits)

        assertEquals(listOf(2L), result.listedIds())
        assertTrue(result.listed.single().missedLastTime)
    }

    @Test
    fun `a plant the sitter did water is not tagged and waits its full interval`() {
        val plants = listOf(plant(1, interval = 10), plant(2, interval = 3))
        val logs = listOf(water(1, day(5)), water(2, day(5)))
        val visits = listOf(visit(day(10), SitterVisitState.CONFIRMED, 1L to true, 2L to true))

        val result = project(plants, logs, visits)

        // Plant 2 watered on the 10th is due on the 13th, so it is back on the list, but not as missed.
        assertEquals(listOf(2L), result.listedIds())
        assertFalse(result.listed.single().missedLastTime)
    }

    @Test
    fun `an unconfirmed previous visit counts its whole list as watered`() {
        val plants = listOf(plant(1, interval = 10), plant(2, interval = 10))
        val logs = listOf(water(1, day(1)), water(2, day(1)))
        val visits = listOf(visit(day(10), SitterVisitState.PLANNED, 1L to null, 2L to null))

        val result = project(plants, logs, visits)

        assertTrue(result.listed.isEmpty())
    }

    @Test
    fun `without that visit the same plants are overdue and listed`() {
        val result = project(listOf(plant(1, interval = 10)), listOf(water(1, day(1))))

        assertEquals(listOf(1L), result.listedIds())
    }

    @Test
    fun `a visit that did not happen counts nothing`() {
        val plants = listOf(plant(1, interval = 10))
        val logs = listOf(water(1, day(1)))
        val visits = listOf(visit(day(10), SitterVisitState.DID_NOT_HAPPEN, 1L to null))

        val result = project(plants, logs, visits)

        assertEquals(listOf(1L), result.listedIds())
        assertFalse(result.listed.single().missedLastTime)
    }

    @Test
    fun `missed tags come only from the previous visit when it was confirmed`() {
        val plants = listOf(plant(1, interval = 3), plant(2, interval = 3))
        val logs = listOf(water(1, day(1)), water(2, day(1)))
        val confirmedMissed = visit(day(8), SitterVisitState.CONFIRMED, 1L to false, 2L to true)
        val unconfirmedLater = visit(day(11), SitterVisitState.PLANNED, 2L to null)

        val staleTag = project(plants, logs, listOf(confirmedMissed, unconfirmedLater))
        val freshTag = project(plants, logs, listOf(confirmedMissed))

        assertTrue(staleTag.listed.none { it.missedLastTime })
        assertEquals(listOf(1L), freshTag.listed.filter { it.missedLastTime }.map { it.plant.id })
    }

    @Test
    fun `a planned visit on the same day is replaced but a confirmed one counts`() {
        val plants = listOf(plant(1, interval = 10))
        val logs = listOf(water(1, day(1)))

        val replaced = project(plants, logs, listOf(visit(today, SitterVisitState.PLANNED, 1L to null)))
        val counted = project(plants, logs, listOf(visit(today, SitterVisitState.CONFIRMED, 1L to true)))

        assertEquals(listOf(1L), replaced.listedIds())
        assertTrue(counted.listed.isEmpty())
    }

    @Test
    fun `a visit after the planned day is ignored`() {
        val plants = listOf(plant(1, interval = 10))
        val logs = listOf(water(1, day(1)))

        val result = project(plants, logs, listOf(visit(day(20), SitterVisitState.PLANNED, 1L to null)))

        assertEquals(listOf(1L), result.listedIds())
    }

    @Test
    fun `a dormant plant on a cadence is listed by its cadence`() {
        val dormant = plant(1, interval = 3, dormancyStart = 10, dormancyEnd = 11, cadence = 7)
        val slower = plant(2, interval = 3, dormancyStart = 10, dormancyEnd = 11, cadence = 28)

        val result = project(listOf(dormant, slower), listOf(water(1, day(8)), water(2, day(8))))

        assertEquals(listOf(1L), result.listedIds())
        assertEquals(7, result.listed.single().usualIntervalDays)
        assertEquals(listOf(2L), result.resting.map { it.id })
    }

    @Test
    fun `a plant whose watering is suspended is never listed and goes on the resting page`() {
        val suspended = plant(1, interval = 3, dormancyStart = 10, dormancyEnd = 11)

        val result = project(listOf(suspended, plant(2, interval = 3)), listOf(water(2, day(10))))

        assertEquals(listOf(2L), result.listedIds())
        assertEquals(listOf(1L), result.resting.map { it.id })
    }

    @Test
    fun `a resting plant that is on the list is not also listed as resting`() {
        val cadence = plant(1, interval = 3, dormancyStart = 10, dormancyEnd = 11, cadence = 7)

        val result = project(listOf(cadence), listOf(water(1, day(8))))

        assertEquals(listOf(1L), result.listedIds())
        assertTrue(result.resting.isEmpty())
    }

    @Test
    fun `seasonal intervals decide the due date, not the stored literal`() {
        val seasonal = plant(1, interval = 7, pinned = false, base = 14.0)
        val effective = SeasonalWatering.effectiveInterval(14.0, today, 0.5, Hemisphere.NORTHERN)
        assertTrue("the case must tell the base from the literal", effective > 7)
        val logs = listOf(water(1, day(10)))

        val seasonalResult = project(listOf(seasonal), logs, amplitude = 0.5)
        val pinnedResult = project(listOf(seasonal.copy(pinIntervalToBase = true)), logs, amplitude = 0.5)

        assertTrue(seasonalResult.listed.isEmpty())
        assertEquals(listOf(1L), pinnedResult.listedIds())
    }

    @Test
    fun `a liquid fertilizer plant is listed once, for watering only`() {
        val liquid = plant(1, interval = 3, liquid = true, fertilizingInterval = 10)
        val logs = listOf(water(1, day(10)), fertilize(1, day(1)))

        val result = project(listOf(liquid), logs)

        assertEquals(listOf(1L), result.listedIds())
    }

    @Test
    fun `a liquid fertilizer plant is not listed for fertilizing alone`() {
        val liquid = plant(1, interval = 30, liquid = true, fertilizingInterval = 10)
        val logs = listOf(water(1, day(13)), fertilize(1, day(1)))

        assertTrue(project(listOf(liquid), logs).listed.isEmpty())
    }

    @Test
    fun `archived plants never appear and plants without a schedule are not listed`() {
        val plants = listOf(plant(1, archived = true), plant(2, interval = null), plant(3, interval = 3))

        val result = project(plants, listOf(water(3, day(10))))

        assertEquals(listOf(3L), result.listedIds())
        assertTrue(result.resting.isEmpty())
    }

    @Test
    fun `a pre-trip watering not yet logged is assumed done on the last day at home`() {
        val plants = listOf(plant(1, interval = 10), plant(2, interval = 11))

        val result = project(plants)

        // Never watered, so due at the last day at home (Oct 7): watered then, due Oct 17 and Oct 18.
        assertEquals(listOf(1L), result.listedIds())
        assertEquals(day(17), result.listed.single().dueDate)
    }

    @Test
    fun `a real watering after the last day at home wins over the assumed one`() {
        val result = project(listOf(plant(1, interval = 10)), listOf(water(1, day(12))))

        assertTrue(result.listed.isEmpty())
    }

    @Test
    fun `before the trip starts the plan still assumes everything due is watered on the last day at home`() {
        val plants = listOf(plant(1, interval = 7), plant(2, interval = 7))
        val logs = listOf(water(1, day(1)), water(2, day(2)))

        val result = project(plants, logs, visitDay = day(8), now = day(1))

        // Plant 1 would be due Oct 8 (it falls after the last day at home, Oct 7: no assumed watering);
        // plant 2 is due Oct 9: both within the visit's reach (Oct 11).
        assertEquals(listOf(1L, 2L), result.listedIds())
        assertEquals(listOf(day(8), day(9)), result.listed.map { it.dueDate })
    }

    @Test
    fun `only a plant due by the last day at home is assumed watered then`() {
        val logs = listOf(water(1, day(1)))

        val result = project(listOf(plant(1, interval = 7)), logs, visitDay = day(8), now = day(1))

        // Due Oct 8, a day after the last day at home, so nothing is assumed: it is due on the visit itself.
        assertEquals(listOf(1L), result.listedIds())

        val earlier = project(listOf(plant(1, interval = 3)), logs, visitDay = day(8), now = day(1))

        // Due Oct 4: assumed watered on Oct 7, next due Oct 10, inside the visit's reach (Oct 11).
        assertEquals(listOf(day(10)), earlier.listed.map { it.dueDate })
    }

    @Test
    fun `a simulated watering clears an active reschedule as a real one does`() {
        val rescheduled = plant(1, interval = 3, override = day(30))
        val logs = listOf(water(1, day(1)))

        val withoutVisit = project(listOf(rescheduled), logs)
        val withVisit = project(
            listOf(rescheduled),
            logs,
            listOf(visit(day(10), SitterVisitState.CONFIRMED, 1L to true))
        )

        assertTrue(withoutVisit.listed.isEmpty())
        assertEquals(listOf(day(13)), withVisit.listed.map { it.dueDate })
    }

    @Test
    fun `longer than usual compares the gap with one and a half times the interval`() {
        val plants = listOf(plant(1, interval = 3), plant(2, interval = 5), plant(3, interval = 7))
        val logs = plants.map { water(it.id, day(10)) }

        val result = project(plants, logs, gap = 7)

        val byId = result.listed.associate { it.plant.id to it.longerThanUsual }
        // g = 7: 7 >= 4.5 flags the 3-day plant; 7 < 7.5 and 7 < 10.5 leave the others.
        assertEquals(mapOf(1L to true, 2L to false, 3L to false), byId)
    }

    @Test
    fun `usual interval is carried for each listed plant`() {
        val result = project(listOf(plant(1, interval = 4)), listOf(water(1, day(10))))

        assertEquals(4, result.listed.single().usualIntervalDays)
    }
}
