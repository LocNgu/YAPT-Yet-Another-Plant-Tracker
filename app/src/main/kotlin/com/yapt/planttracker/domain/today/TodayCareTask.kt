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
    val effectiveDueAt: Long?,
    val isAwaySpanning: Boolean = false
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

/**
 * A visible plant with a [com.yapt.planttracker.domain.model.CareType.WATER] log on the aggregator's today;
 * [wateredAt] is its newest such log.
 */
data class TodayWateredPlant(
    val plant: Plant,
    val wateredAt: Long
)

/**
 * [wateredToday] (#939, product ADR-0071) is independent of every horizon and lists each plant once,
 * newest watering first. The default keeps tests that build a snapshot by hand working.
 */
data class TodayQueueSnapshot(
    val activePlantCount: Int,
    val tasks: List<TodayCareTask>,
    val wateredToday: List<TodayWateredPlant> = emptyList()
) {
    val hasTasksDueNow: Boolean get() = tasks.any { it.bucket !is TodayTaskBucket.Upcoming }
}

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
    TOMORROW
}

/**
 * [plantCount] counts distinct plants, not tasks: a plant with two custom reminders is two tiles but
 * one plant. [wateringGroup] is set on the up-to-three [TodayCareSection.WATERING] entries (one per
 * bucket) and is `null` for every other section. [key] is the entry's stable identity, also its
 * per-session collapse key.
 */
data class TodayCareTypeSection(
    val section: TodayCareSection,
    val tasks: List<TodayCareTask>,
    val plantCount: Int,
    val wateringGroup: TodayWateringGroup? = null
) {
    val key: String get() = wateringGroup?.let { "watering:${it.name}" } ?: "section:${section.name}"
}

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
        .flatMap { (section, sectionTasks) ->
            if (section == TodayCareSection.WATERING) {
                wateringSections(sectionTasks)
            } else {
                listOf(TodayCareTypeSection(section, sectionTasks, distinctPlantCount(sectionTasks)))
            }
        }

private fun wateringSections(tasks: List<TodayCareTask>): List<TodayCareTypeSection> =
    tasks.groupBy { it.bucket.wateringGroup() }
        .entries
        .sortedBy { it.key.ordinal }
        .map { (group, groupTasks) ->
            TodayCareTypeSection(
                section = TodayCareSection.WATERING,
                tasks = groupTasks,
                plantCount = distinctPlantCount(groupTasks),
                wateringGroup = group
            )
        }

private fun distinctPlantCount(tasks: List<TodayCareTask>): Int = tasks.distinctBy { it.plant.id }.size

// Care's screen never widens the aggregator's horizon, which on its default path ends tomorrow, so every
// Upcoming bucket here is tomorrow; no date math is repeated.
private fun TodayTaskBucket.wateringGroup(): TodayWateringGroup = when (this) {
    TodayTaskBucket.Overdue -> TodayWateringGroup.OVERDUE
    TodayTaskBucket.Today -> TodayWateringGroup.TODAY
    is TodayTaskBucket.Upcoming -> TodayWateringGroup.TOMORROW
}
