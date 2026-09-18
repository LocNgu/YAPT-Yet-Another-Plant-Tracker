package com.yapt.planttracker.ui.screens.plantdetail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * [isOnOrAfterLocalToday] boundary coverage (#508 review fix) — the [DatePicker] operates on UTC
 * midnight, but the picked date is always reinterpreted as a local calendar day downstream
 * ([utcMidnightMsToLocalStartOfDayMillis]), so "today" must be evaluated in the caller's local zone,
 * not UTC.
 *
 * Every test below pins a fixed reference [Instant] and passes an explicit `today` (the injectable
 * parameter added for this fix) instead of relying on [LocalDate.now], so results never depend on
 * when CI happens to run. Each test's candidate/`today` pair is chosen so the assertion would flip
 * if [isOnOrAfterLocalToday] reverted to comparing against `LocalDate.now(ZoneOffset.UTC)` instead of
 * the caller's local zone — that reversion is proven directly by pairing each "correct" assertion with
 * a sibling test that supplies the naive UTC-only `today` for the same candidate instant and shows the
 * opposite result.
 */
class WateringDueActionsTest {

    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val losAngeles = ZoneId.of("America/Los_Angeles")

    // 2026-01-15T20:00:00Z is 2026-01-16 05:00 in Tokyo (Tokyo, UTC+9, has already rolled over to
    // the next calendar day) but is still 2026-01-15 in UTC itself.
    private val tokyoReferenceInstant = Instant.parse("2026-01-15T20:00:00Z")
    private val tokyoToday = tokyoReferenceInstant.atZone(tokyo).toLocalDate()
    private val utcCalendarDayAtTokyoReference = tokyoReferenceInstant.atZone(ZoneOffset.UTC).toLocalDate()

    // 2026-01-16T02:00:00Z is 2026-01-15 18:00 in Los Angeles (Los Angeles, UTC-8 in January, is
    // still on the previous calendar day) but UTC itself has already rolled over to the 16th.
    private val losAngelesReferenceInstant = Instant.parse("2026-01-16T02:00:00Z")
    private val losAngelesToday = losAngelesReferenceInstant.atZone(losAngeles).toLocalDate()
    private val utcCalendarDayAtLosAngelesReference =
        losAngelesReferenceInstant.atZone(ZoneOffset.UTC).toLocalDate()

    @Test
    fun `UTC-midnight instant for UTC's calendar day is not selectable once Tokyo has rolled over to the next day`() {
        val candidateMillis = utcCalendarDayAtTokyoReference.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        assertFalse(isOnOrAfterLocalToday(candidateMillis, tokyo, today = tokyoToday))
    }

    @Test
    fun `same candidate would incorrectly be selectable in Tokyo if today fell back to UTC's calendar day`() {
        val candidateMillis = utcCalendarDayAtTokyoReference.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        assertTrue(isOnOrAfterLocalToday(candidateMillis, tokyo, today = utcCalendarDayAtTokyoReference))
    }

    @Test
    fun `UTC-midnight instant for Los Angeles's calendar day is selectable even though UTC has already rolled over`() {
        val candidateMillis = losAngelesToday.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        assertTrue(isOnOrAfterLocalToday(candidateMillis, losAngeles, today = losAngelesToday))
    }

    @Test
    fun `same candidate would incorrectly be unselectable in Los Angeles if today fell back to UTC's calendar day`() {
        val candidateMillis = losAngelesToday.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        assertFalse(
            isOnOrAfterLocalToday(candidateMillis, losAngeles, today = utcCalendarDayAtLosAngelesReference),
        )
    }

    // ---- isSelectableRescheduleDate (#720) ----

    private val fixedZone = ZoneId.of("UTC")
    private val fixedToday = LocalDate.of(2026, 9, 18)

    private fun utcMidnightMillisFor(date: LocalDate): Long =
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun `date strictly after the computed due date is accepted`() {
        val computedDueDate = LocalDate.of(2026, 9, 20)
        val candidate = utcMidnightMillisFor(LocalDate.of(2026, 9, 21))

        assertTrue(
            isSelectableRescheduleDate(
                candidate,
                utcMidnightMillisFor(computedDueDate),
                fixedZone,
                fixedToday
            )
        )
    }

    @Test
    fun `date on the computed due date's local day is rejected`() {
        val computedDueDate = LocalDate.of(2026, 9, 20)
        val candidate = utcMidnightMillisFor(computedDueDate)

        assertFalse(
            isSelectableRescheduleDate(
                candidate,
                utcMidnightMillisFor(computedDueDate),
                fixedZone,
                fixedToday
            )
        )
    }

    @Test
    fun `date before local today is rejected even when after the computed due date`() {
        // Computed due date is in the past (plant overdue since January), today is in September —
        // the today-floor must still reject a February/March/etc pick even though it clears the
        // due-date floor.
        val computedDueDate = LocalDate.of(2026, 1, 10)
        val candidate = utcMidnightMillisFor(LocalDate.of(2026, 2, 1))

        assertFalse(
            isSelectableRescheduleDate(
                candidate,
                utcMidnightMillisFor(computedDueDate),
                fixedZone,
                fixedToday
            )
        )
    }

    @Test
    fun `computed due date in the past accepts today (the overdue no-op case)`() {
        val computedDueDate = LocalDate.of(2026, 9, 10)
        val candidate = utcMidnightMillisFor(fixedToday)

        assertTrue(
            isSelectableRescheduleDate(
                candidate,
                utcMidnightMillisFor(computedDueDate),
                fixedZone,
                fixedToday
            )
        )
    }

    @Test
    fun `null computed due date preserves today-floor-only behaviour`() {
        val candidateToday = utcMidnightMillisFor(fixedToday)
        val candidateYesterday = utcMidnightMillisFor(fixedToday.minusDays(1))

        assertTrue(isSelectableRescheduleDate(candidateToday, null, fixedZone, fixedToday))
        assertFalse(isSelectableRescheduleDate(candidateYesterday, null, fixedZone, fixedToday))
    }
}
