package com.yapt.planttracker.ui.components

import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

class WateringHistoryChartTest {

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

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
        assertEquals(1f, result[0].daysSincePrevious, 0.01f) // log1 → log2
        assertEquals(2f, result[1].daysSincePrevious, 0.01f) // log2 → log3
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
        val rangeStart = 100L + dayInMs * 5 // log1 is before range
        val result = computeWateringIntervals(logs, rangeStart, 100L + dayInMs * 30)
        assertEquals(2, result.size)
        assertEquals(10f, result[0].daysSincePrevious, 0.01f) // log1 → log2
        assertEquals(15f, result[1].daysSincePrevious, 0.01f) // log2 → log3
    }

    @Test
    fun computeWateringIntervals_noInRangeWithTwoPredecessors() {
        val dayInMs = 24 * 60 * 60 * 1000L
        val logs = listOf(
            CareLog(id = 1, plantId = 1, careType = CareType.WATER, loggedAt = 100L),
            CareLog(id = 2, plantId = 1, careType = CareType.WATER, loggedAt = 100L + dayInMs * 45),
            CareLog(id = 3, plantId = 1, careType = CareType.WATER, loggedAt = 100L + dayInMs * 90)
        )
        // Range starts after all waterings (simulates a plant watered every 45 days on 1M view)
        val rangeStart = 100L + dayInMs * 100
        val result = computeWateringIntervals(logs, rangeStart, 100L + dayInMs * 130)
        assertEquals(1, result.size)
        assertEquals(45f, result[0].daysSincePrevious, 0.01f)
        assertEquals(100L + dayInMs * 90, result[0].timestamp)
    }

    @Test
    fun computeWateringIntervals_noInRangeWithOnePredecessor() {
        val dayInMs = 24 * 60 * 60 * 1000L
        val log = CareLog(id = 1, plantId = 1, careType = CareType.WATER, loggedAt = 100L)
        val rangeStart = 100L + dayInMs * 10
        val result = computeWateringIntervals(listOf(log), rangeStart, 100L + dayInMs * 20)
        assertTrue(result.isEmpty())
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
    fun computeWateringIntervals_sameDayDifferentTimes_flooredToMinimumOneDay() {
        // Sub-day gaps are floored to a minimum of 1 displayed day, so two waterings an hour
        // apart don't plot as a near-zero fractional interval on the chart.
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
        assertEquals(1f, result[0].daysSincePrevious, 0.01f)
        assertEquals(1f, result[1].daysSincePrevious, 0.01f)
    }

    @Test
    fun computeWateringIntervals_averageOfMixedGaps_isAtLeastOneDay() {
        // A mix of a sub-day gap and a multi-day gap must never pull the average interval
        // (shown in the chart legend) below 1 day.
        val hourInMs = 60 * 60 * 1000L
        val dayInMs = 24 * hourInMs
        val baseTime = 1712102400000L
        val logs = listOf(
            CareLog(id = 1, plantId = 1, careType = CareType.WATER, loggedAt = baseTime),
            CareLog(id = 2, plantId = 1, careType = CareType.WATER, loggedAt = baseTime + hourInMs),
            CareLog(id = 3, plantId = 1, careType = CareType.WATER, loggedAt = baseTime + hourInMs + dayInMs * 3)
        )
        val result = computeWateringIntervals(logs, 0L, baseTime + hourInMs + dayInMs * 3 + 1)
        assertEquals(2, result.size)
        val average = result.map { it.daysSincePrevious }.average()
        assertTrue("Average interval should never drop below 1 day even with a sub-day gap", average >= 1.0)
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

    // --- computeCareEventMarkers tests ---

    private fun dayMs(days: Long) = days * 24L * 60 * 60 * 1000

    private val baseMs: Long = ZonedDateTime.of(2025, 3, 1, 0, 0, 0, 0, ZoneId.of("UTC"))
        .toInstant().toEpochMilli()

    @Test
    fun computeCareEventMarkers_emptyLogs() {
        val result = computeCareEventMarkers(emptyList(), baseMs, baseMs + dayMs(30))
        assertTrue(result.isEmpty())
    }

    @Test
    fun computeCareEventMarkers_waterLogsExcluded() {
        val waterLog = CareLog(id = 1, plantId = 1, careType = CareType.WATER, loggedAt = baseMs + dayMs(1))
        val result = computeCareEventMarkers(listOf(waterLog), baseMs, baseMs + dayMs(30))
        assertTrue(result.isEmpty())
    }

    @Test
    fun computeCareEventMarkers_outOfRangeExcluded() {
        val before = CareLog(id = 1, plantId = 1, careType = CareType.PRUNE, loggedAt = baseMs - dayMs(1))
        val after = CareLog(id = 2, plantId = 1, careType = CareType.MIST, loggedAt = baseMs + dayMs(31))
        val result = computeCareEventMarkers(listOf(before, after), baseMs, baseMs + dayMs(30))
        assertTrue(result.isEmpty())
    }

    @Test
    fun computeCareEventMarkers_inRangeReturned() {
        val repot = CareLog(id = 1, plantId = 1, careType = CareType.REPOT, loggedAt = baseMs + dayMs(5))
        val result = computeCareEventMarkers(listOf(repot), baseMs, baseMs + dayMs(30))
        assertEquals(1, result.size)
        assertEquals(CareType.REPOT, result[0].careType)
        assertEquals(baseMs + dayMs(5), result[0].timestamp)
    }

    @Test
    fun computeCareEventMarkers_correctMonthIndex() {
        val zone = ZoneId.of("UTC")
        // rangeStartMs is 2025-03-01; month 0 = March, month 1 = April
        val marchLog = CareLog(id = 1, plantId = 1, careType = CareType.PRUNE, loggedAt = baseMs + dayMs(10))
        val aprilLog = CareLog(
            id = 2,
            plantId = 1,
            careType = CareType.MIST,
            loggedAt = ZonedDateTime.of(2025, 4, 5, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        )
        val endMs = ZonedDateTime.of(2025, 4, 30, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val result = computeCareEventMarkers(listOf(marchLog, aprilLog), baseMs, endMs)
        assertEquals(2, result.size)
        // March 11 = day 11 of 31 → 0 + 10/31 ≈ 0.32
        assertEquals(0.32f, result[0].monthIndex, 0.05f)
        // April 5 = day 5 of 30 → 1 + 4/30 ≈ 1.13
        assertEquals(1.13f, result[1].monthIndex, 0.05f)
    }

    @Test
    fun computeCareEventMarkers_multipleTypesAllReturned() {
        val prune = CareLog(id = 1, plantId = 1, careType = CareType.PRUNE, loggedAt = baseMs + dayMs(1))
        val mist = CareLog(id = 2, plantId = 1, careType = CareType.MIST, loggedAt = baseMs + dayMs(2))
        val repot = CareLog(id = 3, plantId = 1, careType = CareType.REPOT, loggedAt = baseMs + dayMs(3))
        val result = computeCareEventMarkers(listOf(prune, mist, repot), baseMs, baseMs + dayMs(30))
        assertEquals(3, result.size)
        val types = result.map { it.careType }
        assertTrue(types.contains(CareType.PRUNE))
        assertTrue(types.contains(CareType.MIST))
        assertTrue(types.contains(CareType.REPOT))
    }

    @Test
    fun computeCareEventMarkers_sortedByTimestamp() {
        val log3 = CareLog(id = 3, plantId = 1, careType = CareType.REPOT, loggedAt = baseMs + dayMs(15))
        val log1 = CareLog(id = 1, plantId = 1, careType = CareType.PRUNE, loggedAt = baseMs + dayMs(5))
        val log2 = CareLog(id = 2, plantId = 1, careType = CareType.MIST, loggedAt = baseMs + dayMs(10))
        val result = computeCareEventMarkers(listOf(log3, log1, log2), baseMs, baseMs + dayMs(30))
        assertEquals(3, result.size)
        assertTrue(result[0].timestamp < result[1].timestamp)
        assertTrue(result[1].timestamp < result[2].timestamp)
    }

    @Test
    fun computeCareEventMarkers_nowBoundaryIncludes() {
        val exactly = CareLog(id = 1, plantId = 1, careType = CareType.MIST, loggedAt = baseMs + dayMs(30))
        val result = computeCareEventMarkers(listOf(exactly), baseMs, baseMs + dayMs(30))
        assertEquals(1, result.size)
    }

    @Test
    fun computeCareEventMarkers_monthIndex_alignsWithEffectiveStart() {
        // Regression: when effectiveStartMs is earlier than rangeStartMs (predecessor-interval
        // optimization), monthIndex must be relative to effectiveStartMs so icons land on the
        // correct x position on the chart.
        val effectiveStartMs = baseMs - dayMs(28) // Feb 1, 2025 UTC
        val marchLog = CareLog(id = 1, plantId = 1, careType = CareType.PRUNE, loggedAt = baseMs + dayMs(10))
        val result = computeCareEventMarkers(
            careLogs = listOf(marchLog),
            rangeStartMs = baseMs,
            now = baseMs + dayMs(30),
            effectiveStartMs = effectiveStartMs,
        )
        assertEquals(1, result.size)
        // Chart month 0 = February, month 1 = March.
        // March 11 = day 11 of 31 → 1 + 10/31 ≈ 1.32
        assertEquals(1.32f, result[0].monthIndex, 0.05f)
    }
}

class ClusterMarkersByCxTest {
    private fun marker(cx: Float, ts: Long = 0L): PositionedMarker =
        PositionedMarker(cx, CareEventMarker(monthIndex = 0f, careType = CareType.PRUNE, timestamp = ts))

    @Test
    fun empty_returnsEmpty() {
        assertTrue(clusterMarkersByCx(emptyList(), 14f).isEmpty())
    }

    @Test
    fun single_returnsOneCluster() {
        val result = clusterMarkersByCx(listOf(marker(5f)), 14f)
        assertEquals(1, result.size)
        assertEquals(1, result[0].size)
    }

    @Test
    fun twoWithin_clustered() {
        val result = clusterMarkersByCx(listOf(marker(0f), marker(10f)), 14f)
        assertEquals(1, result.size)
        assertEquals(2, result[0].size)
    }

    @Test
    fun twoAtThreshold_clustered() {
        // Exactly iconSize apart: 14f - 0f == 14f, not > 14f, so they cluster
        val result = clusterMarkersByCx(listOf(marker(0f), marker(14f)), 14f)
        assertEquals(1, result.size)
        assertEquals(2, result[0].size)
    }

    @Test
    fun twoBeyond_separateClusters() {
        val result = clusterMarkersByCx(listOf(marker(0f), marker(15f)), 14f)
        assertEquals(2, result.size)
        assertEquals(1, result[0].size)
        assertEquals(1, result[1].size)
    }

    @Test
    fun threeFirstTwoClose_thirdFar() {
        val result = clusterMarkersByCx(listOf(marker(0f), marker(5f), marker(20f)), 14f)
        assertEquals(2, result.size)
        assertEquals(2, result[0].size)
        assertEquals(1, result[1].size)
    }

    @Test
    fun threeLastTwoClose_firstFar() {
        val result = clusterMarkersByCx(listOf(marker(0f), marker(15f), marker(20f)), 14f)
        assertEquals(2, result.size)
        assertEquals(1, result[0].size)
        assertEquals(2, result[1].size)
    }

    @Test
    fun bridging_usesLastMemberNotAnchor() {
        // Items at 0, 13, 14.5 with iconSize=14. Using first() as anchor:
        // 14.5 - 0 = 14.5 > 14 → splits, cluster avg = 6.5, next item at 14.5 → overlap.
        // Using last() as anchor: 14.5 - 13 = 1.5 <= 14 → all three cluster together.
        val result = clusterMarkersByCx(listOf(marker(0f), marker(13f), marker(14.5f)), 14f)
        assertEquals(1, result.size)
        assertEquals(3, result[0].size)
    }
}

class MarkerCyTest {

    // Layer bounds: top = 100 (small y), bottom = 300 (large y). On a canvas, smaller y is
    // higher up, so the largest data value maps to `top` and the smallest to `bottom`.

    @Test
    fun minValueMapsToBottom() {
        assertEquals(300f, markerCy(daysSincePrevious = 2f, yMin = 2f, yMax = 10f, top = 100f, bottom = 300f), 0.01f)
    }

    @Test
    fun maxValueMapsToTop() {
        assertEquals(100f, markerCy(daysSincePrevious = 10f, yMin = 2f, yMax = 10f, top = 100f, bottom = 300f), 0.01f)
    }

    @Test
    fun midpointMapsToMiddle() {
        assertEquals(200f, markerCy(daysSincePrevious = 6f, yMin = 2f, yMax = 10f, top = 100f, bottom = 300f), 0.01f)
    }

    @Test
    fun degenerateRangeMapsToVerticalCenter() {
        // yMax == yMin (all points equal) → vertical centre, no division by zero.
        assertEquals(200f, markerCy(daysSincePrevious = 5f, yMin = 5f, yMax = 5f, top = 100f, bottom = 300f), 0.01f)
    }
}

class ComputeWaterEventMarkersTest {

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private val baseMs: Long = ZonedDateTime.of(2025, 3, 1, 0, 0, 0, 0, ZoneId.of("UTC"))
        .toInstant().toEpochMilli()
    private val dayMs = 24L * 60 * 60 * 1000

    private fun interval(loggedAt: Long, daysSince: Float = 7f) =
        WateringInterval(timestamp = loggedAt, daysSincePrevious = daysSince)

    @Test
    fun emptyIntervals_returnsEmpty() {
        val result = computeWaterEventMarkers(emptyList(), baseMs, baseMs + dayMs * 30)
        assertTrue(result.isEmpty())
    }

    @Test
    fun intervalBeforeRangeStart_excluded() {
        val before = interval(baseMs - dayMs)
        val result = computeWaterEventMarkers(listOf(before), baseMs, baseMs + dayMs * 30)
        assertTrue(result.isEmpty())
    }

    @Test
    fun intervalAfterNow_excluded() {
        val after = interval(baseMs + dayMs * 31)
        val result = computeWaterEventMarkers(listOf(after), baseMs, baseMs + dayMs * 30)
        assertTrue(result.isEmpty())
    }

    @Test
    fun intervalAtNowBoundary_included() {
        val exactly = interval(baseMs + dayMs * 30)
        val result = computeWaterEventMarkers(listOf(exactly), baseMs, baseMs + dayMs * 30)
        assertEquals(1, result.size)
    }

    @Test
    fun daysSincePreviousPreserved() {
        val iv = interval(baseMs + dayMs * 5, daysSince = 14f)
        val result = computeWaterEventMarkers(listOf(iv), baseMs, baseMs + dayMs * 30)
        assertEquals(14f, result[0].daysSincePrevious, 0.01f)
    }

    @Test
    fun timestampPreserved() {
        val ts = baseMs + dayMs * 10
        val iv = interval(ts)
        val result = computeWaterEventMarkers(listOf(iv), baseMs, baseMs + dayMs * 30)
        assertEquals(ts, result[0].timestamp)
    }

    @Test
    fun fractionalMonthIndex_midMonth() {
        // March 16 = day 16 of 31 → monthIndex = 0 + 15/31 ≈ 0.484
        val ts = baseMs + dayMs * 15 // 2025-03-16 00:00 UTC
        val iv = interval(ts)
        val result = computeWaterEventMarkers(listOf(iv), baseMs, baseMs + dayMs * 30)
        assertEquals(0.484f, result[0].monthIndex, 0.01f)
    }

    @Test
    fun twoWateringsInSameMonth_distinctFractionalPositions() {
        val early = interval(baseMs + dayMs * 2) // March 3
        val late = interval(baseMs + dayMs * 20) // March 21
        val result = computeWaterEventMarkers(listOf(early, late), baseMs, baseMs + dayMs * 30)
        assertEquals(2, result.size)
        assertTrue(result[0].monthIndex < result[1].monthIndex)
    }

    @Test
    fun effectiveStartMs_alignment() {
        // Regression: when effectiveStartMs is earlier than rangeStartMs the monthIndex
        // must be relative to effectiveStartMs, matching the chart's month-walk origin.
        val effectiveStartMs = baseMs - dayMs * 28 // Feb 1, 2025 UTC
        val marchTs = baseMs + dayMs * 10 // March 11
        val iv = interval(marchTs)
        val result = computeWaterEventMarkers(
            intervals = listOf(iv),
            rangeStartMs = baseMs,
            now = baseMs + dayMs * 30,
            effectiveStartMs = effectiveStartMs,
        )
        // Chart month 0 = February (effectiveStartMs), month 1 = March.
        // March 11 = day 11 of 31 → 1 + 10/31 ≈ 1.32
        assertEquals(1.32f, result[0].monthIndex, 0.05f)
    }
}

class CatmullRomSegmentsTest {

    @Test
    fun emptyInput_returnsEmpty() {
        assertTrue(catmullRomSegments(emptyList()).isEmpty())
    }

    @Test
    fun singlePoint_returnsEmpty() {
        assertTrue(catmullRomSegments(listOf(1f to 2f)).isEmpty())
    }

    @Test
    fun twoPoints_oneSegment_endsAtSecondPoint() {
        val segments = catmullRomSegments(listOf(0f to 0f, 10f to 5f))
        assertEquals(1, segments.size)
        assertEquals(10f, segments[0].endX, 1e-4f)
        assertEquals(5f, segments[0].endY, 1e-4f)
    }

    @Test
    fun curvePassesThroughEveryPoint() {
        val points = listOf(0f to 1f, 1f to 4f, 2f to 2f, 3f to 6f)
        val segments = catmullRomSegments(points)
        assertEquals(points.size - 1, segments.size)
        // The end of segment i is exactly input point i+1, so the curve interpolates each point.
        segments.forEachIndexed { i, s ->
            assertEquals(points[i + 1].first, s.endX, 1e-4f)
            assertEquals(points[i + 1].second, s.endY, 1e-4f)
        }
    }

    @Test
    fun collinearPoints_controlPointsStayOnLine() {
        // Points on y = x; a Catmull-Rom spline through collinear points must keep every
        // Bézier control point on the same line, so a straight run renders straight (no wobble).
        val points = listOf(0f to 0f, 1f to 1f, 2f to 2f, 3f to 3f)
        catmullRomSegments(points).forEach { s ->
            assertEquals(s.c1x, s.c1y, 1e-4f)
            assertEquals(s.c2x, s.c2y, 1e-4f)
        }
    }

    @Test
    fun controlPointsDoNotOvershootEndpointYRange() {
        // A sharp asymmetric peak — unclamped Catmull-Rom would push a control point above the
        // peak value (e.g. 45 + (20-5)/6 = 47.5). Clamping must keep every control-point y within
        // the [min, max] of its own segment's endpoints, so the curve never bulges past a point.
        val points = listOf(0f to 5f, 1f to 45f, 2f to 20f, 3f to 5f)
        val segments = catmullRomSegments(points)
        segments.forEachIndexed { i, s ->
            val lo = minOf(points[i].second, points[i + 1].second)
            val hi = maxOf(points[i].second, points[i + 1].second)
            assertTrue("c1y ${s.c1y} outside [$lo, $hi]", s.c1y in lo..hi)
            assertTrue("c2y ${s.c2y} outside [$lo, $hi]", s.c2y in lo..hi)
        }
    }
}

class ComputeYAxisStepTest {

    @Test
    fun smallRange_stepIsOne() {
        assertEquals(1, computeYAxisStep(1.0))
        assertEquals(1, computeYAxisStep(5.0))
    }

    @Test
    fun largerRange_stepScalesUp() {
        assertEquals(2, computeYAxisStep(6.0))
        assertEquals(6, computeYAxisStep(30.0))
    }

    @Test
    fun stepNeverProducesMoreThanSixTicks() {
        listOf(1.0, 7.0, 13.0, 45.0, 200.0).forEach { yMax ->
            val step = computeYAxisStep(yMax)
            val tickCount = (yMax / step).let { kotlin.math.ceil(it).toInt() } + 1
            assertTrue("yMax=$yMax step=$step produced $tickCount ticks", tickCount <= 6)
        }
    }

    @Test
    fun stepIsNeverBelowOne() {
        assertEquals(1, computeYAxisStep(0.0))
    }
}
