package com.yapt.planttracker.domain.today

import com.yapt.planttracker.domain.model.CustomReminder
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.FertilizingSeason

enum class TodayCareKind {
    WATER,
    FERTILIZE,
    WATER_AND_FERTILIZE,
    REPOT,
    CUSTOM_REMINDER,
    ISSUE_TREATMENT,
    PHOTO
}

sealed interface TodayTaskBucket {
    data object Overdue : TodayTaskBucket
    data object Today : TodayTaskBucket
    data class Upcoming(val epochDay: Long) : TodayTaskBucket
}

data class WateringTaskAction(
    val isOnSchedule: Boolean,
    val isGapLong: Boolean,
    val isDormancySpanning: Boolean,
    val computedDueAt: Long?,
    val effectiveDueAt: Long?
)

data class TodayCareTask(
    val id: String,
    val plant: Plant,
    val kind: TodayCareKind,
    val dueAt: Long,
    val bucket: TodayTaskBucket,
    val wateringAction: WateringTaskAction? = null,
    val customReminder: CustomReminder? = null,
    val issueName: String? = null,
    /** The planned season of a [TodayCareKind.REPOT] task that comes from a one-off plan (#809); else `null`. */
    val repotPlanSeason: FertilizingSeason? = null
)

data class TodayQueueSnapshot(
    val activePlantCount: Int,
    val tasks: List<TodayCareTask>
)

enum class TodayCareSection {
    WATERING,
    ISSUE_TREATMENTS,
    FERTILIZING,
    CUSTOM_REMINDERS,
    REPOTTING,
    PHOTOS
}

enum class TodayWateringGroup {
    OVERDUE,
    TODAY,
    NEXT_THREE_DAYS
}

data class TodayWateringSubGroup(
    val group: TodayWateringGroup,
    val tasks: List<TodayCareTask>,
    val plantCount: Int
)

/**
 * [plantCount] counts distinct plants, not tasks: a plant with two custom reminders is two tiles but
 * one plant. [subGroups] is populated for [TodayCareSection.WATERING] only and is empty for every flat
 * section.
 */
data class TodayCareTypeSection(
    val section: TodayCareSection,
    val tasks: List<TodayCareTask>,
    val plantCount: Int,
    val subGroups: List<TodayWateringSubGroup> = emptyList()
)

val TodayCareKind.section: TodayCareSection
    get() = when (this) {
        TodayCareKind.WATER,
        TodayCareKind.WATER_AND_FERTILIZE -> TodayCareSection.WATERING
        TodayCareKind.ISSUE_TREATMENT -> TodayCareSection.ISSUE_TREATMENTS
        TodayCareKind.FERTILIZE -> TodayCareSection.FERTILIZING
        TodayCareKind.CUSTOM_REMINDER -> TodayCareSection.CUSTOM_REMINDERS
        TodayCareKind.REPOT -> TodayCareSection.REPOTTING
        TodayCareKind.PHOTO -> TodayCareSection.PHOTOS
    }

fun careTypeSections(tasks: List<TodayCareTask>): List<TodayCareTypeSection> =
    tasks.groupBy { it.kind.section }
        .entries
        .sortedBy { it.key.ordinal }
        .map { (section, sectionTasks) ->
            TodayCareTypeSection(
                section = section,
                tasks = sectionTasks,
                plantCount = distinctPlantCount(sectionTasks),
                subGroups = if (section == TodayCareSection.WATERING) wateringSubGroups(sectionTasks) else emptyList()
            )
        }

private fun wateringSubGroups(tasks: List<TodayCareTask>): List<TodayWateringSubGroup> =
    tasks.groupBy { it.bucket.wateringGroup() }
        .entries
        .sortedBy { it.key.ordinal }
        .map { (group, groupTasks) -> TodayWateringSubGroup(group, groupTasks, distinctPlantCount(groupTasks)) }

private fun distinctPlantCount(tasks: List<TodayCareTask>): Int = tasks.distinctBy { it.plant.id }.size

// The aggregator's horizon already caps Upcoming at three local days, so every Upcoming bucket is
// inside "Next 3 days"; no date math is repeated here.
private fun TodayTaskBucket.wateringGroup(): TodayWateringGroup = when (this) {
    TodayTaskBucket.Overdue -> TodayWateringGroup.OVERDUE
    TodayTaskBucket.Today -> TodayWateringGroup.TODAY
    is TodayTaskBucket.Upcoming -> TodayWateringGroup.NEXT_THREE_DAYS
}
