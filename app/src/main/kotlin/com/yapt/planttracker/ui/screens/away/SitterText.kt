package com.yapt.planttracker.ui.screens.away

import android.content.Context
import android.text.format.DateFormat
import com.yapt.planttracker.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val WEEKLY_RHYTHM_DAYS = 7
private const val FORTNIGHTLY_RHYTHM_DAYS = 14
private const val MAX_LISTED_NAMES = 5

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

/** The first few names of a warning, comma separated, and how many more there are: a long list can't swamp the card. */
internal fun cappedNames(names: List<String>): Pair<String, Int> =
    names.take(MAX_LISTED_NAMES).joinToString(", ") to (names.size - MAX_LISTED_NAMES).coerceAtLeast(0)

internal fun Context.beforeYouLeaveLoggedText(event: BeforeYouLeaveEvent.Logged): String =
    loggedWithSkipped(event.logged, event.skipped, R.plurals.before_leave_skipped)

internal fun Context.planVisitEventText(event: PlanVisitEvent): String = when (event) {
    is PlanVisitEvent.Confirmed -> loggedWithSkipped(event.logged, event.skipped, R.plurals.sitter_confirm_skipped)
    PlanVisitEvent.DidNotHappen -> getString(R.string.sitter_confirm_did_not_happen_done)
    PlanVisitEvent.ShareFailed -> getString(R.string.sitter_share_failed)
    is PlanVisitEvent.SharePdf, is PlanVisitEvent.ShareText -> ""
}

private fun Context.loggedWithSkipped(logged: Int, skipped: Int, skippedPlurals: Int): String {
    val loggedText = resources.getQuantityString(R.plurals.before_leave_logged, logged, logged)
    return if (skipped == 0) {
        loggedText
    } else {
        getString(
            R.string.before_leave_logged_with_skipped,
            loggedText,
            resources.getQuantityString(skippedPlurals, skipped, skipped)
        )
    }
}
