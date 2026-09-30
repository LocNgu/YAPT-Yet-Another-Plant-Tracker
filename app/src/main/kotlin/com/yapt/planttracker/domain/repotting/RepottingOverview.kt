package com.yapt.planttracker.domain.repotting

import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.domain.schedule.RepotPlan
import com.yapt.planttracker.domain.schedule.RepotPlanState
import com.yapt.planttracker.domain.schedule.SeasonalRepotting
import com.yapt.planttracker.util.toLocalDate
import java.time.LocalDate

/** The Repotting overview's chip row (#525, product ADR-0058); [years] is `null` for [NEVER]. */
@Suppress("MagicNumber")
enum class RepottingOverviewThreshold(val years: Int?) {
    NEVER(null),
    ONE_YEAR(1),
    TWO_YEARS(2),
    THREE_YEARS(3);

    companion object {
        val DEFAULT = TWO_YEARS

        fun fromStoredName(name: String?): RepottingOverviewThreshold =
            runCatching { valueOf(name ?: "") }.getOrDefault(DEFAULT)
    }
}

/** One threshold-list row. [anchorAtMillis] is the last REPOT log, or `createdAt` when [neverRepotted]. */
data class RepottingOverviewItem(
    val plant: Plant,
    val anchorAtMillis: Long,
    val neverRepotted: Boolean
)

/** One Planned-group row; [state] is [RepotPlanState.SEASON_ENDED] when the plan is overdue. */
data class RepottingPlannedItem(
    val plant: Plant,
    val plan: RepotPlan,
    val state: RepotPlanState
) {
    val isOverdue: Boolean get() = state == RepotPlanState.SEASON_ENDED
}

/** [count] is the threshold list's size — planned and archived plants are in neither. */
data class RepottingOverview(
    val planned: List<RepottingPlannedItem>,
    val items: List<RepottingOverviewItem>
) {
    val count: Int get() = items.size
}

/**
 * The single source for the Repotting overview page and Settings' count subtitle (#525, product
 * ADR-0058), so the two can't drift. Pure: [today] and [hemisphere] are supplied by the caller.
 */
object RepottingOverviewBuilder {

    fun build(
        plants: List<Plant>,
        lastRepotAtByPlantId: Map<Long, Long>,
        threshold: RepottingOverviewThreshold,
        today: LocalDate,
        hemisphere: Hemisphere
    ): RepottingOverview {
        val active = plants.filter { it.archivedAt == null }
        val (planned, unplanned) = active.partition { it.repotPlanSeasonStartAt != null }
        return RepottingOverview(
            planned = plannedItems(planned, today, hemisphere),
            items = thresholdItems(unplanned, lastRepotAtByPlantId, threshold, today)
        )
    }

    private fun plannedItems(
        planned: List<Plant>,
        today: LocalDate,
        hemisphere: Hemisphere
    ): List<RepottingPlannedItem> = planned
        .mapNotNull { plant ->
            val plan = SeasonalRepotting.resolvePlan(plant.repotPlanSeasonStartAt, hemisphere)
                ?: return@mapNotNull null
            RepottingPlannedItem(plant, plan, plan.stateOn(today))
        }
        .sortedWith(
            compareBy<RepottingPlannedItem> { it.plan.startAtMillis }
                .thenBy { it.plant.name.lowercase() }
                .thenBy { it.plant.id }
        )

    private fun thresholdItems(
        plants: List<Plant>,
        lastRepotAtByPlantId: Map<Long, Long>,
        threshold: RepottingOverviewThreshold,
        today: LocalDate
    ): List<RepottingOverviewItem> = plants
        .mapNotNull { plant ->
            val lastRepotAt = lastRepotAtByPlantId[plant.id]
            val item = RepottingOverviewItem(
                plant = plant,
                anchorAtMillis = lastRepotAt ?: plant.createdAt,
                neverRepotted = lastRepotAt == null
            )
            item.takeIf { matches(it, threshold, today) }
        }
        .sortedWith(
            compareBy<RepottingOverviewItem> { it.anchorAtMillis }
                .thenBy { it.plant.name.lowercase() }
                .thenBy { it.plant.id }
        )

    private fun matches(
        item: RepottingOverviewItem,
        threshold: RepottingOverviewThreshold,
        today: LocalDate
    ): Boolean {
        val years = threshold.years ?: return item.neverRepotted
        return !item.anchorAtMillis.toLocalDate().plusYears(years.toLong()).isAfter(today)
    }
}
