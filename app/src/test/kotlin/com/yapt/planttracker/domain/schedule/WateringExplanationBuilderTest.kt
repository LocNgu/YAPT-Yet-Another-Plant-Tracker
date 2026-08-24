package com.yapt.planttracker.domain.schedule

import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringAdjustment
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import com.yapt.planttracker.util.toLocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Pure-logic tests for the "Why this date?" sheet's builder (#572). */
class WateringExplanationBuilderTest {

    // Jan 5 12:00 UTC 2023 — the northern-hemisphere peak day.
    private val now = localDateUtcMillis(2023, 1, 5)

    private fun plantWith(
        wateringIntervalDays: Int? = 10,
        wateringBaseIntervalDays: Double? = null,
        pinIntervalToBase: Boolean = false,
        wateringConfidence: Int? = null
    ) = Plant(
        id = 1L,
        name = "Monstera",
        wateringIntervalDays = wateringIntervalDays,
        createdAt = now,
        wateringBaseIntervalDays = wateringBaseIntervalDays,
        pinIntervalToBase = pinIntervalToBase,
        wateringConfidence = wateringConfidence
    )

    @Test
    fun `no watering schedule returns null`() {
        val explanation = WateringExplanationBuilder.build(
            plant = plantWith(wateringIntervalDays = null),
            nextWateringDueAt = null,
            lastWateredAt = null,
            waterLogCount = 0,
            adaptiveWateringEnabled = true,
            seasonalAmplitude = 0.0,
            recentAdjustments = emptyList(),
            hemisphere = Hemisphere.NORTHERN,
            now = now
        )
        assertNull(explanation)
    }

    @Test
    fun `adaptive_watering off shows only the plain interval, no base or season or confidence or adjustments`() {
        val explanation = WateringExplanationBuilder.build(
            plant = plantWith(wateringIntervalDays = 10),
            nextWateringDueAt = now,
            lastWateredAt = now,
            waterLogCount = 5,
            adaptiveWateringEnabled = false,
            seasonalAmplitude = 0.0,
            recentAdjustments = listOf(
                WateringAdjustment(
                    plantId = 1L,
                    trigger = WateringAdjustmentTrigger.WATER_JUST_RIGHT,
                    beforeIntervalDays = 10,
                    afterIntervalDays = 10
                )
            ),
            hemisphere = Hemisphere.NORTHERN,
            now = now
        )
        assertNotNull(explanation)
        assertEquals(10, explanation!!.effectiveIntervalDays)
        assertEquals(false, explanation.adaptiveWateringEnabled)
        assertNull(explanation.baseIntervalDays)
        assertNull(explanation.season)
        assertNull(explanation.confidenceLevel)
        assertTrue(explanation.recentAdjustments.isEmpty())
    }

    @Test
    fun `adaptive_watering on with seasonal amplitude shows the season row`() {
        val explanation = WateringExplanationBuilder.build(
            plant = plantWith(wateringIntervalDays = 10, wateringBaseIntervalDays = 8.0),
            nextWateringDueAt = now,
            lastWateredAt = now,
            waterLogCount = 3,
            adaptiveWateringEnabled = true,
            seasonalAmplitude = 0.35,
            recentAdjustments = emptyList(),
            hemisphere = Hemisphere.NORTHERN,
            now = now
        )
        assertNotNull(explanation)
        assertEquals(8, explanation!!.baseIntervalDays)
        assertNotNull(explanation.season)
        // Peak day: season(now) = 1 + amplitude exactly.
        assertEquals(1.35, explanation.season!!.multiplier, 1e-9)
        assertEquals(SeasonBand.SLOWER_GROWTH, explanation.season.band)
    }

    @Test
    fun `pinned plant hides the season row but keeps base, confidence and adjustments`() {
        val explanation = WateringExplanationBuilder.build(
            plant = plantWith(
                wateringIntervalDays = 10,
                wateringBaseIntervalDays = 8.0,
                pinIntervalToBase = true,
                wateringConfidence = 3
            ),
            nextWateringDueAt = now,
            lastWateredAt = now,
            waterLogCount = 4,
            adaptiveWateringEnabled = true,
            seasonalAmplitude = 0.35,
            recentAdjustments = listOf(
                WateringAdjustment(
                    plantId = 1L,
                    trigger = WateringAdjustmentTrigger.MANUAL_EDIT,
                    beforeIntervalDays = 9,
                    afterIntervalDays = 10
                )
            ),
            hemisphere = Hemisphere.NORTHERN,
            now = now
        )
        assertNotNull(explanation)
        assertNull(explanation!!.season)
        assertNotNull(explanation.baseIntervalDays)
        assertNotNull(explanation.confidenceLevel)
        assertEquals(1, explanation.recentAdjustments.size)
        // Pinned: due-date math reads wateringIntervalDays literally, no arithmetic to display.
        assertEquals(10, explanation.effectiveIntervalDays)
    }

    @Test
    fun `confidence buckets`() {
        fun levelFor(score: Int?) = WateringExplanationBuilder.build(
            plant = plantWith(wateringIntervalDays = 10, wateringConfidence = score),
            nextWateringDueAt = now,
            lastWateredAt = now,
            waterLogCount = 1,
            adaptiveWateringEnabled = true,
            seasonalAmplitude = 0.0,
            recentAdjustments = emptyList(),
            now = now
        )!!.confidenceLevel

        assertEquals(WateringConfidenceLevel.STILL_LEARNING, levelFor(null))
        assertEquals(WateringConfidenceLevel.STILL_LEARNING, levelFor(1))
        assertEquals(WateringConfidenceLevel.GETTING_THERE, levelFor(2))
        assertEquals(WateringConfidenceLevel.GETTING_THERE, levelFor(3))
        assertEquals(WateringConfidenceLevel.DIALED_IN, levelFor(4))
        assertEquals(WateringConfidenceLevel.DIALED_IN, levelFor(5))
    }

    @Test
    fun `effectiveIntervalDays matches CareSchedule effectiveWateringIntervalDaysForDisplay`() {
        val plant = plantWith(wateringIntervalDays = 10, wateringBaseIntervalDays = 6.0)
        val explanation = WateringExplanationBuilder.build(
            plant = plant,
            nextWateringDueAt = now,
            lastWateredAt = now,
            waterLogCount = 1,
            adaptiveWateringEnabled = true,
            seasonalAmplitude = 0.35,
            recentAdjustments = emptyList(),
            hemisphere = Hemisphere.NORTHERN,
            now = now
        )!!
        val expected = CareSchedule.effectiveWateringIntervalDaysForDisplay(
            plant,
            now.toLocalDate(),
            0.35,
            Hemisphere.NORTHERN
        )
        assertEquals(expected, explanation.effectiveIntervalDays)
    }
}

@Suppress("FunctionNaming")
private fun localDateUtcMillis(year: Int, month: Int, day: Int): Long {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.clear()
    cal.set(year, month - 1, day, 12, 0, 0)
    return cal.timeInMillis
}
