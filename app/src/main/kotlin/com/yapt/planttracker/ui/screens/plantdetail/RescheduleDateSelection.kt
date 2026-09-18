package com.yapt.planttracker.ui.screens.plantdetail

import androidx.compose.material3.DatePicker
import androidx.compose.material3.SelectableDates
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * [DatePicker]'s `selectedDateMillis`/`SelectableDates` always operate on UTC midnight, regardless of
 * device timezone (a documented Material3 API quirk, and also how Material3's own `CalendarModel`
 * highlights "today"). The calendar day number is read out of [utcTimeMillis] in [ZoneOffset.UTC] since
 * that's the zone the picker encodes it in — but it's compared against **local** "today"
 * ([zoneId], defaulting to [ZoneId.systemDefault]), not UTC "today": [utcMidnightMsToLocalStartOfDayMillis]
 * always reinterprets the picked day as a local calendar day downstream, matching how
 * `CareSchedule.dueStatusFor`'s `isOverdue`/`isDueSoon` compare via `Long.toLocalDate()` (also
 * [ZoneId.systemDefault]). Comparing against UTC "today" instead would let a user in a timezone ahead
 * of UTC (e.g. UTC+9) tap the picker's own highlighted "today" cell during local hours before the UTC
 * day rolls over and end up with a `wateringDueDateOverride` whose local calendar day is still in the
 * past relative to their actual today.
 */
internal fun isOnOrAfterLocalToday(
    utcTimeMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zoneId),
): Boolean {
    val candidate = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
    return !candidate.isBefore(today)
}

/**
 * The custom-date picker's full gate (#720): a candidate must clear **both** [isOnOrAfterLocalToday]
 * and a new floor against [computedNextWateringDueAt] — the schedule-computed due date *before*
 * `Plant.wateringDueDateOverride` is applied (`CareSchedule.computeWateringDue()`'s
 * `maxOf(computedNextDueAt, override)`). An override whose local calendar day is on or before
 * [computedNextWateringDueAt]'s local calendar day can only tie or lose that `maxOf()` and would be
 * written to the database only to be silently discarded — see #720. Both floors are independently
 * load-bearing: a plant overdue since January, with today in September, would have February/March/…
 * wrongly accepted by the due-date floor alone, and a plant due next month would have "today" wrongly
 * accepted by the today floor alone.
 *
 * [computedNextWateringDueAt] is a real epoch-millis instant (not UTC-encoded like the picker's own
 * [utcTimeMillis]) and must be converted via [zoneId] — **not** [ZoneOffset.UTC] — to compare local
 * calendar days consistently with [isOnOrAfterLocalToday]'s own local-day comparison. `null` (no
 * watering interval configured) makes the due-date floor vacuous, leaving the today floor as the only
 * constraint.
 */
internal fun isSelectableRescheduleDate(
    utcTimeMillis: Long,
    computedNextWateringDueAt: Long?,
    zoneId: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zoneId),
): Boolean {
    val clearsDueDateFloor = computedNextWateringDueAt == null || run {
        val candidate = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
        val computedDueDate = Instant.ofEpochMilli(computedNextWateringDueAt).atZone(zoneId).toLocalDate()
        candidate.isAfter(computedDueDate)
    }
    return isOnOrAfterLocalToday(utcTimeMillis, zoneId, today) && clearsDueDateFloor
}

/**
 * Pure predicate backing `RescheduleDatePickerDialog`'s OK button `enabled` state (#720 review round
 * 1) — pulled out of the composable so this contract is directly JVM-testable without driving
 * Material3's real `DatePicker` grid. `null` (nothing tapped yet) stays enabled, matching the
 * documented "OK closes the picker either way" behavior; a non-null selection is re-validated against
 * the *current* [computedNextWateringDueAt] via [isSelectableRescheduleDate], since that floor can
 * move after the date was originally tapped. [zoneId]/[today] are injectable for the same reason
 * [isSelectableRescheduleDate]'s are — deterministic tests, no dependency on when they run.
 */
internal fun isRescheduleConfirmEnabled(
    selectedDateMillis: Long?,
    computedNextWateringDueAt: Long?,
    zoneId: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zoneId),
): Boolean =
    selectedDateMillis == null ||
        isSelectableRescheduleDate(selectedDateMillis, computedNextWateringDueAt, zoneId, today)

internal class TodayOrLaterSelectableDates(private val computedNextWateringDueAt: Long?) : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
        isSelectableRescheduleDate(utcTimeMillis, computedNextWateringDueAt)
}

/** Converts a picked UTC-midnight date to local start-of-day, mirroring `AddCareLogScreen`'s date-picker handling. */
internal fun utcMidnightMsToLocalStartOfDayMillis(utcMidnightMs: Long): Long {
    val pickedDate = Instant.ofEpochMilli(utcMidnightMs).atZone(ZoneOffset.UTC).toLocalDate()
    return pickedDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
