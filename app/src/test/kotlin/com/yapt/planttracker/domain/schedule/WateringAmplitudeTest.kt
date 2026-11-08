package com.yapt.planttracker.domain.schedule

import com.yapt.planttracker.domain.model.Plant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.util.TimeZone

/** The per-plant strength resolver and its consumers (#700, product ADR-0084). */
class WateringAmplitudeTest {

    private val globals = SeasonalAmplitude.entries.map { it.value }
    private val january = LocalDate.of(2026, 1, 5)
    private val nowMillis = LocalDate.of(2026, 1, 5).atTime(12, 0).atZone(java.time.ZoneId.systemDefault())
        .toInstant().toEpochMilli()

    private fun plant(
        pinned: Boolean = false,
        override: SeasonalAmplitude? = null,
        base: Double? = 10.0,
        literal: Int = 10
    ) = Plant(
        id = 1L,
        name = "Fern",
        wateringIntervalDays = literal,
        wateringBaseIntervalDays = base,
        pinIntervalToBase = pinned,
        wateringAmplitudeOverride = override,
        createdAt = 0L,
        updatedAt = 0L
    )

    @Test
    fun `pin resolves to no seasonality for every override and global value`() {
        for (global in globals) {
            for (override in listOf(null) + SeasonalAmplitude.entries) {
                assertEquals(0.0, resolveWateringAmplitude(true, override, global), 0.0)
                assertEquals(0.0, plant(pinned = true, override = override).effectiveWateringAmplitude(global), 0.0)
            }
        }
    }

    @Test
    fun `a custom strength wins over every global value`() {
        for (global in globals) {
            for (override in SeasonalAmplitude.entries) {
                assertEquals(override.value, resolveWateringAmplitude(false, override, global), 0.0)
                assertEquals(override.value, plant(override = override).effectiveWateringAmplitude(global), 0.0)
            }
        }
    }

    @Test
    fun `app default follows the global value`() {
        for (global in globals) {
            assertEquals(global, resolveWateringAmplitude(false, null, global), 0.0)
            assertEquals(global, plant().effectiveWateringAmplitude(global), 0.0)
        }
    }

    @Test
    fun `global Off with a custom Strong plant still swings`() {
        assertEquals(0.5, plant(override = SeasonalAmplitude.STRONG).effectiveWateringAmplitude(0.0), 0.0)
    }

    @Test
    fun `mode shows pin, custom or app default and never offers Off under custom`() {
        assertEquals(WateringStrengthMode.PIN, wateringStrengthMode(true, null))
        assertEquals(WateringStrengthMode.PIN, wateringStrengthMode(true, SeasonalAmplitude.MILD))
        assertEquals(WateringStrengthMode.CUSTOM, wateringStrengthMode(false, SeasonalAmplitude.STRONG))
        assertEquals(WateringStrengthMode.APP_DEFAULT, wateringStrengthMode(false, null))
        assertEquals(WateringStrengthMode.PIN, wateringStrengthMode(false, SeasonalAmplitude.OFF))
        assertFalse(SeasonalAmplitude.OFF in CUSTOM_WATERING_STRENGTHS)
        assertEquals(
            listOf(SeasonalAmplitude.MILD, SeasonalAmplitude.STANDARD, SeasonalAmplitude.STRONG),
            CUSTOM_WATERING_STRENGTHS
        )
    }

    @Test
    fun `changing the strength keeps the base and moves the effective interval`() {
        val standard = plant(override = SeasonalAmplitude.STANDARD)
        val strong = standard.copy(wateringAmplitudeOverride = SeasonalAmplitude.STRONG)
        val global = SeasonalAmplitude.MILD.value

        fun days(p: Plant) =
            CareSchedule.effectiveWateringIntervalDaysForDisplay(p, january, global, Hemisphere.NORTHERN)
        val before = days(standard)
        val after = days(strong)

        assertEquals(standard.wateringBaseIntervalDays, strong.wateringBaseIntervalDays)
        assertEquals(14, before) // round(10 * 1.35) on the peak day
        assertEquals(15, after) // round(10 * 1.5)
    }

    @Test
    fun `effective interval uses the plant strength, not the global one`() {
        val global = SeasonalAmplitude.STANDARD.value
        fun days(p: Plant) =
            CareSchedule.effectiveWateringIntervalDaysForDisplay(p, january, global, Hemisphere.NORTHERN)

        assertEquals(14, days(plant()))
        assertEquals(12, days(plant(override = SeasonalAmplitude.MILD)))
        assertEquals(10, days(plant(pinned = true, literal = 10)))
        assertEquals(10, days(plant(override = SeasonalAmplitude.OFF)))
    }

    @Test
    fun `due date follows the plant strength`() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val lastWatered = nowMillis - 3L * 86_400_000L
        fun due(p: Plant) = CareSchedule.computeStatus(
            plant = p,
            lastWateredAt = lastWatered,
            lastFertilizedAt = null,
            totalLogs = 1,
            now = nowMillis,
            seasonalAmplitude = 0.0,
            hemisphere = Hemisphere.NORTHERN
        ).nextWateringDueAt

        assertEquals(lastWatered + 15L * 86_400_000L, due(plant(override = SeasonalAmplitude.STRONG)))
        assertEquals(lastWatered + 10L * 86_400_000L, due(plant()))
    }

    @Test
    fun `live interval shows a seasonal adjustment only for the effective strength`() {
        val global = SeasonalAmplitude.OFF.value

        assertNull(LiveWateringInterval.compute(plant(), january, global, Hemisphere.NORTHERN)!!.adjustment)
        assertNotNull(
            LiveWateringInterval.compute(
                plant(override = SeasonalAmplitude.STRONG),
                january,
                global,
                Hemisphere.NORTHERN
            )!!.adjustment
        )
        assertNull(
            LiveWateringInterval.compute(
                plant(pinned = true),
                january,
                SeasonalAmplitude.STRONG.value,
                Hemisphere.NORTHERN
            )!!.adjustment
        )
    }

    @Test
    fun `the why-this-date season row uses the effective strength and hides when it is zero`() {
        fun season(p: Plant, global: Double) = WateringExplanationBuilder.build(
            plant = p,
            nextWateringDueAt = nowMillis,
            lastWateredAt = nowMillis,
            waterLogCount = 3,
            seasonalAmplitude = global,
            recentAdjustments = emptyList(),
            hemisphere = Hemisphere.NORTHERN,
            now = nowMillis
        )!!.season

        assertNull(season(plant(), SeasonalAmplitude.OFF.value))
        val strong = season(plant(override = SeasonalAmplitude.STRONG), SeasonalAmplitude.OFF.value)
        assertEquals(1.5, strong!!.multiplier, 1e-9)
        assertNull(season(plant(pinned = true), SeasonalAmplitude.STRONG.value))
        val mild = season(plant(override = SeasonalAmplitude.MILD), SeasonalAmplitude.STRONG.value)
        assertEquals(1.2, mild!!.multiplier, 1e-9)
    }
}
