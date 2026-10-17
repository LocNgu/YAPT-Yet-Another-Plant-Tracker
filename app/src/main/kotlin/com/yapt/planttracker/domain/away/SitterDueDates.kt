package com.yapt.planttracker.domain.away

import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.domain.today.TodayCareKind
import com.yapt.planttracker.domain.today.TodayCareTask
import com.yapt.planttracker.domain.today.TodayQueueAggregator
import com.yapt.planttracker.domain.today.TodayQueueInput
import com.yapt.planttracker.util.toLocalDate
import com.yapt.planttracker.util.toNoonMillis
import com.yapt.planttracker.util.toStartOfDayMillis
import java.time.LocalDate

/** One plant's next watering date, as [TodayQueueAggregator] reports it. */
data class WateringDue(val plant: Plant, val dueDate: LocalDate)

/**
 * The sitter flow's single door to due dates (product ADR-0065): every date comes from
 * [TodayQueueAggregator] through a widened `horizonEnd` (#910), and a planned watering is simulated as a
 * WATER log. Nothing here knows a due rule of its own.
 */
internal object SitterDueDates {

    /** Active plants with a watering due on or before [horizonEnd] (overdue included), by due date then name. */
    @Suppress("LongParameterList")
    fun wateringsDueBy(
        plants: List<Plant>,
        careLogs: List<CareLog>,
        today: LocalDate,
        horizonEnd: LocalDate,
        seasonalAmplitude: Double,
        hemisphere: Hemisphere
    ): List<WateringDue> = TodayQueueAggregator.build(
        TodayQueueInput(
            plants = plants,
            careLogs = careLogs,
            customReminders = emptyList(),
            issues = emptyList(),
            photos = emptyList(),
            today = today,
            seasonalAmplitude = seasonalAmplitude,
            photoReminderEnabled = false,
            hemisphere = hemisphere,
            horizonEnd = maxOf(horizonEnd, today)
        )
    ).tasks
        .filter { it.isWatering() }
        .map { WateringDue(it.plant, it.dueAt.toLocalDate()) }
        .sortedWith(compareBy({ it.dueDate }, { it.plant.name.lowercase() }, { it.plant.id }))

    /** A WATER log noon of [day], the instant a simulated watering is dated. */
    fun syntheticWaterLog(plantId: Long, day: LocalDate): CareLog =
        CareLog(plantId = plantId, careType = CareType.WATER, loggedAt = day.toNoonMillis())

    fun endOfDayMillis(day: LocalDate): Long = day.plusDays(1).toStartOfDayMillis() - 1

    private fun TodayCareTask.isWatering(): Boolean =
        kind == TodayCareKind.WATER || kind == TodayCareKind.WATER_AND_FERTILIZE
}
