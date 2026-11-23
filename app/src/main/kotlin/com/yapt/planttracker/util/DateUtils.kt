package com.yapt.planttracker.util

import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

internal fun Long.toLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

/** The inverse of [toLocalDate]: midnight of [this] date in the system default zone, as epoch millis. */
internal fun LocalDate.toStartOfDayMillis(): Long =
    atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** Noon of [this] date in the system default zone, as epoch millis: always an instant on that local day. */
internal fun LocalDate.toNoonMillis(): Long =
    atTime(LocalTime.NOON).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

/**
 * Advances [this] instant by [days] **calendar** days in [zone], preserving local wall-clock
 * time-of-day — the local date always moves by exactly [days], the local time-of-day never changes.
 * This is deliberately not `this + TimeUnit.DAYS.toMillis(days)`: a fixed-24h-per-day span is correct
 * for a *duration* (e.g. the REPOT freeze window in `WateringLifecycleReset`, which really is "28 real
 * days from now"), but every due-date/deferral site in the scheduling path means "the same time of day,
 * N calendar days from now" — and local calendar days are not always 24 hours. A DST fall-back day
 * (e.g. `America/New_York`'s 02:00 -> 01:00 transition) is 25 hours long, so adding a fixed 24h on such
 * a day can leave the result on the *same* local calendar date, one day short of what every downstream
 * consumer expects when it compares dates via [toLocalDate] (technical ADR-0013). See #733 and
 * technical ADR-0034.
 */
internal fun Long.plusCalendarDays(days: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(this).atZone(zone).plusDays(days).toInstant().toEpochMilli()

object DateUtils {

    sealed interface RelativeDate {
        data object Tomorrow : RelativeDate
        data class InDays(val count: Long) : RelativeDate
        data object Today : RelativeDate
        data object Yesterday : RelativeDate
        data class DaysAgo(val count: Long) : RelativeDate
        data class ExactDate(val value: String) : RelativeDate
    }

    fun relativeDate(
        timestampMs: Long,
        now: Long = System.currentTimeMillis(),
        maxRelativeDays: Long? = null,
    ): RelativeDate {
        val days = ChronoUnit.DAYS.between(timestampMs.toLocalDate(), now.toLocalDate())
        return when {
            days == -1L -> RelativeDate.Tomorrow
            days < -1L -> RelativeDate.InDays(-days)
            days == 0L -> RelativeDate.Today
            days == 1L -> RelativeDate.Yesterday
            maxRelativeDays == null || days <= maxRelativeDays -> RelativeDate.DaysAgo(days)
            else -> RelativeDate.ExactDate(formatDate(timestampMs))
        }
    }

    /** Calendar-day distance to a due date; rendered from resources by `countdownText()` (#1005). */
    sealed interface Countdown {
        data class Overdue(val days: Long) : Countdown
        data object DueToday : Countdown
        data class InDays(val days: Long) : Countdown
    }

    fun countdown(dueAtMs: Long, now: Long = System.currentTimeMillis()): Countdown {
        val diffDays = ChronoUnit.DAYS.between(now.toLocalDate(), dueAtMs.toLocalDate())
        return when {
            diffDays < 0 -> Countdown.Overdue(-diffDays)
            diffDays == 0L -> Countdown.DueToday
            else -> Countdown.InDays(diffDays)
        }
    }

    /**
     * The locale's own field order and punctuation for a date [skeleton] (e.g. `yMMMd` is
     * "Oct 30, 2023" in English, "30. Okt. 2023" in German). Never hardcode an English-ordered
     * pattern such as `MMM d, yyyy` for user-visible dates (#1005).
     */
    fun localizedPattern(skeleton: String, locale: Locale = Locale.getDefault()): String =
        DateFormat.getBestDateTimePattern(locale, skeleton)

    fun formatDate(timestampMs: Long): String =
        SimpleDateFormat(localizedPattern("yMMMd"), Locale.getDefault()).format(Date(timestampMs))

    fun formatTime(timestampMs: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMs))

    fun formatMonthYear(timestampMs: Long): String =
        SimpleDateFormat(localizedPattern("yMMMM"), Locale.getDefault()).format(Date(timestampMs))

    fun formatHourMinute(hour: Int, minute: Int): String =
        String.format(Locale.getDefault(), "%02d:%02d", hour, minute)

    /**
     * The half-open epoch-millis range `[startOfToday, startOfTomorrow)` for the calendar day
     * containing [now], in the system default zone. Used to select care logs whose
     * `loggedAt.toLocalDate() == today` without repeating date math inline (technical ADR-0013).
     */
    fun todayRangeMillis(now: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val today = now.toLocalDate()
        val startOfToday = today.toStartOfDayMillis()
        val startOfTomorrow = today.plusDays(1).toStartOfDayMillis()
        return startOfToday to startOfTomorrow
    }

    fun formatWeekdayDate(epochDay: Long): String {
        val timestampMs = LocalDate.ofEpochDay(epochDay).toStartOfDayMillis()
        return SimpleDateFormat(localizedPattern("EEEMMMd"), Locale.getDefault()).format(Date(timestampMs))
    }
}
