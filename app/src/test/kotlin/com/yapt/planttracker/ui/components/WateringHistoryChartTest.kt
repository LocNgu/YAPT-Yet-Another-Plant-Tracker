package com.yapt.planttracker.ui.components

import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WateringHistoryChartTest {

    @Test
    fun computeWateringIntervals_emptyLogs() {
        val result = computeWateringIntervals(emptyList(), 0L, 1000L)
        assertTrue(result.isEmpty())
    }

    @Test
    fun computeWateringIntervals_singleLog() {
        val log = CareLog(
            id = 1,
            plantId = 1,
            careType = CareType.WATER,
            loggedAt = 500L
        )
        val result = computeWateringIntervals(listOf(log), 0L, 1000L)
        assertTrue(result.isEmpty())
    }

    @Test
    fun computeWateringIntervals_twoLogs() {
        val dayInMs = 24 * 60 * 60 * 1000L
        val log1 = CareLog(
            id = 1,
            plantId = 1,
            careType = CareType.WATER,
            loggedAt = 100L
        )
        val log2 = CareLog(
            id = 2,
            plantId = 1,
            careType = CareType.WATER,
            loggedAt = 100L + dayInMs
        )
        val result = computeWateringIntervals(listOf(log1, log2), 0L, 100L + dayInMs + 1)
        assertEquals(1, result.size)
        assertEquals(1f, result[0].daysSincePrevious, 0.01f)
    }

    @Test
    fun computeWateringIntervals_multipleLogsSorted() {
        val dayInMs = 24 * 60 * 60 * 1000L
        val logs = listOf(
            CareLog(id = 1, plantId = 1, careType = CareType.WATER, loggedAt = 100L),
            CareLog(id = 2, plantId = 1, careType = CareType.WATER, loggedAt = 100L + dayInMs),
            CareLog(id = 3, plantId = 1, careType = CareType.WATER, loggedAt = 100L + dayInMs * 3)
        )
        val result = computeWateringIntervals(logs, 0L, 100L + dayInMs * 4)
        assertEquals(2, result.size)
        assertEquals(1f, result[0].daysSincePrevious, 0.01f)
        assertEquals(2f, result[1].daysSincePrevious, 0.01f)
    }

    @Test
    fun computeWateringIntervals_filterByDateRange() {
        val dayInMs = 24 * 60 * 60 * 1000L
        val logs = listOf(
            CareLog(id = 1, plantId = 1, careType = CareType.WATER, loggedAt = 100L),
            CareLog(id = 2, plantId = 1, careType = CareType.WATER, loggedAt = 100L + dayInMs),
            CareLog(id = 3, plantId = 1, careType = CareType.WATER, loggedAt = 100L + dayInMs * 3)
        )
        val rangeStart = 100L + dayInMs / 2
        val rangeEnd = 100L + dayInMs * 3 + 1
        // log1 is before rangeStart → becomes synthetic predecessor for log2
        val result = computeWateringIntervals(logs, rangeStart, rangeEnd)
        assertEquals(2, result.size)
        assertEquals(1f, result[0].daysSincePrevious, 0.01f)  // log1 → log2
        assertEquals(2f, result[1].daysSincePrevious, 0.01f)  // log2 → log3
    }

    @Test
    fun computeWateringIntervals_singleInRangeWithPredecessor() {
        val dayInMs = 24 * 60 * 60 * 1000L
        val logs = listOf(
            CareLog(id = 1, plantId = 1, careType = CareType.WATER, loggedAt = 100L),
            CareLog(id = 2, plantId = 1, careType = CareType.WATER, loggedAt = 100L + dayInMs * 30)
        )
        // Range starts after log1 but before log2
        val rangeStart = 100L + dayInMs * 15
        val result = computeWateringIntervals(logs, rangeStart, 100L + dayInMs * 31)
        assertEquals(1, result.size)
        assertEquals(30f, result[0].daysSincePrevious, 0.01f)
        assertEquals(100L + dayInMs * 30, result[0].timestamp)
    }

    @Test
    fun computeWateringIntervals_singleInRangeNoPredecessor() {
        val dayInMs = 24 * 60 * 60 * 1000L
        val log = CareLog(id = 1, plantId = 1, careType = CareType.WATER, loggedAt = 100L + dayInMs)
        val result = computeWateringIntervals(listOf(log), 100L, 100L + dayInMs * 2)
        assertTrue(result.isEmpty())
    }

    @Test
    fun computeWateringIntervals_multipleInRangeWithPredecessor() {
        val dayInMs = 24 * 60 * 60 * 1000L
        val logs = listOf(
            CareLog(id = 1, plantId = 1, careType = CareType.WATER, loggedAt = 100L),
            CareLog(id = 2, plantId = 1, careType = CareType.WATER, loggedAt = 100L + dayInMs * 10),
            CareLog(id = 3, plantId = 1, careType = CareType.WATER, loggedAt = 100L + dayInMs * 25)
        )
        val rangeStart = 100L + dayInMs * 5  // log1 is before range
        val result = computeWateringIntervals(logs, rangeStart, 100L + dayInMs * 30)
        assertEquals(2, result.size)
        assertEquals(10f, result[0].daysSincePrevious, 0.01f)  // log1 → log2
        assertEquals(15f, result[1].daysSincePrevious, 0.01f)  // log2 → log3
    }

    @Test
    fun computeWateringIntervals_ignoreNonWateringLogs() {
        val dayInMs = 24 * 60 * 60 * 1000L
        val waterLogs = listOf(
            CareLog(id = 1, plantId = 1, careType = CareType.WATER, loggedAt = 100L),
            CareLog(id = 3, plantId = 1, careType = CareType.WATER, loggedAt = 100L + dayInMs)
        )
        val result = computeWateringIntervals(waterLogs, 0L, 100L + dayInMs + 1)
        assertEquals(1, result.size)
        assertEquals(1f, result[0].daysSincePrevious, 0.01f)
    }

    @Test
    fun computeWateringIntervals_fractionalDays() {
        val dayInMs = 24 * 60 * 60 * 1000L
        val log1 = CareLog(
            id = 1,
            plantId = 1,
            careType = CareType.WATER,
            loggedAt = 100L
        )
        val log2 = CareLog(
            id = 2,
            plantId = 1,
            careType = CareType.WATER,
            loggedAt = 100L + (dayInMs + dayInMs / 2)
        )
        val result = computeWateringIntervals(listOf(log1, log2), 0L, 100L + dayInMs * 2)
        assertEquals(1, result.size)
        assertEquals(1.5f, result[0].daysSincePrevious, 0.01f)
    }

    @Test
    fun computeWateringIntervals_timestampPreserved() {
        val dayInMs = 24 * 60 * 60 * 1000L
        val timestamp = 100L + dayInMs
        val log1 = CareLog(
            id = 1,
            plantId = 1,
            careType = CareType.WATER,
            loggedAt = 100L
        )
        val log2 = CareLog(
            id = 2,
            plantId = 1,
            careType = CareType.WATER,
            loggedAt = timestamp
        )
        val result = computeWateringIntervals(listOf(log1, log2), 0L, timestamp + 1)
        assertEquals(timestamp, result[0].timestamp)
    }

    @Test
    fun computeWateringIntervals_allTimeRange() {
        val dayInMs = 24 * 60 * 60 * 1000L
        val logs = listOf(
            CareLog(id = 1, plantId = 1, careType = CareType.WATER, loggedAt = 100L),
            CareLog(id = 2, plantId = 1, careType = CareType.WATER, loggedAt = 100L + dayInMs),
            CareLog(id = 3, plantId = 1, careType = CareType.WATER, loggedAt = 100L + dayInMs * 3)
        )
        val result = computeWateringIntervals(logs, 0L, 100L + dayInMs * 4)
        assertEquals(2, result.size)
        assertEquals(1f, result[0].daysSincePrevious, 0.01f)
        assertEquals(2f, result[1].daysSincePrevious, 0.01f)
    }

    @Test
    fun computeWateringIntervals_sameDayDifferentTimes() {
        val hourInMs = 60 * 60 * 1000L
        val baseTime = 1712102400000L
        val logs = listOf(
            CareLog(id = 1, plantId = 1, careType = CareType.WATER, loggedAt = baseTime),
            CareLog(id = 2, plantId = 1, careType = CareType.WATER, loggedAt = baseTime + hourInMs),
            CareLog(id = 3, plantId = 1, careType = CareType.WATER, loggedAt = baseTime + (hourInMs * 2))
        )
        val result = computeWateringIntervals(logs, baseTime - 86400000, baseTime + (hourInMs * 3))
        assertEquals(2, result.size)
        assertEquals(baseTime + hourInMs, result[0].timestamp)
        assertEquals(baseTime + (hourInMs * 2), result[1].timestamp)
        val expectedInterval = (1.0f / 24.0f)
        assertEquals(expectedInterval, result[0].daysSincePrevious, 0.01f)
        assertEquals(expectedInterval, result[1].daysSincePrevious, 0.01f)
    }

    @Test
    fun computeWateringIntervals_xAxisSpreadsMultipleDates() {
        val dayInMs = 24 * 60 * 60 * 1000L
        val hourInMs = 60 * 60 * 1000L
        val baseTime = 1712102400000L
        val logs = listOf(
            CareLog(id = 1, plantId = 1, careType = CareType.WATER, loggedAt = baseTime),
            CareLog(id = 2, plantId = 1, careType = CareType.WATER, loggedAt = baseTime + dayInMs),
            CareLog(id = 3, plantId = 1, careType = CareType.WATER, loggedAt = baseTime + (dayInMs * 2))
        )
        val result = computeWateringIntervals(logs, 0L, baseTime + (dayInMs * 3))
        assertEquals(2, result.size)
        val xValues = result.map { it.timestamp / 1_000f }
        assertTrue("X values should be different across days", xValues[0] != xValues[1])
        assertEquals(1f, result[0].daysSincePrevious, 0.01f)
        assertEquals(1f, result[1].daysSincePrevious, 0.01f)
    }
}
