package com.yapt.planttracker.domain.schedule

import com.yapt.planttracker.domain.model.Plant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LiveWateringIntervalTest {

    private val january = LocalDate.of(2026, 1, 5)
    private val july = LocalDate.of(2026, 7, 5)
    private val standard = SeasonalAmplitude.STANDARD.value

    private fun plant(
        literal: Int? = 7,
        base: Double? = 8.0,
        pinned: Boolean = false
    ) = Plant(
        id = 1L,
        name = "Monstera",
        wateringIntervalDays = literal,
        wateringBaseIntervalDays = base,
        pinIntervalToBase = pinned,
        createdAt = 0L,
        updatedAt = 0L
    )

    @Test
    fun `no watering interval has no live value`() {
        assertNull(LiveWateringInterval.compute(plant(literal = null), january, standard, Hemisphere.NORTHERN))
    }

    @Test
    fun `live value comes from the base and never the stored literal`() {
        val live = LiveWateringInterval.compute(
            plant(literal = 7, base = 8.0),
            january,
            standard,
            Hemisphere.NORTHERN
        )!!

        assertEquals(
            SeasonalWatering.effectiveInterval(8.0, january, standard, Hemisphere.NORTHERN),
            live.effectiveDays
        )
        assertNotEquals(7, live.effectiveDays)
    }

    @Test
    fun `an adjusted day names the season and the rounded base`() {
        val live = LiveWateringInterval.compute(plant(base = 7.6), january, standard, Hemisphere.NORTHERN)!!

        assertEquals(LiveWateringInterval.SeasonalAdjustment(FertilizingSeason.WINTER, 8), live.adjustment)
    }

    @Test
    fun `the season flips in the southern hemisphere`() {
        val live = LiveWateringInterval.compute(plant(base = 8.0), january, standard, Hemisphere.SOUTHERN)!!

        assertEquals(FertilizingSeason.SUMMER, live.adjustment?.season)
    }

    @Test
    fun `summer shortens the live interval below the base`() {
        val live = LiveWateringInterval.compute(plant(base = 20.0), july, standard, Hemisphere.NORTHERN)!!

        assertTrue(live.effectiveDays < 20)
        assertEquals(20, live.adjustment?.baseDays)
    }

    @Test
    fun `no adjustment when the live value equals the rounded base`() {
        val tinyAmplitude = 0.01
        val live = LiveWateringInterval.compute(plant(base = 8.0), january, tinyAmplitude, Hemisphere.NORTHERN)!!

        assertEquals(8, live.effectiveDays)
        assertNull(live.adjustment)
    }

    @Test
    fun `amplitude Off returns the literal with no adjustment`() {
        val live = LiveWateringInterval.compute(plant(literal = 7, base = 12.0), january, 0.0, Hemisphere.NORTHERN)!!

        assertEquals(7, live.effectiveDays)
        assertNull(live.adjustment)
    }

    @Test
    fun `a pinned plant returns the literal with no adjustment`() {
        val live = LiveWateringInterval.compute(
            plant(literal = 7, base = 12.0, pinned = true),
            january,
            standard,
            Hemisphere.NORTHERN
        )!!

        assertEquals(7, live.effectiveDays)
        assertNull(live.adjustment)
    }

    @Test
    fun `a never-recorded base falls back to the literal as the base`() {
        val live = LiveWateringInterval.compute(
            plant(literal = 7, base = null),
            january,
            standard,
            Hemisphere.NORTHERN
        )!!

        assertEquals(7, live.adjustment?.baseDays)
    }

    @Test
    fun `a live value over 60 is reported as is`() {
        val live = LiveWateringInterval.compute(
            plant(literal = 60, base = 60.0),
            january,
            standard,
            Hemisphere.NORTHERN
        )!!

        assertTrue(live.effectiveDays > 60)
    }
}
