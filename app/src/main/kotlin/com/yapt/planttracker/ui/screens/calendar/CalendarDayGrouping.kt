package com.yapt.planttracker.ui.screens.calendar

import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.domain.model.WateringScheduleMode
import com.yapt.planttracker.domain.schedule.DormancyWindow
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
    val fertilizeDue: Boolean,
    val isDormant: Boolean = false
)

/** Everything landing on a single calendar day: the plants due and whether any of them are overdue. */
data class DayEntry(
    val plants: List<PlantDayInfo>,
    val containsOverdue: Boolean,
    val dormantPlants: List<PlantDayInfo> = emptyList()
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
 * watering (product ADR-0008/product ADR-0017), so their `nextFertilizingDueAt` / `isFertilizingOverdue` are
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
        val waterDateActive = isWaterDateActive(status, waterDate)
        val fertilizeDateActive = fertilizeDate != null && !isDormantOnDate(status, fertilizeDate)

        val landsToday = waterOverdue || fertilizeOverdue ||
            (waterDateActive && waterDate == today) || (fertilizeDateActive && fertilizeDate == today)

        if (landsToday) {
            val waterDue = waterOverdue || (waterDateActive && waterDate == today)
            val fertilizeDue = fertilizeOverdue || (fertilizeDateActive && fertilizeDate == today)
            contributions += Contribution(
                date = today,
                info = PlantDayInfo(status, waterDue, fertilizeDue, status.isDormant),
                overdue = waterOverdue || fertilizeOverdue
            )
        } else if (status.isDormant && status.wateringScheduleMode != WateringScheduleMode.DORMANT_CADENCE) {
            contributions += Contribution(
                date = today,
                info = PlantDayInfo(status, waterDue = false, fertilizeDue = false, isDormant = true),
                overdue = false
            )
        }

        val futureDates = mutableSetOf<LocalDate>()
        waterDate?.takeIf { waterDateActive && !waterOverdue && it != today }?.let { futureDates += it }
        fertilizeDate?.takeIf { fertilizeDateActive && !fertilizeOverdue && it != today }
            ?.let { futureDates += it }

        for (date in futureDates) {
            contributions += Contribution(
                date = date,
                info = PlantDayInfo(
                    status = status,
                    waterDue = waterDateActive && waterDate == date,
                    fertilizeDue = fertilizeDateActive && fertilizeDate == date,
                    isDormant = isDormantOnDate(status, date)
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
                plants = entries.filter { it.info.waterDue || it.info.fertilizeDue }.map { it.info },
                containsOverdue = entries.any { it.overdue },
                dormantPlants = entries.filter { !it.info.waterDue && !it.info.fertilizeDue && it.info.isDormant }
                    .map { it.info }
            )
        }
}

private fun isDormantOnDate(status: PlantCareStatus, date: LocalDate): Boolean =
    DormancyWindow.isDormant(date.monthValue, status.plant.dormancyStartMonth, status.plant.dormancyEndMonth)

private fun isWaterDateActive(status: PlantCareStatus, date: LocalDate?): Boolean {
    if (date == null) return false
    return when (status.wateringScheduleMode) {
        WateringScheduleMode.NORMAL -> !isDormantOnDate(status, date)
        WateringScheduleMode.DORMANT_CADENCE -> isDormantOnDate(status, date)
        WateringScheduleMode.DORMANT_SUSPENDED -> false
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
