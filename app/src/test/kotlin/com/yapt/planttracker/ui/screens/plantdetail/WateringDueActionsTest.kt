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
}
