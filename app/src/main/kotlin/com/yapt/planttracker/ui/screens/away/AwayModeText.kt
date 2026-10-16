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

/** "Mon, Oct 7": the weekday and day, with the year only when it differs from [today]'s. */
internal fun awayWeekdayDayText(date: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String {
    val skeleton = if (date.year == today.year) "EEEMMMd" else "EEEyMMMd"
    val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
    return date.format(DateTimeFormatter.ofPattern(pattern, locale))
}

/** The copyable "Ask your sitter" text of the rhythm step (product ADR-0065). */
internal fun Context.askYourSitterText(everyDays: Int, firstVisitBy: LocalDate, today: LocalDate): String {
    val by = awayWeekdayDayText(firstVisitBy, today)
    return when (everyDays) {
        WEEKLY_RHYTHM_DAYS -> getString(R.string.sitter_ask_weekly, by)
        FORTNIGHTLY_RHYTHM_DAYS -> getString(R.string.sitter_ask_fortnightly, by)
        else -> resources.getQuantityString(R.plurals.sitter_ask_days, everyDays, everyDays, by)
    }
}

private const val WEEKLY_RHYTHM_DAYS = 7
private const val FORTNIGHTLY_RHYTHM_DAYS = 14

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

internal fun Context.beforeYouLeaveLoggedText(event: BeforeYouLeaveEvent.Logged): String {
    val logged = resources.getQuantityString(R.plurals.before_leave_logged, event.logged, event.logged)
    return if (event.skipped == 0) {
        logged
    } else {
        getString(
            R.string.before_leave_logged_with_skipped,
            logged,
            resources.getQuantityString(R.plurals.before_leave_skipped, event.skipped, event.skipped)
        )
    }
}

private const val MAX_LISTED_NAMES = 5

/** "Basil, Fern and 3 more": the first few names of a warning, so a long list can't swamp the summary card. */
internal fun Context.namesText(names: List<String>): String {
    val shown = names.take(MAX_LISTED_NAMES).joinToString(", ")
    val more = names.size - MAX_LISTED_NAMES
    return if (more > 0) getString(R.string.sitter_plan_names_more, shown, more) else shown
}

internal fun android.content.Context.planVisitEventText(event: PlanVisitEvent): String = when (event) {
    is PlanVisitEvent.Confirmed -> {
        val logged = resources.getQuantityString(R.plurals.before_leave_logged, event.logged, event.logged)
        if (event.skipped == 0) {
            logged
        } else {
            getString(
                R.string.before_leave_logged_with_skipped,
                logged,
                resources.getQuantityString(R.plurals.sitter_confirm_skipped, event.skipped, event.skipped)
            )
        }
    }
    PlanVisitEvent.DidNotHappen -> getString(R.string.sitter_confirm_did_not_happen_done)
    PlanVisitEvent.ShareFailed -> getString(R.string.sitter_share_failed)
    is PlanVisitEvent.SharePdf, is PlanVisitEvent.ShareText -> ""
}
