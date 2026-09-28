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

data class TodayCareTypeSection(
    val section: TodayCareSection,
    val tasks: List<TodayCareTask>
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
        .map { TodayCareTypeSection(it.key, it.value) }
