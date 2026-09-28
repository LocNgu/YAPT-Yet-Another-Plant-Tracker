package com.yapt.planttracker.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.yapt.planttracker.R
import com.yapt.planttracker.util.DateUtils

@Composable
fun relativeDateText(
    timestampMs: Long,
    now: Long = System.currentTimeMillis(),
    maxRelativeDays: Long? = null
): String = when (val relative = DateUtils.relativeDate(timestampMs, now, maxRelativeDays)) {
    DateUtils.RelativeDate.Today -> stringResource(R.string.date_relative_today)
    DateUtils.RelativeDate.Tomorrow -> stringResource(R.string.date_group_tomorrow)
    DateUtils.RelativeDate.Yesterday -> stringResource(R.string.date_relative_yesterday)
    is DateUtils.RelativeDate.InDays -> pluralStringResource(
        R.plurals.date_relative_in_days,
        relative.count.toInt(),
        relative.count
    )
    is DateUtils.RelativeDate.DaysAgo -> pluralStringResource(
        R.plurals.date_relative_days_ago,
        relative.count.toInt(),
        relative.count
    )
    is DateUtils.RelativeDate.ExactDate -> relative.value
}
