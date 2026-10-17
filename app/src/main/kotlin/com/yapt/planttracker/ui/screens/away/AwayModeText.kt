package com.yapt.planttracker.ui.screens.away

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.away.AwayPeriodValidation
import com.yapt.planttracker.domain.away.AwayPeriods
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "Oct 21", or "Jan 4, 2027" when the year differs from [today]'s. */
internal fun awayDayText(date: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String {
    val skeleton = if (date.year == today.year) "MMMd" else "yMMMd"
    val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
    return date.format(DateTimeFormatter.ofPattern(pattern, locale))
}

internal fun Context.awayValidationText(validation: AwayPeriodValidation, today: LocalDate): String =
    when (validation) {
        AwayPeriodValidation.Valid -> ""
        AwayPeriodValidation.FirstDayAfterLastDay -> getString(R.string.away_error_first_after_last)
        AwayPeriodValidation.EndsInThePast -> getString(R.string.away_error_ends_in_past)
        AwayPeriodValidation.TooLong -> getString(R.string.away_error_too_long, AwayPeriods.MAX_TRIP_DAYS)
        is AwayPeriodValidation.OverlapsTrip -> getString(
            R.string.away_error_overlaps,
            awayDayText(validation.other.firstDay, today),
            awayDayText(validation.other.lastDay, today)
        )
        is AwayPeriodValidation.AnotherCurrentOrUpcomingTrip -> getString(
            R.string.away_error_another_trip,
            awayDayText(validation.other.firstDay, today),
            awayDayText(validation.other.lastDay, today)
        )
    }

internal fun Context.awayEventText(event: AwayModeEvent, today: LocalDate): String = when (event) {
    is AwayModeEvent.VisitsRemoved ->
        resources.getQuantityString(R.plurals.away_visits_removed, event.count, event.count)
    is AwayModeEvent.Rejected -> awayValidationText(event.validation, today)
}

/** Turns the ViewModel's one-shot events into the host screen's snackbar messages. */
@Composable
internal fun AwayModeEventsEffect(viewModel: AwayModeViewModel, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val latestOnMessage by rememberUpdatedState(onMessage)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val today = viewModel.uiState.value?.today ?: LocalDate.now()
            latestOnMessage(context.awayEventText(event, today))
        }
    }
}
