package com.yapt.planttracker.domain.devmode

import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.util.toLocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Anchor-time math and log-building helpers shared by [DemoPlantBuilders]. Split out of
 * [DemoData] purely to keep each object's function count under Detekt's `TooManyFunctions`
 * threshold (#463) — there is no other reason for the split.
 */
internal object DemoDataTime {

    private const val PLACEHOLDER_PLANT_ID = 0L
    private const val ANCHOR_HOUR = 10
    private const val ANCHOR_MINUTE = 0

    // Deterministic jitter applied (by log index, cycling) to each historical watering so the
    // interval chart isn't a flat line. Index 0 always resolves to a plant's own "last watering"
    // offset, which must stay exact for CareSchedule's due-date math, so the cycle's first value
    // is 0.
    private val JITTER_CYCLE_DAYS = listOf(0, 1, -1, 2, -1)
    private val FEEDBACK_CYCLE = listOf(
        WateringFeedback.JUST_RIGHT,
        WateringFeedback.JUST_RIGHT,
        WateringFeedback.TOO_LATE,
        WateringFeedback.JUST_RIGHT,
        WateringFeedback.TOO_SOON
    )

    /** Today (from [now]) at [ANCHOR_HOUR]:00 local time — a fixed time-of-day avoids the
     * calendar-day boundary flakiness `CareSchedule`'s `LocalDate` comparisons are sensitive to
     * (technical ADR-0013). */
    fun anchorTimestamp(now: Long): Long {
        val today = now.toLocalDate()
        return today.atTime(ANCHOR_HOUR, ANCHOR_MINUTE)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    fun offsetMillis(anchor: Long, daysAgo: Int): Long =
        anchor - TimeUnit.DAYS.toMillis(daysAgo.toLong())

    fun careLog(
        anchor: Long,
        daysAgo: Int,
        careType: CareType,
        feedback: WateringFeedback? = null
    ): CareLog = CareLog(
        plantId = PLACEHOLDER_PLANT_ID,
        careType = careType,
        loggedAt = offsetMillis(anchor, daysAgo),
        wateringFeedback = feedback
    )

    /**
     * A plant's watering history: starts at [lastWaterDaysAgo] (kept exact, zero jitter, since it
     * drives `CareSchedule`'s due-date math) and steps backward by [intervalDays] plus the
     * deterministic [JITTER_CYCLE_DAYS] entry for that index until [historyDepthDays] is reached.
     */
    fun wateringHistoryLogs(
        anchor: Long,
        lastWaterDaysAgo: Int,
        intervalDays: Int,
        historyDepthDays: Int
    ): List<CareLog> {
        val logs = mutableListOf<CareLog>()
        var daysAgoCursor = lastWaterDaysAgo
        var index = 0
        while (daysAgoCursor <= historyDepthDays) {
            logs += careLog(anchor, daysAgoCursor, CareType.WATER, FEEDBACK_CYCLE[index % FEEDBACK_CYCLE.size])
            index++
            val jitter = JITTER_CYCLE_DAYS[index % JITTER_CYCLE_DAYS.size]
            daysAgoCursor += intervalDays + jitter
        }
        return logs
    }
}
