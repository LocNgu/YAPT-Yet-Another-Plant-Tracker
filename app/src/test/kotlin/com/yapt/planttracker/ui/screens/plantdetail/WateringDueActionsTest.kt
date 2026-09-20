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

    // ---- isRescheduleTodayEnabled (#746, signature widened in review round 1 / #752) ----

    // A fixed instant far from fixedToday in either zone, used wherever a test wants
    // effectiveNextWateringDueAt to definitely not collide with "today" so it isolates the
    // computedNextWateringDueAt floor being tested.
    private val distantPastEffectiveDueDate = utcMidnightMillisFor(LocalDate.of(2020, 1, 1))

    @Test
    fun `today enabled when computed due date's local day is before today (the bug's target window)`() {
        // Mirrors the reported repro: computed due date before today, plus a winning *future*
        // override (effective due date after today) that would otherwise leave isOverdue false.
        val computedDueDate = utcMidnightMillisFor(fixedToday.minusDays(3))
        val effectiveDueDate = utcMidnightMillisFor(fixedToday.plusDays(7))

        assertTrue(isRescheduleTodayEnabled(computedDueDate, effectiveDueDate, fixedZone, fixedToday))
    }

    @Test
    fun `today disabled when computed due date is today (a true no-op)`() {
        val computedDueDate = utcMidnightMillisFor(fixedToday)

        assertFalse(
            isRescheduleTodayEnabled(computedDueDate, computedDueDate, fixedZone, fixedToday)
        )
    }

    @Test
    fun `today disabled when computed due date is in the future`() {
        val computedDueDate = utcMidnightMillisFor(fixedToday.plusDays(3))

        assertFalse(
            isRescheduleTodayEnabled(computedDueDate, computedDueDate, fixedZone, fixedToday)
        )
    }

    @Test
    fun `today enabled when both due dates are null (vacuous)`() {
        assertTrue(isRescheduleTodayEnabled(null, null, fixedZone, fixedToday))
    }

    @Test
    fun `review round 1 regression - computed due in the past but effective due already today stays disabled`() {
        // The Codex-found bug (#752): an overdue plant whose Today tap (or an active override) has
        // already pulled the effective due date to today. computedNextWateringDueAt is frozen in the
        // past (it's the pre-override date) and would clear the old, insufficient floor alone, but a
        // second tap would be a genuine no-op against the *current* effective date.
        val computedDueDate = utcMidnightMillisFor(fixedToday.minusDays(4))
        val effectiveDueDateToday = utcMidnightMillisFor(fixedToday)

        assertFalse(
            isRescheduleTodayEnabled(computedDueDate, effectiveDueDateToday, fixedZone, fixedToday)
        )
    }

    @Test
    fun `effective due date in the past despite an active override stays enabled`() {
        // An override that is itself already in the past (but later than the computed due date, so
        // it still wins maxOf()) — the plant is still overdue, just with a stale-but-winning
        // override. Tapping Today is not a no-op here.
        val computedDueDate = utcMidnightMillisFor(LocalDate.of(2026, 1, 10))
        val effectiveDueDate = utcMidnightMillisFor(LocalDate.of(2026, 2, 3))

        assertTrue(
            isRescheduleTodayEnabled(computedDueDate, effectiveDueDate, fixedZone, fixedToday)
        )
    }

    @Test
    fun `isOverdue implies isRescheduleTodayEnabled - superset invariant`() {
        // isOverdue is true whenever the *effective* (post-override) due date is strictly before
        // today; computedNextDueAt <= effective due date always, so isOverdue implies both that
        // computedNextDueAt clears the floor and that the effective date isn't today. Exercise
        // (computed, effective) pairs — including ones where an override makes them differ — and
        // confirm the new gate agrees on all of them.
        val overdueDueDatePairs = listOf(
            fixedToday.minusDays(1) to fixedToday.minusDays(1),
            fixedToday.minusDays(10) to fixedToday.minusDays(2),
            fixedToday.minusMonths(8) to fixedToday.minusMonths(8),
        )

        for ((computedDueDate, effectiveDueDate) in overdueDueDatePairs) {
            assertTrue(
                isRescheduleTodayEnabled(
                    utcMidnightMillisFor(computedDueDate),
                    utcMidnightMillisFor(effectiveDueDate),
                    fixedZone,
                    fixedToday
                )
            )
        }
    }

    @Test
    fun `today correctly a no-op in Tokyo once due date is converted via the caller's zone`() {
        // tokyoReferenceInstant's Tokyo calendar day is 2026-01-16 (already rolled over) while its
        // UTC calendar day is still 2026-01-15 — see the fixture comment above. Passing today as
        // 2026-01-16 (Tokyo's own calendar day for this instant) and converting the due date via
        // the correct (Tokyo) zone puts them on the same day: a true no-op, disabled.
        val computedNextWateringDueAt = tokyoReferenceInstant.toEpochMilli()
        val today = LocalDate.of(2026, 1, 16)

        assertFalse(
            isRescheduleTodayEnabled(
                computedNextWateringDueAt,
                distantPastEffectiveDueDate,
                tokyo,
                today = today
            )
        )
    }

    @Test
    fun `same due date would incorrectly enable today if converted via UTC instead of the caller's zone`() {
        val computedNextWateringDueAt = tokyoReferenceInstant.toEpochMilli()
        val today = LocalDate.of(2026, 1, 16)

        // Converting via ZoneOffset.UTC instead reads the due date as 2026-01-15 (a day earlier),
        // wrongly making "today" (still 2026-01-16) look strictly after it.
        assertTrue(
            isRescheduleTodayEnabled(
                computedNextWateringDueAt,
                distantPastEffectiveDueDate,
                ZoneOffset.UTC,
                today = today
            )
        )
    }

    // The pair above only pins the computedNextWateringDueAt floor's zone conversion — it feeds
    // effectiveNextWateringDueAt a zone-insensitive distant-past value, so a zone regression on the
    // *alreadyDueToday* term specifically would slip through undetected. This pair mirrors it for
    // that term instead: computedNextWateringDueAt is pinned safely in the past (identical under
    // either zone, since it sits exactly at UTC midnight) so it can't be what drives the result, and
    // effectiveNextWateringDueAt is the same tokyoReferenceInstant whose UTC/Tokyo calendar days
    // genuinely differ (#752 review round 2).

    @Test
    fun `already-due-today guard disables today once effective due date is converted via the caller's zone`() {
        // tokyoReferenceInstant's Tokyo calendar day is 2026-01-16 (matches tokyoToday) while its UTC
        // calendar day is still 2026-01-15 (does not) — see the fixture comment above.
        val effectiveNextWateringDueAt = tokyoReferenceInstant.toEpochMilli()

        assertFalse(
            isRescheduleTodayEnabled(
                distantPastEffectiveDueDate,
                effectiveNextWateringDueAt,
                tokyo,
                today = tokyoToday
            )
        )
    }

    @Test
    fun `same effective due date would incorrectly stay enabled if converted via UTC instead of the caller's zone`() {
        val effectiveNextWateringDueAt = tokyoReferenceInstant.toEpochMilli()

        // Converting via ZoneOffset.UTC instead reads the effective due date as 2026-01-15 — a day
        // earlier than tokyoToday (2026-01-16) — so alreadyDueToday wrongly evaluates false and
        // Today wrongly stays enabled.
        assertTrue(
            isRescheduleTodayEnabled(
                distantPastEffectiveDueDate,
                effectiveNextWateringDueAt,
                ZoneOffset.UTC,
                today = tokyoToday
            )
        )
    }

    @Test
    fun `isRescheduleTodayEnabled implies isSelectableRescheduleDate for the picker's own today cell`() {
        // Only a one-way implication holds since review round 1 (#752) — see the function's KDoc.
        val todayAsUtcMidnight = utcMidnightMillisFor(fixedToday)
        val enabledCases = listOf(
            // null/null (vacuous)
            null to null,
            // computed in the past, no override (effective == computed)
            utcMidnightMillisFor(fixedToday.minusDays(5)) to utcMidnightMillisFor(fixedToday.minusDays(5)),
            // computed in the past, winning future override
            utcMidnightMillisFor(fixedToday.minusDays(2)) to utcMidnightMillisFor(fixedToday.plusDays(5)),
        )

        for ((computedDueDate, effectiveDueDate) in enabledCases) {
            assertTrue(isRescheduleTodayEnabled(computedDueDate, effectiveDueDate, fixedZone, fixedToday))
            assertTrue(
                isSelectableRescheduleDate(todayAsUtcMidnight, computedDueDate, fixedZone, fixedToday)
            )
        }
    }

    @Test
    fun `today disabled while the picker's own today cell stays selectable when effective due is already today`() {
        // The one state where the two gates legitimately differ (documented in the function's
        // KDoc): the plant is no longer due today by the computed floor's account (it cleared long
        // ago), but the *effective* due date already is today, so tapping Today again would be a
        // no-op — a case the picker's day grid has no equivalent single-cell guard for.
        val computedDueDate = utcMidnightMillisFor(fixedToday.minusDays(10))
        val effectiveDueDateToday = utcMidnightMillisFor(fixedToday)

        assertFalse(
            isRescheduleTodayEnabled(computedDueDate, effectiveDueDateToday, fixedZone, fixedToday)
        )
        assertTrue(
            isSelectableRescheduleDate(utcMidnightMillisFor(fixedToday), computedDueDate, fixedZone, fixedToday)
        )
    }
}
