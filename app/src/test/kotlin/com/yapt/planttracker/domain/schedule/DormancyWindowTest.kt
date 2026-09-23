package com.yapt.planttracker.domain.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DormancyWindowTest {

    private val zone = ZoneId.systemDefault()

    private fun millisAt(year: Int, month: Int, day: Int) =
        LocalDate.of(year, month, day).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `dormant watering cadence accepts whole weeks from one through twelve`() {
        assertEquals(7, DormancyWindow.validWateringInterval(7))
        assertEquals(84, DormancyWindow.validWateringInterval(84))
        assertEquals(8, DormancyWindow.wateringIntervalWeeks(56))
        assertEquals(56, DormancyWindow.wateringIntervalDays(8))
        assertNull(DormancyWindow.validWateringInterval(6))
        assertNull(DormancyWindow.validWateringInterval(85))
        assertNull(DormancyWindow.validWateringInterval(91))
    }

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

    // ---- spansDormancy: no window configured ----

    @Test
    fun `spansDormancy is false when both months are null`() {
        assertFalse(
            DormancyWindow.spansDormancy(
                startMonth = null,
                endMonth = null,
                fromMillis = millisAt(2026, 10, 25),
                toMillis = millisAt(2027, 3, 1)
            )
        )
    }

    @Test
    fun `spansDormancy is false for an out-of-range window`() {
        assertFalse(
            DormancyWindow.spansDormancy(
                startMonth = 0,
                endMonth = 2,
                fromMillis = millisAt(2026, 10, 25),
                toMillis = millisAt(2027, 3, 1)
            )
        )
    }

    @Test
    fun `spansDormancy is false when toMillis is not after fromMillis`() {
        val same = millisAt(2026, 12, 1)
        assertFalse(DormancyWindow.spansDormancy(startMonth = 11, endMonth = 2, fromMillis = same, toMillis = same))
    }

    // ---- spansDormancy: wrapping window (the canonical #761 example) ----

    @Test
    fun `spansDormancy is true for the canonical Oct25-to-Mar1 gap across a Nov-Feb window`() {
        assertTrue(
            DormancyWindow.spansDormancy(
                startMonth = 11,
                endMonth = 2,
                fromMillis = millisAt(2026, 10, 25),
                toMillis = millisAt(2027, 3, 1)
            )
        )
    }

    @Test
    fun `spansDormancy is false for a short on-schedule gap entirely outside a wrapping window`() {
        assertFalse(
            DormancyWindow.spansDormancy(
                startMonth = 11,
                endMonth = 2,
                fromMillis = millisAt(2026, 6, 1),
                toMillis = millisAt(2026, 6, 8)
            )
        )
    }

    // ---- spansDormancy: non-wrapping window ----

    @Test
    fun `spansDormancy is true crossing a non-wrapping summer window`() {
        assertTrue(
            DormancyWindow.spansDormancy(
                startMonth = 6,
                endMonth = 8,
                fromMillis = millisAt(2026, 5, 20),
                toMillis = millisAt(2026, 9, 5)
            )
        )
    }

    @Test
    fun `spansDormancy is false for a gap that never touches a non-wrapping summer window`() {
        assertFalse(
            DormancyWindow.spansDormancy(
                startMonth = 6,
                endMonth = 8,
                fromMillis = millisAt(2026, 9, 10),
                toMillis = millisAt(2026, 12, 1)
            )
        )
    }

    // ---- spansDormancy: span wholly inside the window ----

    @Test
    fun `spansDormancy is true for a span wholly inside a wrapping window`() {
        assertTrue(
            DormancyWindow.spansDormancy(
                startMonth = 11,
                endMonth = 2,
                fromMillis = millisAt(2026, 12, 5),
                toMillis = millisAt(2027, 1, 10)
            )
        )
    }

    // ---- spansDormancy: endpoint months themselves are dormant ----

    @Test
    fun `spansDormancy is true when the new watering itself falls inside the window`() {
        assertTrue(
            DormancyWindow.spansDormancy(
                startMonth = 11,
                endMonth = 2,
                fromMillis = millisAt(2026, 10, 25),
                toMillis = millisAt(2026, 12, 15)
            )
        )
    }

    // ---- spansDormancy: crosses the window twice / longer than a year ----

    @Test
    fun `spansDormancy is true for a gap spanning more than a year, crossing the window twice`() {
        assertTrue(
            DormancyWindow.spansDormancy(
                startMonth = 11,
                endMonth = 2,
                fromMillis = millisAt(2025, 10, 25),
                toMillis = millisAt(2027, 4, 10)
            )
        )
    }

    // ---- spansDormancy: the MONTHS_IN_YEAR - 1 shortcut threshold, pinned against a single-month
    // window (review round 2, Codex on #776) ----
    //
    // A wide window (like the Nov-Feb fixture used everywhere else in this file) can't discriminate
    // the shortcut threshold: an 11-month walk already hits *some* month of a 4-month window no
    // matter where the walk starts, so a test built on such a window passes identically whether the
    // threshold is `MONTHS_IN_YEAR - 1` (11, correct) or `MONTHS_IN_YEAR - 2` (10, off-by-one) — the
    // walk itself finds the match either way, with or without the shortcut ever firing. A single-month
    // window is the only fixture that can be missed by a walk exactly one month short, which is
    // precisely the case the threshold has to get right: a walk covering `MONTHS_IN_YEAR - 1` (11)
    // distinct months always omits exactly one month-of-year value, so a single-month window placed at
    // that omitted value is the worst case the shortcut must still handle correctly by *not* firing.

    @Test
    fun `an 11-month walk that omits the window's one month correctly returns false, not the shortcut`() {
        // Feb 2026 -> Dec 2026 is a 10-month gap (ChronoUnit.MONTHS.between on the 1st-of-month dates),
        // so the walk covers 11 consecutive months, Feb through Dec — every month-of-year value except
        // January. A single-month January window therefore genuinely does not overlap this span, and
        // 10 < MONTHS_IN_YEAR - 1 (11), so the shortcut must not fire here at all; the walk itself must
        // correctly conclude "no overlap".
        assertFalse(
            DormancyWindow.spansDormancy(
                startMonth = 1,
                endMonth = 1,
                fromMillis = millisAt(2026, 2, 5),
                toMillis = millisAt(2026, 12, 20)
            )
        )
    }

    @Test
    fun `one month longer, the same single-month window is now guaranteed and the shortcut fires`() {
        // Feb 2026 -> Jan 2027 is an 11-month gap, one month longer than the case above — now
        // MONTHS_IN_YEAR - 1 (11) is met, so the shortcut fires unconditionally. The walk this
        // guarantees now covers all 12 months (Feb through the following Jan), including the exact
        // January window the shorter span above just missed.
        assertTrue(
            DormancyWindow.spansDormancy(
                startMonth = 1,
                endMonth = 1,
                fromMillis = millisAt(2026, 2, 5),
                toMillis = millisAt(2027, 1, 20)
            )
        )
    }
}
