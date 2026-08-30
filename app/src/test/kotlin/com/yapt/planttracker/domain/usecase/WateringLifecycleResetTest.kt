package com.yapt.planttracker.domain.usecase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [WateringLifecycleReset]'s decision functions (#571). See technical ADR-0023.
 */
class WateringLifecycleResetTest {

    // --- roomChangeTriggersReset() ---

    @Test
    fun `no room set at all does not trigger a reset`() {
        assertFalse(WateringLifecycleReset.roomChangeTriggersReset(null, null))
    }

    @Test
    fun `unchanged room does not trigger a reset`() {
        assertFalse(WateringLifecycleReset.roomChangeTriggersReset("Living room", "Living room"))
    }

    @Test
    fun `blank to filled for the first time is data entry, not a reset`() {
        assertFalse(WateringLifecycleReset.roomChangeTriggersReset(null, "Living room"))
        assertFalse(WateringLifecycleReset.roomChangeTriggersReset("", "Living room"))
        assertFalse(WateringLifecycleReset.roomChangeTriggersReset("   ", "Living room"))
    }

    @Test
    fun `filled to a different filled room is a real move and resets`() {
        assertTrue(WateringLifecycleReset.roomChangeTriggersReset("Living room", "Bedroom"))
    }

    @Test
    fun `filled to blank is treated as a move and resets`() {
        assertTrue(WateringLifecycleReset.roomChangeTriggersReset("Living room", null))
    }

    // --- isFrozen() ---

    @Test
    fun `no freeze marker is never frozen`() {
        assertFalse(WateringLifecycleReset.isFrozen(null, now = 1_000L))
    }

    @Test
    fun `before the freeze marker elapses is frozen`() {
        assertTrue(WateringLifecycleReset.isFrozen(freezeUntil = 2_000L, now = 1_000L))
    }

    @Test
    fun `at or after the freeze marker is not frozen`() {
        assertFalse(WateringLifecycleReset.isFrozen(freezeUntil = 2_000L, now = 2_000L))
        assertFalse(WateringLifecycleReset.isFrozen(freezeUntil = 2_000L, now = 3_000L))
    }
}
