package com.yapt.planttracker.domain.today

import com.yapt.planttracker.domain.model.CustomReminder
import com.yapt.planttracker.domain.model.Plant

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
    val issueName: String? = null
) {
    val isBulkEligible: Boolean get() = kind != TodayCareKind.PHOTO
}

data class TodayQueueSnapshot(
    val activePlantCount: Int,
    val tasks: List<TodayCareTask>
)

data class TodayTaskSection(
    val bucket: TodayTaskBucket,
    val tasks: List<TodayCareTask>
)

data class TodayPlantGroup(
    val plant: Plant,
    val bucket: TodayTaskBucket,
    val tasks: List<TodayCareTask>
)

data class TodayPlantSection(
    val bucket: TodayTaskBucket,
    val groups: List<TodayPlantGroup>
)

fun taskSections(tasks: List<TodayCareTask>): List<TodayTaskSection> =
    tasks.groupBy { it.bucket }
        .entries
        .sortedWith(compareBy({ bucketRank(it.key) }, { bucketEpochDay(it.key) }))
        .map { TodayTaskSection(it.key, it.value) }

fun plantSections(tasks: List<TodayCareTask>): List<TodayPlantSection> {
    val groups = tasks.groupBy { it.plant.id }.values.map { plantTasks ->
        val bucket = plantTasks.minWith(compareBy({ bucketRank(it.bucket) }, { bucketEpochDay(it.bucket) })).bucket
        TodayPlantGroup(
            plant = plantTasks.first().plant,
            bucket = bucket,
            tasks = plantTasks
        )
    }
    return groups.groupBy { it.bucket }
        .entries
        .sortedWith(compareBy({ bucketRank(it.key) }, { bucketEpochDay(it.key) }))
        .map { (_, bucketGroups) ->
            TodayPlantSection(
                bucket = bucketGroups.first().bucket,
                groups = bucketGroups.sortedWith(
                    compareBy<TodayPlantGroup> { it.tasks.first().dueAt }
                        .thenBy { it.plant.name.lowercase() }
                        .thenBy { it.plant.id }
                )
            )
        }
}

private fun bucketRank(bucket: TodayTaskBucket): Int = when (bucket) {
    TodayTaskBucket.Overdue -> 0
    TodayTaskBucket.Today -> 1
    is TodayTaskBucket.Upcoming -> 2
}

private fun bucketEpochDay(bucket: TodayTaskBucket): Long =
    (bucket as? TodayTaskBucket.Upcoming)?.epochDay ?: Long.MIN_VALUE
