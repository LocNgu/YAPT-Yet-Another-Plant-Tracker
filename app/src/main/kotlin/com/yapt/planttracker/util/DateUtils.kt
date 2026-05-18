package com.yapt.planttracker.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

object DateUtils {

    fun formatRelative(timestampMs: Long, now: Long = System.currentTimeMillis()): String {
        val diffMs = now - timestampMs
        val days = TimeUnit.MILLISECONDS.toDays(diffMs)
        return when {
            days == 0L -> "Today"
            days == 1L -> "Yesterday"
            days < 7L -> "$days days ago"
            days < 30L -> "${days / 7} week${if (days / 7 > 1) "s" else ""} ago"
            else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                .format(Date(timestampMs))
        }
    }

    fun formatCountdown(dueAtMs: Long, now: Long = System.currentTimeMillis()): String {
        val diffMs = dueAtMs - now
        val absDays = TimeUnit.MILLISECONDS.toDays(abs(diffMs))
        return when {
            diffMs < 0 && absDays == 0L -> "Overdue"
            diffMs < 0 -> "Overdue by $absDays day${if (absDays == 1L) "" else "s"}"
            absDays == 0L -> "Due today"
            else -> "In $absDays day${if (absDays == 1L) "" else "s"}"
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
}
