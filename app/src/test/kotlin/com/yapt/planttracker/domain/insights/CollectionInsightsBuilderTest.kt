package com.yapt.planttracker.domain.insights

import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantIssue
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.util.toStartOfDayMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class CollectionInsightsBuilderTest {

    private val today = LocalDate.of(2026, 7, 15)
    private val now = noon(today)

    private fun noon(date: LocalDate) = date.toStartOfDayMillis() + TimeUnit.HOURS.toMillis(12)

    @Suppress("LongParameterList")
    private fun plant(
        id: Long,
        interval: Int? = 7,
        name: String = "Plant $id",
        amplitude: Boolean = false,
        archivedAt: Long? = null,
        hiddenAt: Long? = null,
        dormancy: Pair<Int, Int>? = null,
        dormantCadence: Int? = null
    ) = Plant(
        id = id,
        name = name,
        wateringIntervalDays = interval,
        wateringBaseIntervalDays = interval?.toDouble(),
        pinIntervalToBase = !amplitude,
        archivedAt = archivedAt,
        hiddenAt = hiddenAt,
        dormancyStartMonth = dormancy?.first,
        dormancyEndMonth = dormancy?.second,
        dormantWateringIntervalDays = dormantCadence
    )

    private fun water(plantId: Long, date: LocalDate, type: CareType = CareType.WATER) =
        CareLog(plantId = plantId, careType = type, loggedAt = noon(date))

    @Suppress("LongParameterList")
    private fun build(
        plants: List<Plant>,
        logs: List<CareLog> = emptyList(),
        issues: List<PlantIssue> = emptyList(),
        away: List<AwayPeriod> = emptyList(),
        amplitude: Double = 0.0,
        at: Long = now
    ) = CollectionInsightsBuilder.build(plants, logs, issues, away, amplitude, at, Hemisphere.NORTHERN)

    private fun waterings(plantId: Long, vararg dates: LocalDate) = dates.map { water(plantId, it) }

    @Test
    fun `two late waterings in the window flag the plant`() {
        val logs = waterings(1, today.minusDays(25), today.minusDays(15), today.minusDays(5))
        val late = build(listOf(plant(1)), logs).lateWaterings

        assertEquals(listOf(LateWateringItem(plant(1), 2)), late)
    }

    @Test
    fun `one late watering is not enough`() {
        val logs = waterings(1, today.minusDays(25), today.minusDays(17), today.minusDays(10))
        assertTrue(build(listOf(plant(1)), logs).lateWaterings.isEmpty())
    }

    @Test
    fun `a watering exactly on the due day is not late and one day after is`() {
        val onTime = waterings(1, today.minusDays(21), today.minusDays(14), today.minusDays(7))
        assertTrue(build(listOf(plant(1)), onTime).lateWaterings.isEmpty())

        val oneDayLate = waterings(1, today.minusDays(26), today.minusDays(18), today.minusDays(10))
        assertEquals(2, build(listOf(plant(1)), oneDayLate).lateWaterings.single().lateCount)
    }

    @Test
    fun `a window boundary watering counts but the one before the window does not`() {
        val start = today.minusDays(29)
        val inside = waterings(1, start.minusDays(10), start, start.plusDays(10))
        assertEquals(2, build(listOf(plant(1)), inside).lateWaterings.single().lateCount)

        val outside = waterings(1, start.minusDays(20), start.minusDays(10), start.minusDays(1), start.plusDays(10))
        assertTrue(build(listOf(plant(1)), outside).lateWaterings.isEmpty())
    }

    @Test
    fun `a watering after today is ignored`() {
        val logs = waterings(1, today.minusDays(10), today.plusDays(3), today.plusDays(13))
        assertTrue(build(listOf(plant(1)), logs).lateWaterings.isEmpty())
    }

    @Test
    fun `the previous watering may lie before the window`() {
        val start = today.minusDays(29)
        val logs = waterings(1, start.minusDays(20), start.plusDays(2), start.plusDays(12))
        assertEquals(2, build(listOf(plant(1)), logs).lateWaterings.single().lateCount)
    }

    @Test
    fun `a plant without a watering interval is never late`() {
        val logs = waterings(2, today.minusDays(25), today.minusDays(15), today.minusDays(5))
        assertTrue(build(listOf(plant(2, interval = null)), logs).lateWaterings.isEmpty())
    }

    @Test
    fun `a gap touching a trip is not late`() {
        val logs = waterings(1, today.minusDays(25), today.minusDays(15), today.minusDays(5))
        val trip = AwayPeriod(firstDay = today.minusDays(20), lastDay = today.minusDays(17))

        val late = build(listOf(plant(1)), logs, away = listOf(trip)).lateWaterings

        assertTrue(late.isEmpty())
    }

    @Test
    fun `a trip only excludes the gaps it touches`() {
        val logs = waterings(1, today.minusDays(28), today.minusDays(20), today.minusDays(10), today.minusDays(1))
        val trip = AwayPeriod(firstDay = today.minusDays(27), lastDay = today.minusDays(25))

        val late = build(listOf(plant(1)), logs, away = listOf(trip)).lateWaterings

        assertEquals(2, late.single().lateCount)
    }

    @Test
    fun `lateness is judged by the interval of the season of the later watering`() {
        val base = plant(1, interval = 7, amplitude = true)
        val summerInterval = SeasonalWatering.effectiveInterval(7.0, today, 0.5, Hemisphere.NORTHERN)
        val winterDay = LocalDate.of(2027, 1, 20)
        val winterInterval = SeasonalWatering.effectiveInterval(7.0, winterDay, 0.5, Hemisphere.NORTHERN)
        assertTrue(winterInterval > summerInterval)

        val gap = summerInterval + 1
        val summerLogs = waterings(
            1,
            today.minusDays(3L * gap),
            today.minusDays(2L * gap),
            today.minusDays(gap.toLong())
        )
        assertEquals(2, build(listOf(base), summerLogs, amplitude = 0.5).lateWaterings.single().lateCount)

        val winterLogs = waterings(
            1,
            winterDay.minusDays(3L * gap),
            winterDay.minusDays(2L * gap),
            winterDay.minusDays(gap.toLong())
        )
        assertTrue(build(listOf(base), winterLogs, amplitude = 0.5, at = noon(winterDay)).lateWaterings.isEmpty())
    }

    @Test
    fun `a suspended dormant plant is never late`() {
        val logs = waterings(1, today.minusDays(25), today.minusDays(15), today.minusDays(5))
        val dormant = plant(1, dormancy = 6 to 8)

        assertTrue(build(listOf(dormant), logs).lateWaterings.isEmpty())
    }

    @Test
    fun `a dormant cadence plant is judged by its cadence`() {
        val dormant = plant(1, dormancy = 5 to 9, dormantCadence = 7)
        val onCadence = waterings(1, today.minusDays(21), today.minusDays(14), today.minusDays(7), today)
        assertTrue(build(listOf(dormant), onCadence).lateWaterings.isEmpty())

        val late = waterings(1, today.minusDays(28), today.minusDays(20), today.minusDays(12), today.minusDays(4))
        assertEquals(3, build(listOf(dormant), late).lateWaterings.single().lateCount)
    }

    @Test
    fun `the first watering of a dormant cycle is measured from the cycle start`() {
        val dormant = plant(1, dormancy = 7 to 8, dormantCadence = 7)
        val logs = waterings(1, LocalDate.of(2026, 6, 10), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 8), today)

        assertTrue(build(listOf(dormant), logs).lateWaterings.isEmpty())
    }

    @Test
    fun `hidden and archived plants are excluded everywhere`() {
        val hidden = plant(1, hiddenAt = 1L)
        val archived = plant(2, archivedAt = 1L)
        val logs = waterings(1, today.minusDays(25), today.minusDays(15), today.minusDays(5)) +
            waterings(2, today.minusDays(25), today.minusDays(15), today.minusDays(5))
        val issues = listOf(
            PlantIssue(id = 1, plantId = 1, name = "Mites", startedAt = noon(today.minusDays(3))),
            PlantIssue(id = 2, plantId = 2, name = "Rot", startedAt = noon(today.minusDays(3)))
        )

        val insights = build(listOf(hidden, archived), logs, issues)

        assertTrue(insights.lateWaterings.isEmpty())
        assertTrue(insights.careCounts.isEmpty())
        assertTrue(insights.activeIssues.isEmpty())
    }

    @Test
    fun `care counts cover the window per counted type and skip retired types`() {
        val start = today.minusDays(29)
        val logs = listOf(
            water(1, start),
            water(1, today),
            water(1, start.minusDays(1)),
            water(1, today.plusDays(1)),
            water(1, today.minusDays(3), CareType.FERTILIZE),
            water(2, today.minusDays(4), CareType.PRUNE),
            water(1, today.minusDays(5), CareType.NOTE),
            water(1, today.minusDays(5), CareType.MIST),
            water(1, today.minusDays(5), CareType.CHECK)
        )

        val counts = build(listOf(plant(1), plant(2)), logs).careCounts

        assertEquals(
            listOf(
                CareCountItem(CareType.WATER, 2),
                CareCountItem(CareType.FERTILIZE, 1),
                CareCountItem(CareType.PRUNE, 1)
            ),
            counts
        )
    }

    @Test
    fun `active issues report calendar days, longest first, and skip resolved ones`() {
        val issues = listOf(
            PlantIssue(id = 1, plantId = 1, name = "Mites", startedAt = noon(today.minusDays(10))),
            PlantIssue(id = 2, plantId = 2, name = "Rot", startedAt = today.toStartOfDayMillis() + 1),
            PlantIssue(id = 3, plantId = 1, name = "Old", startedAt = noon(today.minusDays(40)), resolvedAt = 5L),
            PlantIssue(id = 4, plantId = 2, name = "Gnats", startedAt = noon(today.minusDays(45)))
        )

        val active = build(listOf(plant(1), plant(2)), issues = issues).activeIssues

        assertEquals(listOf(4L, 1L, 2L), active.map { it.issue.id })
        assertEquals(listOf(45, 10, 0), active.map { it.daysActive })
    }

    @Test
    fun `nothing to report gives empty sections`() {
        val insights = build(listOf(plant(1)))

        assertTrue(insights.lateWaterings.isEmpty())
        assertTrue(insights.careCounts.isEmpty())
        assertTrue(insights.activeIssues.isEmpty())
        assertEquals(0, insights.totalCareCount)
    }

    @Test
    fun `late plants sort by count then name`() {
        val a = plant(1, name = "Zed")
        val b = plant(2, name = "Alpha")
        val c = plant(3, name = "Busy")
        val two = { id: Long -> waterings(id, today.minusDays(25), today.minusDays(15), today.minusDays(5)) }
        val three = waterings(3, today.minusDays(28), today.minusDays(20), today.minusDays(12), today.minusDays(4))

        val late = build(listOf(a, b, c), two(1) + two(2) + three).lateWaterings

        assertEquals(listOf(3L, 2L, 1L), late.map { it.plant.id })
    }
}
