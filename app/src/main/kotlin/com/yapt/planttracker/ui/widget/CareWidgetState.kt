package com.yapt.planttracker.ui.widget

import com.yapt.planttracker.domain.today.TodayCareKind
import com.yapt.planttracker.domain.today.TodayCareSection
import com.yapt.planttracker.domain.today.TodayQueueSnapshot
import com.yapt.planttracker.domain.today.TodayWateringGroup
import com.yapt.planttracker.domain.today.careTypeSections
import com.yapt.planttracker.ui.screens.plantdetail.PlantDetailTab
import com.yapt.planttracker.ui.screens.today.plantDetailTab

/** What the Care widget shows (#923, product ADR-0088). Built only by [careWidgetState]. */
sealed interface CareWidgetState {
    /** No visible plant at all: a first-use message; a tap opens the app. */
    data object NoPlants : CareWidgetState

    /**
     * [dueNowCount] is the number of plants in Watering · Overdue and Watering · Today, the same
     * distinct-plant counts Care's headers show. [groups] lists Overdue, Today and Tomorrow in Care's
     * order, empty groups left out. [dueNowCount] `== 0` is the "Nothing to water today" state.
     */
    data class Watering(
        val dueNowCount: Int,
        val groups: List<CareWidgetGroup>
    ) : CareWidgetState
}

data class CareWidgetGroup(
    val group: TodayWateringGroup,
    val rows: List<CareWidgetRow>
)

/** One watering task: [kind] is [TodayCareKind.WATER] or [TodayCareKind.WATER_AND_FERTILIZE]. */
data class CareWidgetRow(
    val taskId: String,
    val plantId: Long,
    val plantName: String,
    val kind: TodayCareKind
) {
    /** The Plant Detail tab a tap opens, the same one Care's tile opens. */
    val plantDetailTab: PlantDetailTab get() = kind.plantDetailTab()
}

/**
 * Maps Care's own queue to the widget. The widget shows only the watering entries of
 * [careTypeSections] (the plain watering task and the combined water-and-fertilize task), so its
 * count and rows match Care's watering sections exactly; it derives no dates or buckets of its own.
 * `null` (nothing loaded) is treated like a queue with no plants.
 */
fun careWidgetState(snapshot: TodayQueueSnapshot?): CareWidgetState {
    if (snapshot == null || snapshot.activePlantCount == 0) return CareWidgetState.NoPlants
    val wateringSections = careTypeSections(snapshot.tasks).filter { it.section == TodayCareSection.WATERING }
    val groups = wateringSections.mapNotNull { section ->
        val group = section.wateringGroup ?: return@mapNotNull null
        CareWidgetGroup(
            group = group,
            rows = section.tasks.map { task ->
                CareWidgetRow(
                    taskId = task.id,
                    plantId = task.plant.id,
                    plantName = task.plant.name,
                    kind = task.kind
                )
            }
        )
    }
    val dueNowCount = wateringSections
        .filter { it.wateringGroup != TodayWateringGroup.TOMORROW }
        .sumOf { it.plantCount }
    return CareWidgetState.Watering(dueNowCount = dueNowCount, groups = groups)
}
