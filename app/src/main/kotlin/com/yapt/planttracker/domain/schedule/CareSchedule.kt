package com.yapt.planttracker.domain.schedule

import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.util.toLocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlin.math.max

object CareSchedule {

    private val ONE_DAY_MS = TimeUnit.DAYS.toMillis(1)

    /**
     * Never-fertilized plants get a grace period before they start being flagged as due, anchored
     * to when the plant was added. Nursery mixes typically contain slow-release fertilizer and
     * new plants are acclimating, so a single fertilizing interval (often ~2 weeks) is too short
     * a hold-off (issue #428).
     */
    const val FIRST_FERTILIZE_GRACE_DAYS = 30

    fun computeStatus(
        plant: Plant,
        lastWateredAt: Long?,
        lastFertilizedAt: Long?,
        totalLogs: Int,
        now: Long = System.currentTimeMillis(),
        lastMistedAt: Long? = null,
        lastRepottedAt: Long? = null
    ): PlantCareStatus {
        val daysSinceWatering = lastWateredAt?.let {
            (now - it) / ONE_DAY_MS
        }

        val computedNextDueAt = if (plant.wateringIntervalDays == null) {
            null
        } else if (lastWateredAt != null) {
            lastWateredAt + TimeUnit.DAYS.toMillis(plant.wateringIntervalDays.toLong())
        } else {
            now
        }

        val nextDueAt = when {
            computedNextDueAt == null -> plant.wateringDueDateOverride
            plant.wateringDueDateOverride == null -> computedNextDueAt
            else -> maxOf(computedNextDueAt, plant.wateringDueDateOverride)
        }

        val nowDate = now.toLocalDate()
        val isOverdue = nextDueAt != null && nextDueAt.toLocalDate().isBefore(nowDate)
        val isDueSoon = nextDueAt != null && !isOverdue && nextDueAt.toLocalDate() == nowDate

        val nextFertilizingDueAt = if (plant.fertilizingIntervalDays == null) {
            null
        } else if (lastFertilizedAt != null) {
            lastFertilizedAt + TimeUnit.DAYS.toMillis(plant.fertilizingIntervalDays.toLong())
        } else {
            plant.createdAt + TimeUnit.DAYS.toMillis(FIRST_FERTILIZE_GRACE_DAYS.toLong())
        }
        val isFertilizingOverdue = nextFertilizingDueAt != null &&
            nextFertilizingDueAt.toLocalDate().isBefore(nowDate)
        val isFertilizingDueSoon = nextFertilizingDueAt != null && !isFertilizingOverdue &&
            nextFertilizingDueAt.toLocalDate() == nowDate

        val nextMistingDueAt = extendedCareDueAt(plant.mistingIntervalDays, lastMistedAt, plant.createdAt)
        val isMistingOverdue = nextMistingDueAt != null && nextMistingDueAt.toLocalDate().isBefore(nowDate)
        val isMistingDueSoon = nextMistingDueAt != null && !isMistingOverdue &&
            nextMistingDueAt.toLocalDate() == nowDate

        val nextRepottingDueAt = extendedCareDueAt(plant.repottingIntervalDays, lastRepottedAt, plant.createdAt)
        val isRepottingOverdue = nextRepottingDueAt != null && nextRepottingDueAt.toLocalDate().isBefore(nowDate)
        val isRepottingDueSoon = nextRepottingDueAt != null && !isRepottingOverdue &&
            nextRepottingDueAt.toLocalDate() == nowDate

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
            totalCareLogs = totalLogs,
            lastMistedAt = lastMistedAt,
            nextMistingDueAt = nextMistingDueAt,
            isMistingOverdue = isMistingOverdue,
            isMistingDueSoon = isMistingDueSoon,
            lastRepottedAt = lastRepottedAt,
            nextRepottingDueAt = nextRepottingDueAt,
            isRepottingOverdue = isRepottingOverdue,
            isRepottingDueSoon = isRepottingDueSoon
        )
    }

    /**
     * Due date for an extended-care reminder (misting, repotting). Returns `null` when the interval
     * is unset. For a plant that has never had this care logged, the first due date is anchored to
     * `createdAt + interval` rather than the day the reminder was enabled — a newly acquired plant
     * was presumably just misted/repotted, so it should not fire immediately (see product ADR-0021).
     */
    private fun extendedCareDueAt(intervalDays: Int?, lastDoneAt: Long?, createdAt: Long): Long? {
        if (intervalDays == null) return null
        val base = lastDoneAt ?: createdAt
        return base + TimeUnit.DAYS.toMillis(intervalDays.toLong())
    }

    fun computeSuggestedInterval(
        feedback: WateringFeedback,
        actualIntervalDays: Int,
        currentIntervalDays: Int? = null
    ): Int {
        return when (feedback) {
            WateringFeedback.TOO_LATE -> {
                val base = if (currentIntervalDays != null && actualIntervalDays > currentIntervalDays) {
                    currentIntervalDays
                } else {
                    actualIntervalDays
                }
                max(1, base - 1)
            }
            WateringFeedback.JUST_RIGHT -> actualIntervalDays
            WateringFeedback.TOO_SOON -> {
                val base = if (currentIntervalDays != null && actualIntervalDays < currentIntervalDays) {
                    currentIntervalDays
                } else {
                    actualIntervalDays
                }
                base + 1
            }
        }.coerceAtLeast(1)
    }

    fun daysBetween(earlierMs: Long, laterMs: Long): Int =
        ChronoUnit.DAYS.between(earlierMs.toLocalDate(), laterMs.toLocalDate()).toInt()
}
