package com.yapt.planttracker.domain.devmode

import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.FertilizerType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringFeedback

/**
 * The 8 fixed demo plants (#523) — their static attributes plus how each one's care-log history
 * is built. Split out of [DemoData] purely to keep each object's function count under Detekt's
 * `TooManyFunctions` threshold (#463) — there is no other reason for the split. All day offsets
 * and dataset constants are named below rather than inlined, since Detekt's `MagicNumber` rule is
 * active outside `ui/` (#463).
 */
internal object DemoPlantBuilders {

    private const val RICH_HISTORY_DEPTH_DAYS = 180
    private const val MID_HISTORY_DEPTH_DAYS = 90

    // Plant 1 - Monstera Deliciosa: overdue by 2, rich 180-day history for the chart.
    private const val MONSTERA_NAME = "Monstera Deliciosa"
    private const val MONSTERA_ROOM = "Living Room"
    private const val MONSTERA_WATER_INTERVAL_DAYS = 7
    private const val MONSTERA_FERT_INTERVAL_DAYS = 30
    private const val MONSTERA_LAST_WATER_DAYS_AGO = 9
    private const val MONSTERA_LAST_FERT_DAYS_AGO = 30
    private const val MONSTERA_CREATED_DAYS_AGO = 200
    private const val MONSTERA_PRUNE_DAYS_AGO = 45
    private const val MONSTERA_REPOT_DAYS_AGO = 120
    private const val MONSTERA_MIST_DAYS_AGO = 10
    private const val MONSTERA_NOTE_DAYS_AGO = 5

    // Plant 2 - Snake Plant: due today, fertilizing overdue.
    private const val SNAKE_NAME = "Snake Plant"
    private const val SNAKE_ROOM = "Bedroom"
    private const val SNAKE_WATER_INTERVAL_DAYS = 14
    private const val SNAKE_FERT_INTERVAL_DAYS = 60
    private const val SNAKE_LAST_WATER_DAYS_AGO = 14
    private const val SNAKE_LAST_FERT_DAYS_AGO = 70
    private const val SNAKE_CREATED_DAYS_AGO = 100

    // Plant 3 - Fiddle Leaf Fig: due in 2 days.
    private const val FIDDLE_NAME = "Fiddle Leaf Fig"
    private const val FIDDLE_ROOM = "Living Room"
    private const val FIDDLE_WATER_INTERVAL_DAYS = 5
    private const val FIDDLE_FERT_INTERVAL_DAYS = 30
    private const val FIDDLE_LAST_WATER_DAYS_AGO = 3
    private const val FIDDLE_LAST_FERT_DAYS_AGO = 10
    private const val FIDDLE_CREATED_DAYS_AGO = 100

    // Plant 4 - Pothos: liquid fertilizer, overdue by 1, "Due with next watering" chip.
    private const val POTHOS_NAME = "Pothos"
    private const val POTHOS_ROOM = "Kitchen"
    private const val POTHOS_WATER_INTERVAL_DAYS = 7
    private const val POTHOS_FERT_INTERVAL_DAYS = 14
    private const val POTHOS_LAST_WATER_DAYS_AGO = 8
    private const val POTHOS_LAST_FERT_DAYS_AGO = 20
    private const val POTHOS_CREATED_DAYS_AGO = 100

    // Plant 5 - Peace Lily: due in 3 days.
    private const val PEACE_LILY_NAME = "Peace Lily"
    private const val PEACE_LILY_ROOM = "Bathroom"
    private const val PEACE_LILY_WATER_INTERVAL_DAYS = 4
    private const val PEACE_LILY_FERT_INTERVAL_DAYS = 21
    private const val PEACE_LILY_LAST_WATER_DAYS_AGO = 1
    private const val PEACE_LILY_LAST_FERT_DAYS_AGO = 20
    private const val PEACE_LILY_CREATED_DAYS_AGO = 100

    // Plant 6 - Aloe Vera: unassigned room, overdue by 4.
    private const val ALOE_NAME = "Aloe Vera"
    private const val ALOE_WATER_INTERVAL_DAYS = 21
    private const val ALOE_LAST_WATER_DAYS_AGO = 25
    private const val ALOE_CREATED_DAYS_AGO = 100

    // Plant 7 - Cactus: not scheduled (no watering/fertilizing interval).
    private const val CACTUS_NAME = "Cactus"
    private const val CACTUS_ROOM = "Bedroom"
    private const val CACTUS_LAST_WATER_DAYS_AGO = 60
    private const val CACTUS_CREATED_DAYS_AGO = 100

    // Plant 8 - Calathea: never watered -> due today (#428 path).
    private const val CALATHEA_NAME = "Calathea"
    private const val CALATHEA_ROOM = "Living Room"
    private const val CALATHEA_WATER_INTERVAL_DAYS = 6
    private const val CALATHEA_CREATED_DAYS_AGO = 3

    fun buildAll(anchor: Long): List<DemoPlantSeed> = listOf(
        buildMonstera(anchor),
        buildSnakePlant(anchor),
        buildFiddleLeafFig(anchor),
        buildPothos(anchor),
        buildPeaceLily(anchor),
        buildAloeVera(anchor),
        buildCactus(anchor),
        buildCalathea(anchor)
    )

    private fun buildMonstera(anchor: Long): DemoPlantSeed {
        val plant = Plant(
            name = DemoData.NAME_PREFIX + MONSTERA_NAME,
            room = MONSTERA_ROOM,
            wateringIntervalDays = MONSTERA_WATER_INTERVAL_DAYS,
            fertilizingIntervalDays = MONSTERA_FERT_INTERVAL_DAYS,
            createdAt = DemoDataTime.offsetMillis(anchor, MONSTERA_CREATED_DAYS_AGO),
            updatedAt = DemoDataTime.offsetMillis(anchor, MONSTERA_CREATED_DAYS_AGO)
        )
        val logs = DemoDataTime.wateringHistoryLogs(
            anchor,
            MONSTERA_LAST_WATER_DAYS_AGO,
            MONSTERA_WATER_INTERVAL_DAYS,
            RICH_HISTORY_DEPTH_DAYS
        ) + listOf(
            DemoDataTime.careLog(anchor, MONSTERA_LAST_FERT_DAYS_AGO, CareType.FERTILIZE),
            DemoDataTime.careLog(anchor, MONSTERA_PRUNE_DAYS_AGO, CareType.PRUNE),
            DemoDataTime.careLog(anchor, MONSTERA_REPOT_DAYS_AGO, CareType.REPOT),
            DemoDataTime.careLog(anchor, MONSTERA_MIST_DAYS_AGO, CareType.MIST),
            DemoDataTime.careLog(anchor, MONSTERA_NOTE_DAYS_AGO, CareType.NOTE)
        )
        return DemoPlantSeed(plant, logs)
    }

    private fun buildSnakePlant(anchor: Long): DemoPlantSeed {
        val plant = Plant(
            name = DemoData.NAME_PREFIX + SNAKE_NAME,
            room = SNAKE_ROOM,
            wateringIntervalDays = SNAKE_WATER_INTERVAL_DAYS,
            fertilizingIntervalDays = SNAKE_FERT_INTERVAL_DAYS,
            createdAt = DemoDataTime.offsetMillis(anchor, SNAKE_CREATED_DAYS_AGO),
            updatedAt = DemoDataTime.offsetMillis(anchor, SNAKE_CREATED_DAYS_AGO)
        )
        val logs = DemoDataTime.wateringHistoryLogs(
            anchor,
            SNAKE_LAST_WATER_DAYS_AGO,
            SNAKE_WATER_INTERVAL_DAYS,
            MID_HISTORY_DEPTH_DAYS
        ) + DemoDataTime.careLog(anchor, SNAKE_LAST_FERT_DAYS_AGO, CareType.FERTILIZE)
        return DemoPlantSeed(plant, logs)
    }

    private fun buildFiddleLeafFig(anchor: Long): DemoPlantSeed {
        val plant = Plant(
            name = DemoData.NAME_PREFIX + FIDDLE_NAME,
            room = FIDDLE_ROOM,
            wateringIntervalDays = FIDDLE_WATER_INTERVAL_DAYS,
            fertilizingIntervalDays = FIDDLE_FERT_INTERVAL_DAYS,
            createdAt = DemoDataTime.offsetMillis(anchor, FIDDLE_CREATED_DAYS_AGO),
            updatedAt = DemoDataTime.offsetMillis(anchor, FIDDLE_CREATED_DAYS_AGO)
        )
        val logs = DemoDataTime.wateringHistoryLogs(
            anchor,
            FIDDLE_LAST_WATER_DAYS_AGO,
            FIDDLE_WATER_INTERVAL_DAYS,
            MID_HISTORY_DEPTH_DAYS
        ) + DemoDataTime.careLog(anchor, FIDDLE_LAST_FERT_DAYS_AGO, CareType.FERTILIZE)
        return DemoPlantSeed(plant, logs)
    }

    /**
     * Liquid-fertilizer plant. Its one FERTILIZE log must be paired with a WATER log at the exact
     * same timestamp (product ADR-0008/ADR-0017) — building both from the same [fertTimestamp]
     * makes that invariant structural rather than something that has to be double-checked.
     */
    private fun buildPothos(anchor: Long): DemoPlantSeed {
        val plant = Plant(
            name = DemoData.NAME_PREFIX + POTHOS_NAME,
            room = POTHOS_ROOM,
            wateringIntervalDays = POTHOS_WATER_INTERVAL_DAYS,
            fertilizingIntervalDays = POTHOS_FERT_INTERVAL_DAYS,
            useLiquidFertilizer = true,
            createdAt = DemoDataTime.offsetMillis(anchor, POTHOS_CREATED_DAYS_AGO),
            updatedAt = DemoDataTime.offsetMillis(anchor, POTHOS_CREATED_DAYS_AGO)
        )
        val fertTimestamp = DemoDataTime.offsetMillis(anchor, POTHOS_LAST_FERT_DAYS_AGO)
        val pairedLogs = listOf(
            CareLog(
                plantId = 0L,
                careType = CareType.FERTILIZE,
                loggedAt = fertTimestamp,
                fertilizerType = FertilizerType.LIQUID
            ),
            CareLog(
                plantId = 0L,
                careType = CareType.WATER,
                loggedAt = fertTimestamp,
                wateringFeedback = WateringFeedback.JUST_RIGHT
            )
        )
        val logs = DemoDataTime.wateringHistoryLogs(
            anchor,
            POTHOS_LAST_WATER_DAYS_AGO,
            POTHOS_WATER_INTERVAL_DAYS,
            MID_HISTORY_DEPTH_DAYS
        ) + pairedLogs
        return DemoPlantSeed(plant, logs)
    }

    private fun buildPeaceLily(anchor: Long): DemoPlantSeed {
        val plant = Plant(
            name = DemoData.NAME_PREFIX + PEACE_LILY_NAME,
            room = PEACE_LILY_ROOM,
            wateringIntervalDays = PEACE_LILY_WATER_INTERVAL_DAYS,
            fertilizingIntervalDays = PEACE_LILY_FERT_INTERVAL_DAYS,
            createdAt = DemoDataTime.offsetMillis(anchor, PEACE_LILY_CREATED_DAYS_AGO),
            updatedAt = DemoDataTime.offsetMillis(anchor, PEACE_LILY_CREATED_DAYS_AGO)
        )
        val logs = DemoDataTime.wateringHistoryLogs(
            anchor,
            PEACE_LILY_LAST_WATER_DAYS_AGO,
            PEACE_LILY_WATER_INTERVAL_DAYS,
            MID_HISTORY_DEPTH_DAYS
        ) + DemoDataTime.careLog(anchor, PEACE_LILY_LAST_FERT_DAYS_AGO, CareType.FERTILIZE)
        return DemoPlantSeed(plant, logs)
    }

    private fun buildAloeVera(anchor: Long): DemoPlantSeed {
        val plant = Plant(
            name = DemoData.NAME_PREFIX + ALOE_NAME,
            room = null,
            wateringIntervalDays = ALOE_WATER_INTERVAL_DAYS,
            fertilizingIntervalDays = null,
            createdAt = DemoDataTime.offsetMillis(anchor, ALOE_CREATED_DAYS_AGO),
            updatedAt = DemoDataTime.offsetMillis(anchor, ALOE_CREATED_DAYS_AGO)
        )
        val logs = listOf(
            DemoDataTime.careLog(anchor, ALOE_LAST_WATER_DAYS_AGO, CareType.WATER, WateringFeedback.JUST_RIGHT)
        )
        return DemoPlantSeed(plant, logs)
    }

    private fun buildCactus(anchor: Long): DemoPlantSeed {
        val plant = Plant(
            name = DemoData.NAME_PREFIX + CACTUS_NAME,
            room = CACTUS_ROOM,
            wateringIntervalDays = null,
            fertilizingIntervalDays = null,
            createdAt = DemoDataTime.offsetMillis(anchor, CACTUS_CREATED_DAYS_AGO),
            updatedAt = DemoDataTime.offsetMillis(anchor, CACTUS_CREATED_DAYS_AGO)
        )
        val logs = listOf(
            DemoDataTime.careLog(anchor, CACTUS_LAST_WATER_DAYS_AGO, CareType.WATER, WateringFeedback.JUST_RIGHT)
        )
        return DemoPlantSeed(plant, logs)
    }

    private fun buildCalathea(anchor: Long): DemoPlantSeed {
        val plant = Plant(
            name = DemoData.NAME_PREFIX + CALATHEA_NAME,
            room = CALATHEA_ROOM,
            wateringIntervalDays = CALATHEA_WATER_INTERVAL_DAYS,
            fertilizingIntervalDays = null,
            createdAt = DemoDataTime.offsetMillis(anchor, CALATHEA_CREATED_DAYS_AGO),
            updatedAt = DemoDataTime.offsetMillis(anchor, CALATHEA_CREATED_DAYS_AGO)
        )
        return DemoPlantSeed(plant, emptyList())
    }
}
