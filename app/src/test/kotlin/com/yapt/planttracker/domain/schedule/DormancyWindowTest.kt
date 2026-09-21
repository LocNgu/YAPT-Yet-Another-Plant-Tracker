package com.yapt.planttracker.domain.schedule

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DormancyWindowTest {

    // ---- Non-wrapping window (start <= end) ----

    @Test
    fun `non-wrapping window is dormant strictly inside the range`() {
        assertTrue(DormancyWindow.isDormant(month = 7, startMonth = 6, endMonth = 8))
    }

    @Test
    fun `non-wrapping window is dormant at the start boundary`() {
        assertTrue(DormancyWindow.isDormant(month = 6, startMonth = 6, endMonth = 8))
    }

    @Test
    fun `non-wrapping window is dormant at the end boundary`() {
        assertTrue(DormancyWindow.isDormant(month = 8, startMonth = 6, endMonth = 8))
    }

    @Test
    fun `non-wrapping window is not dormant just before the start`() {
        assertFalse(DormancyWindow.isDormant(month = 5, startMonth = 6, endMonth = 8))
    }

    @Test
    fun `non-wrapping window is not dormant just after the end`() {
        assertFalse(DormancyWindow.isDormant(month = 9, startMonth = 6, endMonth = 8))
    }

    // ---- Wrapping window (start > end) ----

    @Test
    fun `wrapping window is dormant after the start, before year-end`() {
        assertTrue(DormancyWindow.isDormant(month = 12, startMonth = 11, endMonth = 2))
    }

    @Test
    fun `wrapping window is dormant after year-start, before the end`() {
        assertTrue(DormancyWindow.isDormant(month = 1, startMonth = 11, endMonth = 2))
    }

    @Test
    fun `wrapping window is dormant at the start boundary`() {
        assertTrue(DormancyWindow.isDormant(month = 11, startMonth = 11, endMonth = 2))
    }

    @Test
    fun `wrapping window is dormant at the end boundary`() {
        assertTrue(DormancyWindow.isDormant(month = 2, startMonth = 11, endMonth = 2))
    }

    @Test
    fun `wrapping window is not dormant in the middle of the gap`() {
        assertFalse(DormancyWindow.isDormant(month = 6, startMonth = 11, endMonth = 2))
    }

    @Test
    fun `wrapping window is not dormant just before the start`() {
        assertFalse(DormancyWindow.isDormant(month = 10, startMonth = 11, endMonth = 2))
    }

    @Test
    fun `wrapping window is not dormant just after the end`() {
        assertFalse(DormancyWindow.isDormant(month = 3, startMonth = 11, endMonth = 2))
    }

    // ---- Single-month window (start == end) ----

    @Test
    fun `single-month window is dormant only in that month`() {
        assertTrue(DormancyWindow.isDormant(month = 7, startMonth = 7, endMonth = 7))
        assertFalse(DormancyWindow.isDormant(month = 6, startMonth = 7, endMonth = 7))
        assertFalse(DormancyWindow.isDormant(month = 8, startMonth = 7, endMonth = 7))
    }

    // ---- Unconfigured / half-configured ----

    @Test
    fun `both months null is never dormant`() {
        assertFalse(DormancyWindow.isDormant(month = 7, startMonth = null, endMonth = null))
    }

    @Test
    fun `only startMonth set is never dormant`() {
        assertFalse(DormancyWindow.isDormant(month = 7, startMonth = 6, endMonth = null))
    }

    @Test
    fun `only endMonth set is never dormant`() {
        assertFalse(DormancyWindow.isDormant(month = 7, startMonth = null, endMonth = 8))
    }

    // ---- Out-of-range (defensive, fail-closed) ----

    @Test
    fun `out-of-range startMonth is never dormant`() {
        assertFalse(DormancyWindow.isDormant(month = 7, startMonth = 0, endMonth = 8))
        assertFalse(DormancyWindow.isDormant(month = 7, startMonth = 13, endMonth = 8))
    }

    @Test
    fun `out-of-range endMonth is never dormant`() {
        assertFalse(DormancyWindow.isDormant(month = 7, startMonth = 6, endMonth = 0))
        assertFalse(DormancyWindow.isDormant(month = 7, startMonth = 6, endMonth = 13))
    }

    // ---- Boundary months at each end of the calendar (1 and 12) ----

    @Test
    fun `window spanning January is dormant at month 1`() {
        assertTrue(DormancyWindow.isDormant(month = 1, startMonth = 12, endMonth = 1))
    }

    @Test
    fun `window spanning December is dormant at month 12`() {
        assertTrue(DormancyWindow.isDormant(month = 12, startMonth = 12, endMonth = 1))
    }

    @Test
    fun `full-year non-wrapping window is dormant every month`() {
        for (month in 1..12) {
            assertTrue(DormancyWindow.isDormant(month = month, startMonth = 1, endMonth = 12))
        }
    }
}
