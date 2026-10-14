package com.yapt.planttracker.domain.watering

import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringScheduleMode
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.domain.schedule.DormancyWindow
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.util.toLocalDate

/** The Watering overview's groups, in page order (#901, product ADR-0063). */
@Suppress("MagicNumber")
enum class WateringOverviewGroup {
    EVERY_1_3_DAYS,
    EVERY_4_7_DAYS,
    EVERY_8_14_DAYS,
    EVERY_15_PLUS_DAYS,
    RESTING,
    NO_SCHEDULE;

    companion object {
        fun forInterval(days: Int): WateringOverviewGroup = when {
            days <= 3 -> EVERY_1_3_DAYS
            days <= 7 -> EVERY_4_7_DAYS
            days <= 14 -> EVERY_8_14_DAYS
            else -> EVERY_15_PLUS_DAYS
        }
    }
}

/**
 * One row. [intervalDays] is the interval to show: today's effective one for a normal-season plant, the
 * fixed dormant cadence for [WateringScheduleMode.DORMANT_CADENCE], `null` when suspended or unscheduled.
 * [nextDueAt] is `null` exactly when there is nothing to date (suspended or unscheduled).
 */
data class WateringOverviewItem(
    val plant: Plant,
    val intervalDays: Int?,
    val mode: WateringScheduleMode,
    val nextDueAt: Long?
) {
    val isPaused: Boolean get() = mode == WateringScheduleMode.DORMANT_SUSPENDED
}

data class WateringOverviewSection(
    val group: WateringOverviewGroup,
    val items: List<WateringOverviewItem>
)

/** Non-empty sections only, in [WateringOverviewGroup] order. */
data class WateringOverview(val sections: List<WateringOverviewSection>) {
    private fun countOf(predicate: (WateringOverviewGroup) -> Boolean): Int =
        sections.filter { predicate(it.group) }.sumOf { it.items.size }

    val totalCount: Int get() = countOf { true }
    val restingCount: Int get() = countOf { it == WateringOverviewGroup.RESTING }
    val noScheduleCount: Int get() = countOf { it == WateringOverviewGroup.NO_SCHEDULE }
    val scheduledCount: Int get() = totalCount - restingCount - noScheduleCount
}

/**
 * Groups every active plant by how often it needs water (#901, product ADR-0063). Pure: [now] and
 * [hemisphere] come from the caller, the due date is [CareSchedule.computeStatus]'s own
 * `nextWateringDueAt` and the interval is the same function the "Why this date?" sheet uses, so the page
 * adds no due-date logic of its own.
 */
object WateringOverviewBuilder {

    fun build(
        plants: List<Plant>,
        lastWateredAtByPlantId: Map<Long, Long>,
        seasonalAmplitude: Double,
        now: Long,
        hemisphere: Hemisphere
    ): WateringOverview {
        val byGroup = plants
            .filter { it.archivedAt == null }
            .map { item(it, lastWateredAtByPlantId[it.id], seasonalAmplitude, now, hemisphere) }
            .groupBy { groupOf(it) }
        return WateringOverview(
            WateringOverviewGroup.entries.mapNotNull { group ->
                byGroup[group]?.let { WateringOverviewSection(group, it.sortedWith(comparatorFor(group))) }
            }
        )
    }

    private fun item(
        plant: Plant,
        lastWateredAt: Long?,
        seasonalAmplitude: Double,
        now: Long,
        hemisphere: Hemisphere
    ): WateringOverviewItem {
        if (plant.wateringIntervalDays == null) {
            return WateringOverviewItem(plant, null, WateringScheduleMode.NORMAL, null)
        }
        val status = CareSchedule.computeStatus(
            plant = plant,
            lastWateredAt = lastWateredAt,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now,
            seasonalAmplitude = seasonalAmplitude,
            hemisphere = hemisphere
        )
        return when (status.wateringScheduleMode) {
            WateringScheduleMode.NORMAL -> WateringOverviewItem(
                plant = plant,
                intervalDays = CareSchedule.effectiveWateringIntervalDaysForDisplay(
                    plant,
                    now.toLocalDate(),
                    seasonalAmplitude,
                    hemisphere
                ),
                mode = WateringScheduleMode.NORMAL,
                nextDueAt = status.nextWateringDueAt
            )
            WateringScheduleMode.DORMANT_CADENCE -> WateringOverviewItem(
                plant = plant,
                intervalDays = DormancyWindow.validWateringInterval(plant.dormantWateringIntervalDays),
                mode = WateringScheduleMode.DORMANT_CADENCE,
                nextDueAt = status.nextWateringDueAt
            )
            WateringScheduleMode.DORMANT_SUSPENDED -> WateringOverviewItem(
                plant = plant,
                intervalDays = null,
                mode = WateringScheduleMode.DORMANT_SUSPENDED,
                nextDueAt = null
            )
        }
    }

    private fun groupOf(item: WateringOverviewItem): WateringOverviewGroup = when {
        item.plant.wateringIntervalDays == null -> WateringOverviewGroup.NO_SCHEDULE
        item.mode != WateringScheduleMode.NORMAL -> WateringOverviewGroup.RESTING
        else -> WateringOverviewGroup.forInterval(item.intervalDays ?: item.plant.wateringIntervalDays)
    }

    // Resting sorts by cadence with suspended plants last; the no-schedule group has no interval at all.
    private fun comparatorFor(group: WateringOverviewGroup): Comparator<WateringOverviewItem> {
        val byName = compareBy<WateringOverviewItem>({ it.plant.name.lowercase() }, { it.plant.id })
        return when (group) {
            WateringOverviewGroup.NO_SCHEDULE -> byName
            else -> compareBy<WateringOverviewItem> { it.intervalDays ?: Int.MAX_VALUE }.then(byName)
        }
    }
}
