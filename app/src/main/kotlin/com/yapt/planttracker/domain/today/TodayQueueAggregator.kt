package com.yapt.planttracker.domain.today

import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.CustomReminder
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.domain.model.PlantIssue
import com.yapt.planttracker.domain.model.PlantPhoto
import com.yapt.planttracker.domain.model.WateringScheduleMode
import com.yapt.planttracker.domain.reminder.PhotoReminderPolicy
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.domain.schedule.DormancyWindow
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.util.plusCalendarDays
import com.yapt.planttracker.util.toLocalDate
import com.yapt.planttracker.util.toStartOfDayMillis
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class TodayQueueInput(
    val plants: List<Plant>,
    val careLogs: List<CareLog>,
    val customReminders: List<CustomReminder>,
    val issues: List<PlantIssue>,
    val photos: List<PlantPhoto>,
    val today: LocalDate,
    val seasonalAmplitude: Double,
    val photoReminderEnabled: Boolean,
    val hemisphere: Hemisphere = SeasonalWatering.currentHemisphere()
)

@Suppress("TooManyFunctions")
object TodayQueueAggregator {

    private data class PlantTaskContext(
        val input: TodayQueueInput,
        val plant: Plant,
        val logs: List<CareLog>,
        val status: PlantCareStatus,
        val photos: List<PlantPhoto>,
        val activeIssueByReminderId: Map<Long, PlantIssue>
    )

    fun build(input: TodayQueueInput): TodayQueueSnapshot {
        val logsByPlant = input.careLogs.groupBy { it.plantId }
        val remindersByPlant = input.customReminders.groupBy { it.plantId }
        val photosByPlant = input.photos.groupBy { it.plantId }
        val activeIssueByReminderId = input.issues
            .asSequence()
            .filter { it.isActive && it.linkedReminderId != null }
            .sortedWith(compareBy<PlantIssue> { it.startedAt }.thenBy { it.id })
            .associateBy { it.linkedReminderId!! }
        val now = input.today.toStartOfDayMillis()
        val tasks = input.plants.filter { it.archivedAt == null }.flatMap { plant ->
            val logs = logsByPlant[plant.id].orEmpty()
            val status = CareSchedule.computeStatus(
                plant = plant,
                lastWateredAt = logs.latestTimestamp(CareType.WATER),
                lastFertilizedAt = logs.latestTimestamp(CareType.FERTILIZE),
                lastRepottedAt = logs.latestTimestamp(CareType.REPOT),
                totalLogs = logs.size,
                customReminders = remindersByPlant[plant.id].orEmpty(),
                now = now,
                seasonalAmplitude = input.seasonalAmplitude,
                hemisphere = input.hemisphere
            )
            buildPlantTasks(
                PlantTaskContext(
                    input = input,
                    plant = plant,
                    logs = logs,
                    status = status,
                    photos = photosByPlant[plant.id].orEmpty(),
                    activeIssueByReminderId = activeIssueByReminderId
                )
            )
        }

        return TodayQueueSnapshot(
            activePlantCount = input.plants.count { it.archivedAt == null },
            tasks = tasks.sortedWith(
                compareBy<TodayCareTask> { it.dueAt }
                    .thenBy { it.plant.name.lowercase() }
                    .thenBy { it.id }
            )
        )
    }

    private fun buildPlantTasks(context: PlantTaskContext): List<TodayCareTask> = buildList {
        addAll(scheduledCareTasks(context))
        addAll(customReminderTasks(context))
        photoTask(context)?.let(::add)
    }

    private fun scheduledCareTasks(context: PlantTaskContext): List<TodayCareTask> {
        val watering = wateringTask(context)
        val fertilizing = fertilizingTask(context)
        val tasks = mutableListOf<TodayCareTask>()
        if (context.plant.useLiquidFertilizer) {
            watering?.let { task ->
                val combinesFertilizer = fertilizing != null &&
                    !fertilizing.dueAt.toLocalDate().isAfter(task.dueAt.toLocalDate())
                tasks += if (combinesFertilizer) {
                    task.copy(
                        id = "water_fertilize:${context.plant.id}",
                        kind = TodayCareKind.WATER_AND_FERTILIZE
                    )
                } else {
                    task
                }
            }
        } else {
            watering?.let(tasks::add)
            fertilizing?.let(tasks::add)
        }
        repottingTask(context)?.let(tasks::add)
        return tasks
    }

    private fun repottingTask(context: PlantTaskContext): TodayCareTask? {
        val dueAt = repottingTaskDueAt(context.status, context.input.today) ?: return null
        return taskForDueDate(context.input.today, dueAt) { bucket ->
            TodayCareTask(
                id = "repot:${context.plant.id}",
                plant = context.plant,
                kind = TodayCareKind.REPOT,
                dueAt = dueAt,
                bucket = bucket,
                repotPlanSeason = context.status.repottingPlanSeason
            )
        }
    }

    /**
     * The date a repotting task is bucketed and ordered by. An interval date is used as is. A one-off plan
     * (#809, product ADR-0057) is due for its whole season and overdue only once it has ended, so its
     * season start — [PlantCareStatus.nextRepottingDueAt] — can't be used once the season is under way,
     * or an in-season plan would land in Overdue from its second day on. Instead: its season start while
     * upcoming (so it enters the next-three-days horizon like any date), today while in season (Today),
     * and the season's last day once it has ended (Overdue from the first day after the season), which
     * keeps every task's bucket a plain function of its [TodayCareTask.dueAt].
     */
    private fun repottingTaskDueAt(status: PlantCareStatus, today: LocalDate): Long? {
        val dueAt = status.nextRepottingDueAt
        val seasonEnd = status.repottingPlanSeasonEndAt?.toLocalDate()
        return when {
            dueAt == null || seasonEnd == null -> dueAt
            today.isBefore(dueAt.toLocalDate()) -> dueAt
            today.isBefore(seasonEnd) -> today.toStartOfDayMillis()
            else -> seasonEnd.minusDays(1).toStartOfDayMillis()
        }
    }

    private fun wateringTask(context: PlantTaskContext): TodayCareTask? =
        context.status.nextWateringDueAt
            ?.takeIf {
                isWateringActiveOnDueDate(
                    context.status.wateringScheduleMode,
                    context.plant,
                    it,
                    context.input.today
                )
            }
            ?.let { dueAt ->
                taskForDueDate(context.input.today, dueAt) { bucket ->
                    TodayCareTask(
                        id = "water:${context.plant.id}",
                        plant = context.plant,
                        kind = TodayCareKind.WATER,
                        dueAt = dueAt,
                        bucket = bucket,
                        wateringAction = WateringTaskAction(
                            isOnSchedule = context.status.isWateringOnSchedule,
                            isGapLong = context.status.isWateringGapLong,
                            isDormancySpanning = context.status.isWateringGapDormancySpanning,
                            computedDueAt = context.status.computedNextWateringDueAt,
                            effectiveDueAt = context.status.nextWateringDueAt
                        )
                    )
                }
            }

    private fun fertilizingTask(context: PlantTaskContext): TodayCareTask? =
        context.status.nextFertilizingDueAt
            ?.takeIf { dueAt ->
                !context.status.isDormant && (
                    dueAt.toLocalDate().isBefore(context.input.today) ||
                        !context.plant.isDormantOn(dueAt)
                    )
            }
            ?.let { dueAt ->
                taskForDueDate(context.input.today, dueAt) { bucket ->
                    TodayCareTask(
                        id = "fertilize:${context.plant.id}",
                        plant = context.plant,
                        kind = TodayCareKind.FERTILIZE,
                        dueAt = dueAt,
                        bucket = bucket
                    )
                }
            }

    private fun customReminderTasks(context: PlantTaskContext): List<TodayCareTask> = buildList {
        for (reminderStatus in context.status.customReminderStatuses) {
            val dueAt = reminderStatus.nextDueAt ?: continue
            taskForDueDate(context.input.today, dueAt) { bucket ->
                val issue = context.activeIssueByReminderId[reminderStatus.reminder.id]
                TodayCareTask(
                    id = "custom:${reminderStatus.reminder.id}",
                    plant = context.plant,
                    kind = if (issue == null) {
                        TodayCareKind.CUSTOM_REMINDER
                    } else {
                        TodayCareKind.ISSUE_TREATMENT
                    },
                    dueAt = dueAt,
                    bucket = bucket,
                    customReminder = reminderStatus.reminder,
                    issueName = issue?.name
                )
            }?.let(::add)
        }
    }

    private fun photoTask(context: PlantTaskContext): TodayCareTask? {
        if (!context.input.photoReminderEnabled) return null
        val lastPhotoAt = sequenceOf(
            context.logs.asSequence().filter { it.photoUri != null }.map { it.loggedAt }.maxOrNull(),
            context.photos.maxOfOrNull { it.capturedAt }
        ).filterNotNull().maxOrNull()
        val dueAt = (lastPhotoAt ?: context.plant.createdAt)
            .plusCalendarDays(PhotoReminderPolicy.PHOTO_REMINDER_INTERVAL_DAYS)
        return taskForDueDate(context.input.today, dueAt) { bucket ->
            TodayCareTask(
                id = "photo:${context.plant.id}",
                plant = context.plant,
                kind = TodayCareKind.PHOTO,
                dueAt = dueAt,
                bucket = bucket
            )
        }
    }

    private fun List<CareLog>.latestTimestamp(careType: CareType): Long? =
        asSequence().filter { it.careType == careType }.maxOfOrNull { it.loggedAt }

    private fun Plant.isDormantOn(timestamp: Long): Boolean = DormancyWindow.isDormant(
        timestamp.toLocalDate().monthValue,
        dormancyStartMonth,
        dormancyEndMonth
    )

    private fun isWateringActiveOnDueDate(
        mode: WateringScheduleMode,
        plant: Plant,
        dueAt: Long,
        today: LocalDate
    ): Boolean = when (mode) {
        WateringScheduleMode.NORMAL ->
            dueAt.toLocalDate().isBefore(today) || !plant.isDormantOn(dueAt)
        WateringScheduleMode.DORMANT_CADENCE -> plant.isDormantOn(dueAt)
        WateringScheduleMode.DORMANT_SUSPENDED -> false
    }

    private inline fun taskForDueDate(
        today: LocalDate,
        dueAt: Long,
        build: (TodayTaskBucket) -> TodayCareTask
    ): TodayCareTask? {
        val dueDate = dueAt.toLocalDate()
        val days = ChronoUnit.DAYS.between(today, dueDate)
        val bucket = when {
            days < 0L -> TodayTaskBucket.Overdue
            days == 0L -> TodayTaskBucket.Today
            days in 1L..UPCOMING_HORIZON_DAYS -> TodayTaskBucket.Upcoming(dueDate.toEpochDay())
            else -> return null
        }
        return build(bucket)
    }

    private const val UPCOMING_HORIZON_DAYS = 3L
}
