package com.yapt.planttracker.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Exercises [dayChangeTicker] directly with a controllable clock. Deliberately never uses
 * `advanceUntilIdle()` — the ticker's `while (true) { ...; delay(...) }` always re-schedules
 * another delayed continuation before suspending, so `advanceUntilIdle()` would keep finding more
 * work forever and hang (#550 implementation note). `advanceTimeBy(ms)` + `runCurrent()` instead,
 * always with an explicit, bounded amount.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DayChangeTickerTest {

    private fun millisAt(zone: ZoneId, date: LocalDate, hour: Int = 0, minute: Int = 0): Long =
        ZonedDateTime.of(date, java.time.LocalTime.of(hour, minute), zone).toInstant().toEpochMilli()

    @Test
    fun `emits the current local date immediately on collection`() = runTest {
        val zone = ZoneId.of("UTC")
        val now = millisAt(zone, LocalDate.of(2026, 6, 15), 10, 30)
        val emitted = mutableListOf<LocalDate>()
        val job = launch {
            dayChangeTicker(zone, { now }, maxPollIntervalMs = 60_000L).collect { emitted.add(it) }
        }
        runCurrent()

        assertEquals(listOf(LocalDate.of(2026, 6, 15)), emitted)
        job.cancel()
    }

    @Test
    fun `emits again once local midnight passes`() = runTest {
        val zone = ZoneId.of("UTC")
        var current = millisAt(zone, LocalDate.of(2026, 6, 15), 23, 59)
        val emitted = mutableListOf<LocalDate>()
        val job = launch {
            dayChangeTicker(zone, { current }, maxPollIntervalMs = 60_000L).collect { emitted.add(it) }
        }
        runCurrent()
        assertEquals(listOf(LocalDate.of(2026, 6, 15)), emitted)

        // The wall clock genuinely advances past midnight in lockstep with the virtual delay firing.
        current = millisAt(zone, LocalDate.of(2026, 6, 16), 0, 0)
        advanceTimeBy(60_000L)
        runCurrent()

        assertEquals(listOf(LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 16)), emitted)
        job.cancel()
    }

    @Test
    fun `computes the correct delay across a DST fall-back day`() = runTest {
        val zone = ZoneId.of("America/New_York")
        var current = millisAt(zone, LocalDate.of(2026, 11, 1), 0, 0)
        val emitted = mutableListOf<LocalDate>()
        val job = launch {
            dayChangeTicker(zone, { current }, maxPollIntervalMs = Long.MAX_VALUE / 2).collect { emitted.add(it) }
        }
        runCurrent()
        assertEquals(listOf(LocalDate.of(2026, 11, 1)), emitted)
        val beforeMs = testScheduler.currentTime

        // Nov 1 2026 is the US fall-back day in America/New_York -- 25 real hours, not 24 -- so the
        // ticker's own delay must resolve to that true duration (technical ADR-0034 calendar-day
        // math via ZonedDateTime), not a fixed 24h span that would fire an hour early.
        current = millisAt(zone, LocalDate.of(2026, 11, 2), 0, 0)
        val expectedDelayMs = Duration.ofHours(25).toMillis()
        advanceTimeBy(expectedDelayMs)
        runCurrent()

        assertEquals(listOf(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2)), emitted)
        assertEquals(expectedDelayMs, testScheduler.currentTime - beforeMs)
        job.cancel()
    }

    @Test
    fun `does not emit again while capped polls see no real date change`() = runTest {
        val zone = ZoneId.of("UTC")
        val current = millisAt(zone, LocalDate.of(2026, 6, 15), 0, 0)
        val emitted = mutableListOf<LocalDate>()
        val job = launch {
            dayChangeTicker(zone, { current }, maxPollIntervalMs = 1_000L).collect { emitted.add(it) }
        }
        runCurrent()
        assertEquals(listOf(LocalDate.of(2026, 6, 15)), emitted)

        // Several capped polls elapse with the wall clock genuinely unmoved -- none should emit.
        repeat(5) {
            advanceTimeBy(1_000L)
            runCurrent()
        }

        assertEquals(listOf(LocalDate.of(2026, 6, 15)), emitted)
        job.cancel()
    }

    @Test
    fun `a late wake still emits promptly once the date has actually changed`() = runTest {
        val zone = ZoneId.of("UTC")
        var current = millisAt(zone, LocalDate.of(2026, 6, 15), 0, 0)
        val emitted = mutableListOf<LocalDate>()
        val job = launch {
            dayChangeTicker(zone, { current }, maxPollIntervalMs = 1_000L).collect { emitted.add(it) }
        }
        runCurrent()
        assertEquals(listOf(LocalDate.of(2026, 6, 15)), emitted)

        // Simulate a deep-sleep gap: the wall clock jumps two calendar days ahead in one step even
        // though only a single capped poll interval of scheduler time advances -- the very next
        // wake must emit promptly rather than waiting for another full poll interval.
        current = millisAt(zone, LocalDate.of(2026, 6, 17), 9, 0)
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(listOf(LocalDate.of(2026, 6, 15), LocalDate.of(2026, 6, 17)), emitted)
        job.cancel()
    }
}
