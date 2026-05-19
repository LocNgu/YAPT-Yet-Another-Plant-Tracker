package com.yapt.planttracker.domain.schedule

import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.util.toLocalDate
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

        val nowDate = now.toLocalDate()
        val isOverdue = nextDueAt != null && nextDueAt.toLocalDate().isBefore(nowDate)
        val isDueSoon = nextDueAt != null && !isOverdue && nextDueAt.toLocalDate() == nowDate

        val nextFertilizingDueAt = if (plant.fertilizingIntervalDays != null && lastFertilizedAt != null) {
            lastFertilizedAt + TimeUnit.DAYS.toMillis(plant.fertilizingIntervalDays.toLong())
        } else null
        val isFertilizingOverdue = nextFertilizingDueAt != null &&
            nextFertilizingDueAt.toLocalDate().isBefore(nowDate)
        val isFertilizingDueSoon = nextFertilizingDueAt != null && !isFertilizingOverdue &&
            nextFertilizingDueAt.toLocalDate() == nowDate

        return PlantCareStatus(
            plant = plant,
            lastWateredAt = lastWateredAt,
            lastFertilizedAt = lastFertilizedAt,
            daysSinceLastWatering = daysSinceWatering,
            nextWateringDueAt = nextDueAt,
            isOverdue = isOverdue,
            isDueSoon = isDueSoon,
            nextFertilizingDueAt = nextFertilizingDueAt,
            isFertilizingOverdue = isFertilizingOverdue,
            isFertilizingDueSoon = isFertilizingDueSoon,
            totalCareLogs = totalLogs
        )
    }

    fun computeSuggestedInterval(
        feedback: WateringFeedback,
        actualIntervalDays: Int,
        currentIntervalDays: Int? = null
    ): Int {
        return when (feedback) {
            WateringFeedback.TOO_LATE -> max(1, actualIntervalDays - 1)
            WateringFeedback.JUST_RIGHT -> actualIntervalDays
            WateringFeedback.TOO_SOON -> {
                val base = if (currentIntervalDays != null && actualIntervalDays < currentIntervalDays)
                    currentIntervalDays else actualIntervalDays
                base + 1
            }
        }
    }

    fun daysBetween(earlierMs: Long, laterMs: Long): Int =
        ((laterMs - earlierMs) / ONE_DAY_MS).toInt()
}
