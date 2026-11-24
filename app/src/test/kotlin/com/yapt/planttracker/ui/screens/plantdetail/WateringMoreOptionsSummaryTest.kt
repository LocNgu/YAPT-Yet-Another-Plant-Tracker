package com.yapt.planttracker.ui.screens.plantdetail

import com.yapt.planttracker.domain.schedule.WateringStrengthMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WateringMoreOptionsSummaryTest {
    private val defaults = WateringMoreOptionsValues(null, null, null, WateringStrengthMode.APP_DEFAULT)

    @Test
    fun `all defaults produce no changes and no summary`() {
        assertEquals(emptyList<WateringOptionChange>(), wateringOptionChanges(defaults))
        assertNull(joinWateringSummary(emptyList()))
    }

    @Test
    fun `dormancy window and custom strength are listed in order`() {
        val changes = wateringOptionChanges(
            WateringMoreOptionsValues(11, 2, null, WateringStrengthMode.CUSTOM)
        )
        assertEquals(
            listOf(
                WateringOptionChange.Dormancy(11, 2, null),
                WateringOptionChange.Strength(WateringStrengthMode.CUSTOM)
            ),
            changes
        )
    }

    @Test
    fun `dormant cadence is reported in whole weeks`() {
        val changes = wateringOptionChanges(defaults.copy(dormancyStartMonth = 12, dormancyEndMonth = 2, dormantWateringIntervalDays = 35))
        assertEquals(listOf(WateringOptionChange.Dormancy(12, 2, 5)), changes)
    }

    @Test
    fun `half a dormancy window or an out of range month is ignored`() {
        assertEquals(emptyList<WateringOptionChange>(), wateringOptionChanges(defaults.copy(dormancyStartMonth = 3)))
        assertEquals(
            emptyList<WateringOptionChange>(),
            wateringOptionChanges(defaults.copy(dormancyStartMonth = 0, dormancyEndMonth = 13))
        )
    }

    @Test
    fun `hidden strength control contributes nothing`() {
        assertEquals(emptyList<WateringOptionChange>(), wateringOptionChanges(defaults.copy(strengthMode = null)))
    }

    @Test
    fun `pin counts as non default`() {
        assertEquals(
            listOf(WateringOptionChange.Strength(WateringStrengthMode.PIN)),
            wateringOptionChanges(defaults.copy(strengthMode = WateringStrengthMode.PIN))
        )
    }

    @Test
    fun `summary joins parts with a middle dot`() {
        assertEquals("Dormant Nov–Feb · Custom strength", joinWateringSummary(listOf("Dormant Nov–Feb", "Custom strength")))
    }
}
