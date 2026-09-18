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

    // ---- isSelectableRescheduleDate zone-swap coverage (#748 review round 1) ----
    // Every case above uses fixedZone = UTC, so a regression swapping computedNextWateringDueAt's
    // conversion zone (zoneId <-> ZoneOffset.UTC) inside the due-date floor would be invisible there —
    // UTC-vs-UTC reads identically either way. These two pin that specific asymmetry, mirroring the
    // isOnOrAfterLocalToday pair above: same candidate/due-date instant, only the zone passed in differs.

    @Test
    fun `due date floor converts computedNextWateringDueAt via the caller's zone, not UTC`() {
        // tokyoReferenceInstant's UTC calendar day is 2026-01-15, but its Tokyo calendar day is
        // already 2026-01-16 (Tokyo, UTC+9, has rolled over) — see the fixture comment above.
        val computedNextWateringDueAt = tokyoReferenceInstant.toEpochMilli()
        // The picker always UTC-midnight-encodes a candidate, so this represents "2026-01-16" as tapped.
        val candidate = LocalDate.of(2026, 1, 16).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        // Same local (Tokyo) day as the due date -> on-day, rejected.
        assertFalse(
            isSelectableRescheduleDate(
                candidate,
                computedNextWateringDueAt,
                tokyo,
                today = LocalDate.of(2026, 1, 10)
            )
        )
    }

    @Test
    fun `same candidate would incorrectly clear the due date floor if it were converted via UTC instead`() {
        val computedNextWateringDueAt = tokyoReferenceInstant.toEpochMilli()
        val candidate = LocalDate.of(2026, 1, 16).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        // Converting the due date via ZoneOffset.UTC instead of the caller's zone reads it as
        // 2026-01-15 (a day earlier), wrongly making the 2026-01-16 candidate look strictly after it.
        assertTrue(
            isSelectableRescheduleDate(
                candidate,
                computedNextWateringDueAt,
                ZoneOffset.UTC,
                today = LocalDate.of(2026, 1, 10)
            )
        )
    }

    // ---- isRescheduleConfirmEnabled (#720 review round 1) ----

    @Test
    fun `confirm stays enabled when no date has been tapped yet`() {
        assertTrue(
            isRescheduleConfirmEnabled(
                selectedDateMillis = null,
                computedNextWateringDueAt = utcMidnightMillisFor(LocalDate.of(2026, 9, 20)),
                zoneId = fixedZone,
                today = fixedToday
            )
        )
    }

    @Test
    fun `confirm is enabled for a selection that still clears the due-date floor`() {
        val computedDueDate = utcMidnightMillisFor(LocalDate.of(2026, 9, 20))
        val selected = utcMidnightMillisFor(LocalDate.of(2026, 9, 22))

        assertTrue(
            isRescheduleConfirmEnabled(selected, computedDueDate, fixedZone, fixedToday)
        )
    }

    @Test
    fun `confirm is disabled once the due date has advanced past an already-tapped selection`() {
        val alreadyTapped = utcMidnightMillisFor(LocalDate.of(2026, 9, 22))
        // computedNextWateringDueAt moved forward (e.g. a watering logged from another surface) while
        // the dialog was still open, past the date the user already tapped.
        val advancedDueDate = utcMidnightMillisFor(LocalDate.of(2026, 9, 25))

        assertFalse(
            isRescheduleConfirmEnabled(alreadyTapped, advancedDueDate, fixedZone, fixedToday)
        )
    }
}
