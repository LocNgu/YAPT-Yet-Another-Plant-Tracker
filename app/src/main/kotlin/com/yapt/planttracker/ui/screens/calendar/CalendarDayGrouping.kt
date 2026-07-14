package com.yapt.planttracker.ui.screens.calendar

import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.util.toLocalDate
import java.time.LocalDate
import java.time.YearMonth

/**
 * A single plant's contribution to a calendar day: which care actions are due, derived from
 * [PlantCareStatus]. [waterDue] / [fertilizeDue] drive which action chips and quick-log buttons
 * the day sheet renders for this plant.
 */
data class PlantDayInfo(
    val status: PlantCareStatus,
    val waterDue: Boolean,
    val fertilizeDue: Boolean
)

/** Everything landing on a single calendar day: the plants due and whether any of them are overdue. */
data class DayEntry(
    val plants: List<PlantDayInfo>,
    val containsOverdue: Boolean
)

private data class Contribution(val date: LocalDate, val info: PlantDayInfo, val overdue: Boolean)

/**
 * Pure transform from a plant's care statuses into a day -> [DayEntry] map for [visibleMonth].
 *
 * Per issue #414 (amended AC): overdue watering and/or fertilizing rolls the plant onto [today],
 * contributing at most once regardless of how many actions are overdue/due. A plant due exactly
 * today (not overdue) also lands on today. Future due dates land on their exact calendar date;
 * a plant with two different future due dates (water vs. fertilize) can appear on both days.
 *
 * Per issue #423: liquid-fertilizer plants (`plant.useLiquidFertilizer`) fertilize together with
 * watering (ADR-0008/ADR-0017), so their `nextFertilizingDueAt` / `isFertilizingOverdue` are
 * ignored entirely — they only contribute via watering, and [PlantDayInfo.fertilizeDue] is
 * always false for them.
 */
fun computePlantsByDay(
    statuses: List<PlantCareStatus>,
    visibleMonth: YearMonth,
    today: LocalDate
): Map<LocalDate, DayEntry> {
    val contributions = mutableListOf<Contribution>()

    for (status in statuses) {
        val isLiquidFertilizer = status.plant.useLiquidFertilizer
        val waterDate = status.nextWateringDueAt?.toLocalDate()
        val fertilizeDate = if (isLiquidFertilizer) null else status.nextFertilizingDueAt?.toLocalDate()
        val waterOverdue = status.isOverdue
        val fertilizeOverdue = if (isLiquidFertilizer) false else status.isFertilizingOverdue

        val landsToday = waterOverdue || fertilizeOverdue ||
            waterDate == today || fertilizeDate == today

        if (landsToday) {
            val waterDue = waterDate != null && (waterOverdue || waterDate == today)
            val fertilizeDue = fertilizeDate != null && (fertilizeOverdue || fertilizeDate == today)
            contributions += Contribution(
                date = today,
                info = PlantDayInfo(status, waterDue, fertilizeDue),
                overdue = waterOverdue || fertilizeOverdue
            )
        }

        val futureDates = mutableSetOf<LocalDate>()
        if (waterDate != null && !waterOverdue && waterDate != today) futureDates += waterDate
        if (fertilizeDate != null && !fertilizeOverdue && fertilizeDate != today) futureDates += fertilizeDate

        for (date in futureDates) {
            contributions += Contribution(
                date = date,
                info = PlantDayInfo(
                    status = status,
                    waterDue = waterDate == date,
                    fertilizeDue = fertilizeDate == date
                ),
                overdue = false
            )
        }
    }

    return contributions
        .filter { YearMonth.from(it.date) == visibleMonth }
        .groupBy { it.date }
        .mapValues { (_, entries) ->
            DayEntry(
                plants = entries.map { it.info },
                containsOverdue = entries.any { it.overdue }
            )
        }
}

/**
 * Whether [info] belongs in the today-sheet's "Overdue" section rather than "Today".
 *
 * Per issue #423: liquid-fertilizer plants fertilize together with watering, so an overdue
 * fertilizing date alone (with watering not overdue) must not sort them into "Overdue".
 */
fun isOverdueEntry(info: PlantDayInfo): Boolean {
    val status = info.status
    return status.isOverdue || (!status.plant.useLiquidFertilizer && status.isFertilizingOverdue)
}
