package com.yapt.planttracker.domain.schedule

import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.domain.model.WateringFeedback
import java.util.concurrent.TimeUnit
import kotlin.math.max

object CareSchedule {

    private val ONE_DAY_MS = TimeUnit.DAYS.toMillis(1)

    fun computeStatus(
        plant: Plant,
        lastWateredAt: Long?,
        lastFertilizedAt: Long?,
        totalLogs: Int,
        now: Long = System.currentTimeMillis()
    ): PlantCareStatus {
        val daysSinceWatering = lastWateredAt?.let {
            (now - it) / ONE_DAY_MS
        }

        val nextDueAt = if (plant.wateringIntervalDays != null && lastWateredAt != null) {
            lastWateredAt + TimeUnit.DAYS.toMillis(plant.wateringIntervalDays.toLong())
        } else null

        val isOverdue = nextDueAt != null && nextDueAt < now
        val isDueSoon = nextDueAt != null && !isOverdue &&
            (nextDueAt - now) <= ONE_DAY_MS

        return PlantCareStatus(
            plant = plant,
            lastWateredAt = lastWateredAt,
            lastFertilizedAt = lastFertilizedAt,
            daysSinceLastWatering = daysSinceWatering,
            nextWateringDueAt = nextDueAt,
            isOverdue = isOverdue,
            isDueSoon = isDueSoon,
            totalCareLogs = totalLogs
        )
    }

    fun computeSuggestedInterval(feedback: WateringFeedback, actualIntervalDays: Int): Int {
        return when (feedback) {
            WateringFeedback.TOO_LATE -> max(1, actualIntervalDays - 1)
            WateringFeedback.JUST_RIGHT -> actualIntervalDays
            WateringFeedback.TOO_SOON -> actualIntervalDays + 1
        }
    }

    fun daysBetween(earlierMs: Long, laterMs: Long): Int =
        ((laterMs - earlierMs) / ONE_DAY_MS).toInt()
}
