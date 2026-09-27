package com.yapt.planttracker.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Bounds how late a midnight-crossing emission can fire after a deep-sleep gap; see [dayChangeTicker]. */
internal const val DAY_CHANGE_TICKER_MAX_POLL_INTERVAL_MS = 3 * 60 * 1000L

/**
 * A cold [Flow] of [LocalDate] that emits the current local calendar date immediately on
 * collection (so a `combine()` isn't blocked waiting on it), then again exactly once per local
 * midnight crossing thereafter — the shared "day changed" trigger for a `combine()` block whose
 * output depends on wall-clock date (calendar-day math per technical ADR-0013/0034) but has no
 * flow of its own that emits purely because time advances (#550). Feed it into a `combine()` with
 * the emitted value ignored (`_`) to force a recomputation at midnight without polling the UI.
 *
 * The delay is recomputed from a **fresh** clock read on every loop and capped at
 * [maxPollIntervalMs] — Android's `delay()` on the main dispatcher rides on
 * `SystemClock.uptimeMillis()`, which does not advance during deep sleep, so a single
 * long-scheduled delay can fire late once the device wakes. The cap bounds how late that can be;
 * the "only emit on an actual date change" guard is what makes the ticker self-correcting
 * regardless of exactly when a given wake happens — an early or late wake with no real date change
 * just re-arms the delay with a freshly computed value instead of emitting a duplicate.
 *
 * [nowProvider]/[zone] mirror [com.yapt.planttracker.domain.usecase.QuickLogUseCase]'s injectable
 * clock convention so a test can pin both and drive virtual time deterministically.
 *
 * Callers that `stateIn(..., SharingStarted.WhileSubscribed(...))` the combined result get a second,
 * independent correction for free: leaving the screen stops the upstream collection, and returning
 * re-subscribes and re-collects this flow from scratch, which re-emits today's date immediately —
 * so a process backgrounded across midnight is already correct on the next collection even before
 * the ticker's own in-flight delay would have fired.
 */
fun dayChangeTicker(
    zone: ZoneId = ZoneId.systemDefault(),
    nowProvider: () -> Long = System::currentTimeMillis,
    maxPollIntervalMs: Long = DAY_CHANGE_TICKER_MAX_POLL_INTERVAL_MS
): Flow<LocalDate> = flow {
    var lastEmitted: LocalDate? = null
    while (true) {
        val nowInstant = Instant.ofEpochMilli(nowProvider())
        val today = nowInstant.atZone(zone).toLocalDate()
        if (today != lastEmitted) {
            emit(today)
            lastEmitted = today
        }
        val nextMidnight = today.plusDays(1).atStartOfDay(zone).toInstant()
        val untilMidnightMs = Duration.between(nowInstant, nextMidnight).toMillis().coerceAtLeast(0)
        delay(untilMidnightMs.coerceAtMost(maxPollIntervalMs))
    }
}
