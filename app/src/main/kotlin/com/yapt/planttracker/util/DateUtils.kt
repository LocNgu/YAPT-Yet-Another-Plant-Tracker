package com.yapt.planttracker.util

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

internal fun Long.toLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

object DateUtils {

    fun formatRelative(
        timestampMs: Long,
        now: Long = System.currentTimeMillis(),
        maxRelativeDays: Long? = null,
    ): String {
        val days = ChronoUnit.DAYS.between(timestampMs.toLocalDate(), now.toLocalDate())
        return when {
            days == 0L -> "Today"
            days == 1L -> "Yesterday"
            maxRelativeDays == null || days <= maxRelativeDays -> "$days days ago"
            else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                .format(Date(timestampMs))
        }
    }

    fun formatCountdown(dueAtMs: Long, now: Long = System.currentTimeMillis()): String {
        val diffDays = ChronoUnit.DAYS.between(now.toLocalDate(), dueAtMs.toLocalDate())
        return when {
            diffDays < 0 -> "Overdue by ${-diffDays} day${if (-diffDays == 1L) "" else "s"}"
            diffDays == 0L -> "Due today"
            else -> "In $diffDays day${if (diffDays == 1L) "" else "s"}"
        }
    }

    fun formatDate(timestampMs: Long): String =
        SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(timestampMs))

    fun formatTime(timestampMs: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMs))

    fun formatMonthYear(timestampMs: Long): String =
        SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(timestampMs))

    fun formatHourMinute(hour: Int, minute: Int): String =
        String.format(Locale.getDefault(), "%02d:%02d", hour, minute)

    fun formatWeekdayDate(epochDay: Long): String {
        val timestampMs = LocalDate.ofEpochDay(epochDay).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(timestampMs))
    }
}
