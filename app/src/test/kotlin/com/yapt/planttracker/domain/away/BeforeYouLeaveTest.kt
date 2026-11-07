package com.yapt.planttracker.domain.away

import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.Hemisphere
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * Before you leave (#899, product ADR-0065): the halfway split, its strict boundary, and who is left out; and the
 * plants brought forward for a trip they would otherwise not last (#969, product ADR-0076).
 */
class BeforeYouLeaveTest {

    private val originalTimeZone: TimeZone = TimeZone.getDefault()
    private val today = LocalDate.of(2026, 10, 7)

    @Before
    fun pinTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

    private fun day(dayOfMonth: Int) = LocalDate.of(2026, 10, dayOfMonth)

    @Suppress("LongParameterList")
    private fun plant(
        id: Long,
        interval: Int? = 7,
        archived: Boolean = false,
        dormancyStart: Int? = null,
        dormancyEnd: Int? = null
    ) = Plant(
        id = id,
        name = "Plant $id",
        wateringIntervalDays = interval,
        pinIntervalToBase = true,
        dormancyStartMonth = dormancyStart,
        dormancyEndMonth = dormancyEnd,
        archivedAt = if (archived) 1L else null,
        createdAt = 0L,
        updatedAt = 0L
    )

    /** A plant whose next watering is due on [due], given its [interval]. */
    private fun dueOn(id: Long, due: LocalDate, interval: Int = 7) =
        plant(id, interval) to CareLog(
            plantId = id,
            careType = CareType.WATER,
            loggedAt = due.minusDays(interval.toLong()).atTime(12, 0).atZone(ZoneId.systemDefault())
                .toInstant().toEpochMilli()
        )

    private fun plan(
        entries: List<Pair<Plant, CareLog>>,
        visitEveryDays: Int? = 7,
        extraPlants: List<Plant> = emptyList(),
        tripDays: Int = 10,
        extraLogs: List<CareLog> = emptyList()
    ) = BeforeYouLeave.plan(
        BeforeYouLeaveInput(
            plants = entries.map { it.first } + extraPlants,
            careLogs = entries.map { it.second } + extraLogs,
            today = today,
            visitEveryDays = visitEveryDays,
            seasonalAmplitude = 0.0,
            hemisphere = Hemisphere.NORTHERN,
            tripFirstDay = today.plusDays(1),
            tripLastDay = today.plusDays(tripDays.toLong())
        )
    )

    private fun List<WateringDue>.ids() = map { it.plant.id }

    private fun log(plantId: Long, type: CareType, day: LocalDate, hour: Int = 12, minute: Int = 0) = CareLog(
        plantId = plantId,
        careType = type,
        loggedAt = day.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    )

    @Test
    fun `the first visit is the last day at home plus the rhythm`() {
        assertEquals(day(14), plan(emptyList(), visitEveryDays = 7).firstVisit)
        assertEquals(day(11), plan(emptyList(), visitEveryDays = 4).firstVisit)
    }

    @Test
    fun `an unset rhythm defaults to seven days`() {
        assertEquals(day(14), plan(emptyList(), visitEveryDays = null).firstVisit)
    }

    @Test
    fun `plants due before halfway are watered today and plants due from halfway are checked`() {
        val entries = listOf(
            dueOn(1, day(6)),
            dueOn(2, day(10)),
            dueOn(3, day(11)),
            // 10 days, not 7: a 7-day plant due on the visit day was watered today, so it is in neither list (#1018).
            dueOn(4, day(14), interval = 10),
            dueOn(5, day(15))
        )

        val result = plan(entries)

        // Halfway to Oct 14 from Oct 7 is Oct 10.5: due Oct 10 is before it, Oct 11 is after.
        assertEquals(listOf(1L, 2L), result.waterToday.ids())
        assertEquals(listOf(3L, 4L), result.checkTheSoil.ids())
        assertEquals(1, result.canWaitCount)
    }

    @Test
    fun `a plant due exactly halfway is checked, not watered`() {
        val entries = listOf(dueOn(1, day(9)), dueOn(2, day(10)))

        val result = plan(entries, visitEveryDays = 6)

        // Halfway to Oct 13 is Oct 10 exactly: the strict boundary sends it to Check the soil.
        assertEquals(listOf(1L), result.waterToday.ids())
        assertEquals(listOf(2L), result.checkTheSoil.ids())
    }

    @Test
    fun `an overdue plant is watered today`() {
        val result = plan(listOf(dueOn(1, day(1))))

        assertEquals(listOf(1L), result.waterToday.ids())
    }

    @Test
    fun `the visit day itself still counts as check the soil and the day after can wait`() {
        // 10 days, not 7: a 7-day plant due on the visit day was watered today, so it is in neither list (#1018).
        val result = plan(listOf(dueOn(1, day(14), interval = 10), dueOn(2, day(15))))

        assertEquals(listOf(1L), result.checkTheSoil.ids())
        assertEquals(1, result.canWaitCount)
    }

    @Test
    fun `archived resting and unscheduled plants are in neither list`() {
        val entries = listOf(dueOn(1, day(7)))
        val others = listOf(
            plant(2, archived = true),
            plant(3, dormancyStart = 10, dormancyEnd = 11),
            plant(4, interval = null)
        )

        val result = plan(entries, extraPlants = others)

        assertEquals(listOf(1L), result.waterToday.ids())
        assertTrue(result.checkTheSoil.isEmpty())
        // Active plants: 1, 3 and 4. Plant 3 and 4 can wait; the archived one is not counted.
        assertEquals(2, result.canWaitCount)
    }

    @Test
    fun `hidden plants are in neither list and are not counted`() {
        val entries = listOf(dueOn(1, day(7)))
        val hidden = plant(2).copy(hiddenAt = 1L)

        val result = plan(entries, extraPlants = listOf(hidden))

        assertEquals(listOf(1L), result.waterToday.ids())
        assertTrue(result.checkTheSoil.isEmpty())
        assertEquals(0, result.canWaitCount)
    }

    @Test
    fun `due dates reflect the logged waterings only, nothing is simulated`() {
        val unwatered = plant(1, interval = 30)

        val result = plan(emptyList(), extraPlants = listOf(unwatered))

        // Never watered, so due today: Water today, with no assumed earlier watering.
        assertEquals(listOf(1L), result.waterToday.ids())
        assertEquals(today, result.waterToday.single().dueDate)
    }

    @Test
    fun `a plant that would come due inside the trip is checked once it is 70 percent through`() {
        // 21-day plant watered 15 days ago, due in 6 days: after the first visit (Oct 11), inside the 21-day trip.
        val result = plan(listOf(dueOn(1, day(13), interval = 21)), visitEveryDays = 4, tripDays = 21)

        assertTrue(result.waterToday.isEmpty())
        assertEquals(listOf(1L), result.checkTheSoil.ids())
        assertEquals(day(13), result.checkTheSoil.single().dueDate)
        assertEquals(0, result.canWaitCount)
    }

    @Test
    fun `a plant just short of 70 percent through is not added`() {
        val result = plan(listOf(dueOn(1, day(14), interval = 21)), visitEveryDays = 4, tripDays = 21)

        assertTrue(result.checkTheSoil.isEmpty())
        assertEquals(1, result.canWaitCount)
    }

    @Test
    fun `a plant already in check the soil is not listed twice`() {
        // With a 7-day rhythm the first visit is Oct 14, so a plant due Oct 13 is already Check the soil.
        val result = plan(listOf(dueOn(1, day(13), interval = 21)), visitEveryDays = 7, tripDays = 21)

        assertEquals(listOf(1L), result.checkTheSoil.ids())
        assertTrue(result.waterToday.isEmpty())
        assertEquals(0, result.canWaitCount)
    }

    @Test
    fun `brought-forward plants follow the existing check the soil rows`() {
        val entries = listOf(
            dueOn(1, day(13), interval = 21),
            dueOn(2, day(10), interval = 7),
            dueOn(3, day(12), interval = 7)
        )

        val result = plan(entries, visitEveryDays = 4, tripDays = 21)

        // First visit Oct 11: plant 2 (Oct 10) is Check the soil, plant 3 (Oct 12) is later but only 4 of 7 days
        // through, and plant 1 is brought forward after the rows that were already there.
        assertEquals(listOf(2L, 1L), result.checkTheSoil.ids())
        assertEquals(1, result.canWaitCount)
    }

    @Test
    fun `the can wait count leaves out brought-forward plants`() {
        val entries = listOf(dueOn(1, day(13), interval = 21), dueOn(2, day(30), interval = 30))

        val result = plan(entries, visitEveryDays = 4, tripDays = 21)

        assertEquals(listOf(1L), result.checkTheSoil.ids())
        assertEquals(1, result.canWaitCount)
    }

    @Test
    fun `a plant that cannot last the whole trip is not brought forward`() {
        // A 20-day plant is exactly 70% through (14 of 20 days) and due after the first visit, but it runs out
        // one day before a 21-day trip ends.
        val nearMiss = dueOn(1, day(13), interval = 20)

        val result = plan(listOf(nearMiss), visitEveryDays = 4, tripDays = 21)

        assertTrue(result.checkTheSoil.isEmpty())
        assertEquals(1, result.canWaitCount)
    }

    @Test
    fun `a hidden plant that fits the rule is neither brought forward nor counted`() {
        val (plant, log) = dueOn(1, day(13), interval = 21)

        val result = plan(listOf(plant.copy(hiddenAt = 1L) to log), visitEveryDays = 4, tripDays = 21)

        assertTrue(result.checkTheSoil.isEmpty())
        assertEquals(0, result.canWaitCount)
    }

    // --- already watered today (#1018) ---

    @Test
    fun `a plant watered today is in neither list even when it comes due again before the first visit`() {
        // The reported case: a 3-day plant watered today is due Oct 10, before halfway (Oct 11) to a visit 10 days
        // out, so it used to be listed under Water today although a second watering would be rejected.
        val wateredToday = dueOn(1, day(10), interval = 3)
        val dueNow = dueOn(2, today)

        val result = plan(listOf(wateredToday, dueNow), visitEveryDays = 10)

        assertEquals(listOf(2L), result.waterToday.ids())
        assertTrue(result.checkTheSoil.isEmpty())
        assertEquals(1, result.wateredTodayCount)
        assertEquals(0, result.canWaitCount)
    }

    @Test
    fun `a plant watered today that is not due again soon is counted as watered, not as can wait`() {
        val result = plan(listOf(dueOn(1, today.plusDays(30), interval = 30), dueOn(2, day(30), interval = 30)))

        assertTrue(result.waterToday.isEmpty())
        assertTrue(result.checkTheSoil.isEmpty())
        assertEquals(1, result.wateredTodayCount)
        assertEquals(1, result.canWaitCount)
    }

    @Test
    fun `a plant watered today is not brought forward for the trip`() {
        // A 21-day plant watered today lasts a 21-day trip on its own; the bring-forward rule needs it 70% through.
        val result = plan(listOf(dueOn(1, day(28), interval = 21)), visitEveryDays = 4, tripDays = 21)

        assertTrue(result.checkTheSoil.isEmpty())
        assertEquals(1, result.wateredTodayCount)
        assertEquals(0, result.canWaitCount)
    }

    @Test
    fun `a watering late yesterday or a fertilizing today does not count as watered today`() {
        val daily = plant(1, interval = 1)
        val weekly = plant(2, interval = 7)
        val logs = listOf(
            log(1, CareType.WATER, today.minusDays(1), hour = 23, minute = 59),
            log(2, CareType.WATER, today.minusDays(7)),
            log(2, CareType.FERTILIZE, today)
        )

        val result = plan(emptyList(), extraPlants = listOf(daily, weekly), extraLogs = logs)

        assertEquals(listOf(1L, 2L), result.waterToday.ids())
        assertEquals(0, result.wateredTodayCount)
    }

    @Test
    fun `hidden and archived plants watered today are not counted`() {
        val hidden = plant(1).copy(hiddenAt = 1L)
        val archived = plant(2, archived = true)
        val logs = listOf(log(1, CareType.WATER, today), log(2, CareType.WATER, today))

        val result = plan(emptyList(), extraPlants = listOf(hidden, archived), extraLogs = logs)

        assertEquals(0, result.wateredTodayCount)
        assertEquals(0, result.canWaitCount)
    }

    // --- rescheduled today (#1019, product ADR-0081) ---

    private fun at(day: LocalDate, hour: Int = 9) =
        day.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun rescheduled(
        entry: Pair<Plant, CareLog>,
        overrideDay: LocalDate,
        madeOn: LocalDate?
    ) = entry.first.copy(
        wateringDueDateOverride = at(overrideDay),
        wateringDueDateOverrideMadeAt = madeOn?.let { at(it, hour = 8) }
    ) to entry.second

    @Test
    fun `a plant rescheduled today is left out and counted as can wait`() {
        val entry = rescheduled(dueOn(1, today), overrideDay = day(10), madeOn = today)

        val result = plan(listOf(entry))

        assertTrue(result.waterToday.isEmpty())
        assertTrue(result.checkTheSoil.isEmpty())
        assertEquals(1, result.canWaitCount)
        assertEquals(0, result.wateredTodayCount)
    }

    @Test
    fun `a plant rescheduled yesterday is still listed by its due date`() {
        val entry = rescheduled(dueOn(1, today), overrideDay = day(10), madeOn = today.minusDays(1))

        val result = plan(listOf(entry))

        assertEquals(listOf(1L), result.waterToday.ids())
        assertEquals(day(10), result.waterToday.single().dueDate)
        assertEquals(0, result.canWaitCount)
    }

    @Test
    fun `an override with no timestamp is still listed by its due date`() {
        val entry = rescheduled(dueOn(1, today), overrideDay = day(10), madeOn = null)

        val result = plan(listOf(entry))

        assertEquals(listOf(1L), result.waterToday.ids())
        assertEquals(0, result.canWaitCount)
    }

    @Test
    fun `an override that does not win the maxOf is not active even when stamped today`() {
        val entry = rescheduled(dueOn(1, day(12)), overrideDay = day(10), madeOn = today)

        val result = plan(listOf(entry))

        assertEquals(listOf(1L), result.checkTheSoil.ids())
        assertEquals(day(12), result.checkTheSoil.single().dueDate)
        assertEquals(0, result.canWaitCount)
    }

    @Test
    fun `a plant rescheduled today is left out whatever the new date is`() {
        val beforeFirstVisit = rescheduled(dueOn(1, today), overrideDay = day(8), madeOn = today)
        val insideTheTrip = rescheduled(dueOn(2, today), overrideDay = day(16), madeOn = today)

        val result = plan(listOf(beforeFirstVisit, insideTheTrip))

        assertTrue(result.waterToday.isEmpty())
        assertTrue(result.checkTheSoil.isEmpty())
        assertEquals(2, result.canWaitCount)
    }

    @Test
    fun `a plant rescheduled today is not brought forward for the trip`() {
        val base = dueOn(1, day(12), interval = 21)
        val broughtForward = rescheduled(base, overrideDay = day(13), madeOn = today.minusDays(1))
        val leftOut = rescheduled(base, overrideDay = day(13), madeOn = today)

        val before = plan(listOf(broughtForward), visitEveryDays = 4, tripDays = 21)
        val after = plan(listOf(leftOut), visitEveryDays = 4, tripDays = 21)

        assertEquals(listOf(1L), before.checkTheSoil.ids())
        assertTrue(after.checkTheSoil.isEmpty())
        assertEquals(1, after.canWaitCount)
    }

    @Test
    fun `a plant rescheduled and watered today counts as watered today`() {
        val entry = rescheduled(dueOn(1, today), overrideDay = day(10), madeOn = today)

        val result = plan(listOf(entry), extraLogs = listOf(log(1, CareType.WATER, today)))

        assertEquals(1, result.wateredTodayCount)
        assertEquals(0, result.canWaitCount)
    }
}
