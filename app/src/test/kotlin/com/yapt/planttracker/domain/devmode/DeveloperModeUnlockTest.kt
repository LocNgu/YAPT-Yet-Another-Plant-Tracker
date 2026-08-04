package com.yapt.planttracker.domain.devmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DeveloperModeUnlockTest {

    @Test
    fun `first two taps are silent`() {
        var count = 0
        repeat(2) {
            val result = DeveloperModeUnlock.registerTap(count, isDeveloperModeEnabled = false)
            assertEquals(DeveloperModeTapOutcome.Silent, result.outcome)
            count = result.newTapCount
        }
        assertEquals(2, count)
    }

    @Test
    fun `third tap shows two taps remaining`() {
        val result = DeveloperModeUnlock.registerTap(2, isDeveloperModeEnabled = false)
        assertEquals(DeveloperModeTapOutcome.Countdown(2), result.outcome)
        assertEquals(3, result.newTapCount)
    }

    @Test
    fun `fourth tap shows one tap remaining`() {
        val result = DeveloperModeUnlock.registerTap(3, isDeveloperModeEnabled = false)
        assertEquals(DeveloperModeTapOutcome.Countdown(1), result.outcome)
        assertEquals(4, result.newTapCount)
    }

    @Test
    fun `four taps do not unlock developer mode`() {
        var count = 0
        var lastOutcome: DeveloperModeTapOutcome = DeveloperModeTapOutcome.Silent
        repeat(4) {
            val result = DeveloperModeUnlock.registerTap(count, isDeveloperModeEnabled = false)
            count = result.newTapCount
            lastOutcome = result.outcome
        }
        assertEquals(4, count)
        assertFalse(lastOutcome is DeveloperModeTapOutcome.Unlocked)
    }

    @Test
    fun `fifth tap unlocks developer mode and resets the counter`() {
        val result = DeveloperModeUnlock.registerTap(4, isDeveloperModeEnabled = false)
        assertEquals(DeveloperModeTapOutcome.Unlocked, result.outcome)
        assertEquals(0, result.newTapCount)
    }

    @Test
    fun `taps are inert once developer mode is already enabled`() {
        val result = DeveloperModeUnlock.registerTap(0, isDeveloperModeEnabled = true)
        assertEquals(DeveloperModeTapOutcome.Inert, result.outcome)
        assertEquals(0, result.newTapCount)
    }

    @Test
    fun `counter does not advance while inert`() {
        val result = DeveloperModeUnlock.registerTap(2, isDeveloperModeEnabled = true)
        assertEquals(DeveloperModeTapOutcome.Inert, result.outcome)
        assertEquals(2, result.newTapCount)
    }
}
