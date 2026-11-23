package com.yapt.planttracker.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.yapt.planttracker.R
import com.yapt.planttracker.util.DateUtils

/** "Overdue by 2 days" / "Due today" / "In 3 days", from resources so it localizes (#1005). */
@Composable
fun countdownText(dueAtMs: Long, now: Long = System.currentTimeMillis()): String =
    when (val countdown = DateUtils.countdown(dueAtMs, now)) {
        is DateUtils.Countdown.Overdue -> pluralStringResource(
            R.plurals.countdown_overdue_by_days,
            countdown.days.toInt(),
            countdown.days
        )
        DateUtils.Countdown.DueToday -> stringResource(R.string.countdown_due_today)
        is DateUtils.Countdown.InDays -> pluralStringResource(
            R.plurals.countdown_in_days,
            countdown.days.toInt(),
            countdown.days
        )
    }
