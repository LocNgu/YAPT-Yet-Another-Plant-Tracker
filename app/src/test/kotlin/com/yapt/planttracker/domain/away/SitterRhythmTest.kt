package com.yapt.planttracker.domain.away

import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.TimeZone

/** The sitter-rhythm step (#899, product ADR-0065): flagged counts, the recommendation and who is excluded. */
class SitterRhythmTest {

    private val originalTimeZone: TimeZone = TimeZone.getDefault()
    private val firstDay = LocalDate.of(2026, 10, 8)

    @Before
    fun pinTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Suppress("LongParameterList")
    private fun plant(
        id: Long,
        interval: Int?,
        name: String = "Plant $id",
        pinned: Boolean = true,
        base: Double? = null,
        dormancyStart: Int? = null,
        dormancyEnd: Int? = null,
        cadence: Int? = null,
        archived: Boolean = false
    ) = Plant(
        id = id,
        name = name,
        wateringIntervalDays = interval,
        wateringBaseIntervalDays = base,
        pinIntervalToBase = pinned,
        dormancyStartMonth = dormancyStart,
        dormancyEndMonth = dormancyEnd,
        dormantWateringIntervalDays = cadence,
        archivedAt = if (archived) 1L else null,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun evaluate(plants: List<Plant>, amplitude: Double = 0.0) =
        SitterRhythm.evaluate(plants, firstDay, amplitude, Hemisphere.NORTHERN)

    private fun RhythmEvaluation.flagged(everyDays: Int) =
        options.first { it.everyDays == everyDays }.flagged.map { it.plant.id }

    @Test
    fun `the offered rhythms are 4 5 7 10 and 14 days`() {
        assertEquals(listOf(4, 5, 7, 10, 14), evaluate(emptyList()).options.map { it.everyDays })
    }

    @Test
    fun `a rhythm flags a plant when it is at least one and a half times its interval`() {
        val result = evaluate(listOf(plant(1, interval = 3), plant(2, interval = 7), plant(3, interval = 10)))

        // 3-day plant: flagged from 5 days (5 >= 4.5); 7-day plant: from 14 (14 >= 10.5); 10-day: never (15).
        assertEquals(emptyList<Long>(), result.flagged(4))
        assertEquals(listOf(1L), result.flagged(5))
        assertEquals(listOf(1L), result.flagged(7))
        assertEquals(listOf(1L), result.flagged(10))
        assertEquals(listOf(1L, 2L), result.flagged(14))
    }

    @Test
    fun `the boundary is inclusive`() {
        assertTrue(SitterRhythm.isLongerThanUsual(gapDays = 6, intervalDays = 4))
        assertFalse(SitterRhythm.isLongerThanUsual(gapDays = 5, intervalDays = 4))
        assertTrue(SitterRhythm.isLongerThanUsual(gapDays = 3, intervalDays = 2))
        assertFalse(SitterRhythm.isLongerThanUsual(gapDays = 14, intervalDays = 10))
    }

    @Test
    fun `flagged plants carry their usual interval and sort by name`() {
        val result = evaluate(listOf(plant(1, 2, name = "Fern"), plant(2, 3, name = "Basil")))

        val flagged = result.options.first { it.everyDays == 7 }.flagged
        assertEquals(listOf("Basil" to 3, "Fern" to 2), flagged.map { it.plant.name to it.usualIntervalDays })
    }

    @Test
    fun `the recommendation is the longest rhythm with nothing flagged`() {
        val result = evaluate(listOf(plant(1, interval = 7), plant(2, interval = 10)))

        assertEquals(10, result.recommendedEveryDays)
        assertTrue(result.options.first { it.everyDays == 10 }.flagged.isEmpty())
        assertEquals(listOf(1L), result.flagged(14))
    }

    @Test
    fun `with no plants every option is clear so the longest is recommended`() {
        assertEquals(14, evaluate(emptyList()).recommendedEveryDays)
    }

    @Test
    fun `when every option flags something the fewest wins`() {
        val result = evaluate(listOf(plant(1, interval = 2), plant(2, interval = 3)))

        // 4 flags only the 2-day plant (4 >= 3, 4 < 4.5); every longer rhythm flags both.
        assertEquals(listOf(1), result.options.first { it.everyDays == 4 }.flagged.map { it.plant.id.toInt() })
        assertEquals(4, result.recommendedEveryDays)
    }

    @Test
    fun `a tie on the fewest goes to the longest rhythm`() {
        val result = evaluate(listOf(plant(1, interval = 2), plant(2, interval = 2), plant(3, interval = 20)))

        assertTrue(result.options.all { it.flaggedCount == 2 })
        assertEquals(14, result.recommendedEveryDays)
    }

    @Test
    fun `resting plants unscheduled plants and archived plants are left out`() {
        val result = evaluate(
            listOf(
                plant(1, interval = 2),
                plant(2, interval = 2, dormancyStart = 10, dormancyEnd = 11, cadence = 28),
                plant(3, interval = 2, dormancyStart = 10, dormancyEnd = 11),
                plant(4, interval = null),
                plant(5, interval = 2, archived = true)
            )
        )

        assertEquals(listOf(1L), result.flagged(14))
    }

    @Test
    fun `dormancy is judged on the trips first day`() {
        val plants = listOf(plant(1, interval = 2, dormancyStart = 11, dormancyEnd = 12))
        val october = SitterRhythm.evaluate(plants, LocalDate.of(2026, 10, 8), 0.0, Hemisphere.NORTHERN)
        val november = SitterRhythm.evaluate(plants, LocalDate.of(2026, 11, 8), 0.0, Hemisphere.NORTHERN)

        assertEquals(listOf(1L), october.flagged(5))
        assertTrue(november.flagged(5).isEmpty())
    }

    @Test
    fun `the seasonal interval on the first day is used, not the stored literal`() {
        val plants = listOf(plant(1, interval = 7, pinned = false, base = 14.0))
        val effective = SeasonalWatering.effectiveInterval(14.0, firstDay, 0.5, Hemisphere.NORTHERN)
        assertTrue("the case must tell the base from the literal", effective != 7)

        val result = evaluate(plants, amplitude = 0.5)

        val expected = SitterRhythm.OPTIONS.associateWith {
            if (SitterRhythm.isLongerThanUsual(it, effective)) 1 else 0
        }
        assertEquals(expected, result.options.associate { it.everyDays to it.flaggedCount })
    }

    @Test
    fun `a pinned plant keeps its literal interval whatever the season`() {
        val result = evaluate(listOf(plant(1, interval = 7, pinned = true, base = 14.0)), amplitude = 0.5)

        assertEquals(listOf(1L), result.flagged(14))
    }

    @Test
    fun `the ask text date is the last day at home plus the rhythm`() {
        assertEquals(LocalDate.of(2026, 10, 7), SitterRhythm.lastDayAtHome(firstDay))
        assertEquals(LocalDate.of(2026, 10, 14), SitterRhythm.firstVisitBy(firstDay, 7))
    }
}
