package com.yapt.planttracker.ui.screens.away

import android.content.Context
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.away.SitterList
import com.yapt.planttracker.domain.away.SitterListPlant
import com.yapt.planttracker.domain.away.SitterListRoom
import java.time.LocalDate

internal val SITTER_TIP_STRINGS = listOf(
    R.string.sitter_tip_soil,
    R.string.sitter_tip_slowly,
    R.string.sitter_tip_saucer,
    R.string.sitter_tip_doubt
)

/**
 * The plain-text fallback of the sitter list (product ADR-0065): the same structure as the PDF, laid out from
 * string resources, with the watering tips after the count and the YAPT branding line last. [today] only decides
 * whether the date carries a year.
 */
internal object SitterListTextRenderer {

    fun render(context: Context, list: SitterList, today: LocalDate = LocalDate.now()): String = buildString {
        appendLine(context.getString(R.string.sitter_visit_title, awayWeekdayDayText(list.visitDay, today)))
        appendLine(context.resources.getQuantityString(R.plurals.sitter_water_count, list.plantCount, list.plantCount))
        appendTips(context)
        appendRooms(context, list.rooms)
        if (list.restingCount > 0) {
            appendLine()
            appendLine(
                context.resources.getQuantityString(
                    R.plurals.sitter_text_resting_title,
                    list.restingCount,
                    list.restingCount
                )
            )
            appendRooms(context, list.restingRooms)
        }
        appendLine()
        appendLine(context.getString(R.string.sitter_brand_line, context.getString(R.string.sitter_brand_url)))
    }.trimEnd()

    private fun StringBuilder.appendTips(context: Context) {
        appendLine()
        appendLine(context.getString(R.string.sitter_tips_heading))
        for (tip in SITTER_TIP_STRINGS) {
            appendLine(context.getString(R.string.sitter_text_bullet, context.getString(tip)))
        }
    }

    private fun StringBuilder.appendRooms(context: Context, rooms: List<SitterListRoom>) {
        for (room in rooms) {
            appendLine()
            appendLine(room.room ?: context.getString(R.string.sitter_room_none))
            for (plant in room.plants) appendLine(context.getString(R.string.sitter_text_bullet, line(context, plant)))
        }
    }

    private fun line(context: Context, plant: SitterListPlant): String {
        val named = plant.species?.let { context.getString(R.string.sitter_text_name_species, plant.name, it) }
            ?: plant.name
        return if (plant.missedLastTime) {
            context.getString(R.string.sitter_text_with_missed, named, context.getString(R.string.sitter_missed_tag))
        } else {
            named
        }
    }
}
