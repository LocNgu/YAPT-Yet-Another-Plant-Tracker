package com.yapt.planttracker.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reason → stored-feedback mapping (#586, product ADR-0030; late-direction mapping amended by
 * #649, product ADR-0033). [WateringReason.SOIL_STILL_MOIST] is the point at which "TOO_SOON on a
 * WATER log is illogical" stopped being a hard invariant — it's now reachable, but only from the
 * late-direction reason prompt, never the early one.
 */
class WateringReasonTest {

    @Test
    fun `the plant needed it is stored as TOO_LATE`() {
        assertEquals(WateringFeedback.TOO_LATE, WateringReason.PLANT_NEEDED_IT.toWateringFeedback())
    }

    @Test
    fun `soil was still moist is stored as TOO_SOON`() {
        assertEquals(WateringFeedback.TOO_SOON, WateringReason.SOIL_STILL_MOIST.toWateringFeedback())
    }

    @Test
    fun `just my timing stores no feedback at all`() {
        assertNull(WateringReason.JUST_MY_TIMING.toWateringFeedback())
    }

    /**
     * Only [WateringReason.SOIL_STILL_MOIST] maps to TOO_SOON — [WateringReason.PLANT_NEEDED_IT] and
     * [WateringReason.JUST_MY_TIMING] must not, so a future edit can't quietly widen this.
     */
    @Test
    fun `only soil still moist maps to TOO_SOON`() {
        for (reason in WateringReason.entries) {
            if (reason != WateringReason.SOIL_STILL_MOIST) {
                assertNotEquals(WateringFeedback.TOO_SOON, reason.toWateringFeedback())
            }
        }
    }

    /**
     * The design constraint from the issue: the model needs exactly one bit per direction, and
     * reason lists bloat. [WateringReason] now holds three values total — [WateringReason
     * .PLANT_NEEDED_IT] (early-only), [WateringReason.SOIL_STILL_MOIST] (late-only), and
     * [WateringReason.JUST_MY_TIMING] (both) — but each individual prompt still offers exactly two,
     * per [com.yapt.planttracker.ui.components.WateringReasonBottomSheet]'s direction-specific
     * option list.
     */
    @Test
    fun `watering reason has exactly three values, reschedule reason at most three`() {
        assertEquals(3, WateringReason.entries.size)
        assertEquals(2, RescheduleReason.entries.size)
    }
}
