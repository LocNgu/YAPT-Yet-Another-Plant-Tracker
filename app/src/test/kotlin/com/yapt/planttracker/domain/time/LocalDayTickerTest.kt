package com.yapt.planttracker.domain.time

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class LocalDayTickerTest {

    @Test
    fun `emits the new local date after delaying to midnight`() = runTest {
        val clock = MutableClock(
            Instant.parse("2026-09-27T21:59:30Z"),
            ZoneId.of("Europe/Berlin")
        )
        val delays = mutableListOf<Long>()
        val ticker = LocalDayTicker(clock) { millis ->
            delays += millis
            clock.advanceMillis(millis)
        }

        val dates = ticker.dates.take(2).toList()

        assertEquals(listOf(LocalDate.of(2026, 9, 27), LocalDate.of(2026, 9, 28)), dates)
        assertEquals(listOf(30_000L), delays)
    }

    @Test
    fun `delay follows the next local midnight across daylight saving time`() = runTest {
        val clock = MutableClock(
            Instant.parse("2026-10-24T10:00:00Z"),
            ZoneId.of("Europe/Berlin")
        )
        val delays = mutableListOf<Long>()
        val ticker = LocalDayTicker(clock) { millis ->
            delays += millis
            clock.advanceMillis(millis)
        }

        ticker.dates.take(3).toList()

        assertEquals(
            listOf(12L * 60L * 60L * 1000L, 25L * 60L * 60L * 1000L),
            delays
        )
    }

    private class MutableClock(
        private var current: Instant,
        private val currentZone: ZoneId
    ) : Clock() {
        override fun getZone(): ZoneId = currentZone
        override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)
        override fun instant(): Instant = current
        fun advanceMillis(millis: Long) {
            current = current.plusMillis(millis)
        }
    }
}
