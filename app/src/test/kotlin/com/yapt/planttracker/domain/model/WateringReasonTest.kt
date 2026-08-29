package com.yapt.planttracker.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reason → stored-feedback mapping (#586, product ADR-0030). Small, but it is the point at which
 * "TOO_SOON on a WATER log is illogical" stops being a convention and becomes unrepresentable.
 */
class WateringReasonTest {

    @Test
    fun `the plant needed it is stored as TOO_LATE`() {
        assertEquals(WateringFeedback.TOO_LATE, WateringReason.PLANT_NEEDED_IT.toWateringFeedback())
    }

    @Test
    fun `just my timing stores no feedback at all`() {
        assertNull(WateringReason.JUST_MY_TIMING.toWateringFeedback())
    }

    /**
     * Acceptance criterion: TOO_SOON is never written to a WATER log. Enumerating the whole enum
     * rather than the two cases above means a future third reason cannot quietly reintroduce it.
     */
    @Test
    fun `no watering reason maps to TOO_SOON`() {
        for (reason in WateringReason.entries) {
            assertNotEquals(WateringFeedback.TOO_SOON, reason.toWateringFeedback())
        }
    }

    /**
     * The design constraint from the issue: the model needs exactly one bit, and reason lists bloat.
     * At most three options per prompt, phrased the way a user would say them.
     */
    @Test
    fun `each prompt offers at most three reasons`() {
        assertEquals(2, WateringReason.entries.size)
        assertEquals(2, RescheduleReason.entries.size)
    }
}
