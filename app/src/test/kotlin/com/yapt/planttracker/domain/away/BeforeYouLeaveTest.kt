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
        tripDays: Int = 10
    ) = BeforeYouLeave.plan(
        BeforeYouLeaveInput(
            plants = entries.map { it.first } + extraPlants,
            careLogs = entries.map { it.second },
            today = today,
            visitEveryDays = visitEveryDays,
            seasonalAmplitude = 0.0,
            hemisphere = Hemisphere.NORTHERN,
            tripFirstDay = today.plusDays(1),
            tripLastDay = today.plusDays(tripDays.toLong())
        )
    )

    private fun List<WateringDue>.ids() = map { it.plant.id }

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
            dueOn(4, day(14)),
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
        val result = plan(listOf(dueOn(1, day(14)), dueOn(2, day(15))))

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
        // A 7-day plant is fully through its interval but needs the sitter either way; a 20-day plant is
        // exactly 70% through yet runs out one day before a 21-day trip ends.
        val short = dueOn(1, day(12), interval = 7)
        val nearMiss = dueOn(2, day(13), interval = 20)

        val result = plan(listOf(short, nearMiss), visitEveryDays = 4, tripDays = 21)

        assertTrue(result.checkTheSoil.isEmpty())
        assertEquals(2, result.canWaitCount)
    }

    @Test
    fun `a hidden plant that fits the rule is neither brought forward nor counted`() {
        val (plant, log) = dueOn(1, day(13), interval = 21)

        val result = plan(listOf(plant.copy(hiddenAt = 1L) to log), visitEveryDays = 4, tripDays = 21)

        assertTrue(result.checkTheSoil.isEmpty())
        assertEquals(0, result.canWaitCount)
    }
}
