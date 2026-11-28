package com.yapt.planttracker.domain.insights

import com.yapt.planttracker.domain.away.AwayPeriods
import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantIssue
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.domain.schedule.DormancyWindow
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.util.toLocalDate
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A plant watered late [lateCount] times inside the window. */
data class LateWateringItem(val plant: Plant, val lateCount: Int)

data class CareCountItem(val type: CareType, val count: Int)

/** An unresolved issue; [daysActive] is whole calendar days since it started (0 = started today). */
data class ActiveIssueItem(val plant: Plant, val issue: PlantIssue, val daysActive: Int)

data class CollectionInsights(
    val lateWaterings: List<LateWateringItem>,
    val careCounts: List<CareCountItem>,
    val activeIssues: List<ActiveIssueItem>
) {
    val totalCareCount: Int get() = careCounts.sumOf { it.count }
}

/**
 * The Collection insights page (#1004, product ADR-0089). Pure: every date is a calendar day via
 * [toLocalDate] (technical ADR-0013). It reads care history only; a "currently overdue" state is never
 * derived here, that stays with `TodayQueueAggregator`.
 */
object CollectionInsightsBuilder {

    const val WINDOW_DAYS = 30
    const val LATE_THRESHOLD = 2

    // Retired types (CHECK, MIST, NOTE) are never counted, product ADR-0089.
    private val COUNTED_TYPES = listOf(
        CareType.WATER,
        CareType.FERTILIZE,
        CareType.PRUNE,
        CareType.REPOT,
        CareType.PHOTO,
        CareType.PROPAGATE,
        CareType.CUSTOM
    )

    @Suppress("LongParameterList")
    fun build(
        plants: List<Plant>,
        careLogs: List<CareLog>,
        issues: List<PlantIssue>,
        awayPeriods: List<AwayPeriod>,
        seasonalAmplitude: Double,
        now: Long,
        hemisphere: Hemisphere
    ): CollectionInsights {
        val today = now.toLocalDate()
        val windowStart = today.minusDays((WINDOW_DAYS - 1).toLong())
        val visible = plants.filter { it.isVisible }.associateBy { it.id }
        val logsByPlant = careLogs.filter { it.plantId in visible }.groupBy { it.plantId }

        val late = visible.values.mapNotNull { plant ->
            val count = lateWateringCount(
                plant,
                logsByPlant[plant.id].orEmpty(),
                awayPeriods,
                windowStart,
                today,
                seasonalAmplitude,
                hemisphere
            )
            if (count >= LATE_THRESHOLD) LateWateringItem(plant, count) else null
        }.sortedWith(
            compareByDescending<LateWateringItem> { it.lateCount }
                .thenBy { it.plant.name.lowercase() }
                .thenBy { it.plant.id }
        )

        val inWindow = logsByPlant.values.flatten().filter { it.loggedAt.toLocalDate() in windowStart..today }
        val counts = COUNTED_TYPES.mapNotNull { type ->
            inWindow.count { it.careType == type }.takeIf { it > 0 }?.let { CareCountItem(type, it) }
        }

        val active = issues.filter { it.isActive }.mapNotNull { issue ->
            visible[issue.plantId]?.let {
                val days = ChronoUnit.DAYS.between(issue.startedAt.toLocalDate(), today).toInt().coerceAtLeast(0)
                ActiveIssueItem(it, issue, days)
            }
        }.sortedWith(
            compareByDescending<ActiveIssueItem> { it.daysActive }
                .thenBy { it.plant.name.lowercase() }
                .thenBy { it.issue.id }
        )

        return CollectionInsights(late, counts, active)
    }

    @Suppress("LongParameterList")
    private fun lateWateringCount(
        plant: Plant,
        logs: List<CareLog>,
        awayPeriods: List<AwayPeriod>,
        windowStart: LocalDate,
        today: LocalDate,
        seasonalAmplitude: Double,
        hemisphere: Hemisphere
    ): Int {
        if (plant.wateringIntervalDays == null) return 0
        val waterings = logs.filter { it.careType == CareType.WATER }.map { it.loggedAt }.sorted()
        return waterings.zipWithNext().count { (previous, current) ->
            current.toLocalDate() in windowStart..today &&
                isLate(plant, previous, current, awayPeriods, seasonalAmplitude, hemisphere)
        }
    }

    // The interval is the one in effect on the later watering's day: a seasonal plant is judged by that
    // season's interval, a dormant one by its cadence (or never, when suspended).
    @Suppress("ReturnCount", "LongParameterList")
    private fun isLate(
        plant: Plant,
        previous: Long,
        current: Long,
        awayPeriods: List<AwayPeriod>,
        seasonalAmplitude: Double,
        hemisphere: Hemisphere
    ): Boolean {
        if (AwayPeriods.spansAway(awayPeriods, previous, current)) return false
        val currentDay = current.toLocalDate()
        val cycleStart = DormancyWindow.currentCycleStart(
            currentDay,
            plant.dormancyStartMonth,
            plant.dormancyEndMonth
        )
        var due: LocalDate
        if (cycleStart == null) {
            val interval = CareSchedule.effectiveWateringIntervalDaysForDisplay(
                plant, currentDay, seasonalAmplitude, hemisphere
            ) ?: return false
            due = previous.toLocalDate().plusDays(interval.toLong())
        } else {
            val cadence = DormancyWindow.validWateringInterval(plant.dormantWateringIntervalDays) ?: return false
            due = maxOf(previous.toLocalDate().plusDays(cadence.toLong()), cycleStart)
        }
        return currentDay.isAfter(due)
    }
}
