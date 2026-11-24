package com.yapt.planttracker.ui.screens.plantdetail

import com.yapt.planttracker.domain.schedule.DormancyWindow
import com.yapt.planttracker.domain.schedule.WateringStrengthMode

internal sealed interface WateringOptionChange {
    data class Dormancy(val startMonth: Int, val endMonth: Int, val cadenceWeeks: Int?) : WateringOptionChange
    data class Strength(val mode: WateringStrengthMode) : WateringOptionChange
}

internal data class WateringMoreOptionsValues(
    val dormancyStartMonth: Int?,
    val dormancyEndMonth: Int?,
    val dormantWateringIntervalDays: Int?,
    val strengthMode: WateringStrengthMode?
)

internal const val WATERING_SUMMARY_SEPARATOR = " · "

/** Settings not at their default, in display order. A null [WateringMoreOptionsValues.strengthMode] means the strength control is hidden. */
internal fun wateringOptionChanges(values: WateringMoreOptionsValues): List<WateringOptionChange> {
    val changes = mutableListOf<WateringOptionChange>()
    val start = values.dormancyStartMonth
    val end = values.dormancyEndMonth
    if (start != null && end != null && start in MONTH_RANGE && end in MONTH_RANGE) {
        changes += WateringOptionChange.Dormancy(
            start,
            end,
            DormancyWindow.wateringIntervalWeeks(values.dormantWateringIntervalDays)
        )
    }
    val mode = values.strengthMode
    if (mode != null && mode != WateringStrengthMode.APP_DEFAULT) {
        changes += WateringOptionChange.Strength(mode)
    }
    return changes
}

/** Null when everything is at its default, so the header shows the title alone. */
internal fun joinWateringSummary(parts: List<String>): String? =
    parts.takeIf { it.isNotEmpty() }?.joinToString(WATERING_SUMMARY_SEPARATOR)

private val MONTH_RANGE = 1..12
