package com.yapt.planttracker.domain.devmode

import com.yapt.planttracker.domain.model.Plant

/**
 * ZZ Plant and Rubber Plant (#571) — split out of [DemoPlantBuilders] purely to keep that object's
 * function count under Detekt's `TooManyFunctions` threshold (#463), the same reason `DemoData`
 * itself was split into [DemoDataTime]/[DemoPlantBuilders] in the first place.
 *
 * Both plants ship with a pre-adapted `wateringConfidence` so a developer can manually exercise
 * #571's lifecycle-reset triggers without first grinding out enough real watering history to build
 * confidence up from scratch:
 * - Log a REPOT on the ZZ Plant via the app to see confidence drop 3 -> 0 and the 4-week freeze
 *   (`wateringFreezeUntil`) start; a WATER logged during the freeze should still appear normally
 *   in care history and the chart, just excluded from base-learning.
 * - Edit the Rubber Plant to a *different* room to see confidence drop 4 -> 0 with **no** freeze
 *   (unlike a repot) — the very next watering resumes normal, unfrozen learning immediately.
 */
internal object DemoAdaptiveResetPlantBuilders {

    private const val MID_HISTORY_DEPTH_DAYS = 90

    private const val ZZ_NAME = "ZZ Plant"
    private const val ZZ_ROOM = "Office"
    private const val ZZ_WATER_INTERVAL_DAYS = 10
    private const val ZZ_FERT_INTERVAL_DAYS = 45
    private const val ZZ_LAST_WATER_DAYS_AGO = 4
    private const val ZZ_CREATED_DAYS_AGO = 100
    private const val ZZ_PRE_ADAPTED_CONFIDENCE = 3

    private const val RUBBER_NAME = "Rubber Plant"
    private const val RUBBER_ROOM = "Guest Room"
    private const val RUBBER_WATER_INTERVAL_DAYS = 9
    private const val RUBBER_FERT_INTERVAL_DAYS = 30
    private const val RUBBER_LAST_WATER_DAYS_AGO = 2
    private const val RUBBER_CREATED_DAYS_AGO = 100
    private const val RUBBER_PRE_ADAPTED_CONFIDENCE = 4

    fun buildAll(anchor: Long): List<DemoPlantSeed> = listOf(
        buildZzPlant(anchor),
        buildRubberPlant(anchor)
    )

    private fun buildZzPlant(anchor: Long): DemoPlantSeed {
        val plant = Plant(
            name = DemoData.NAME_PREFIX + ZZ_NAME,
            room = ZZ_ROOM,
            wateringIntervalDays = ZZ_WATER_INTERVAL_DAYS,
            fertilizingIntervalDays = ZZ_FERT_INTERVAL_DAYS,
            createdAt = DemoDataTime.offsetMillis(anchor, ZZ_CREATED_DAYS_AGO),
            updatedAt = DemoDataTime.offsetMillis(anchor, ZZ_CREATED_DAYS_AGO),
            wateringConfidence = ZZ_PRE_ADAPTED_CONFIDENCE
        )
        val logs = DemoDataTime.wateringHistoryLogs(
            anchor,
            ZZ_LAST_WATER_DAYS_AGO,
            ZZ_WATER_INTERVAL_DAYS,
            MID_HISTORY_DEPTH_DAYS
        )
        return DemoPlantSeed(plant, logs)
    }

    private fun buildRubberPlant(anchor: Long): DemoPlantSeed {
        val plant = Plant(
            name = DemoData.NAME_PREFIX + RUBBER_NAME,
            room = RUBBER_ROOM,
            wateringIntervalDays = RUBBER_WATER_INTERVAL_DAYS,
            fertilizingIntervalDays = RUBBER_FERT_INTERVAL_DAYS,
            createdAt = DemoDataTime.offsetMillis(anchor, RUBBER_CREATED_DAYS_AGO),
            updatedAt = DemoDataTime.offsetMillis(anchor, RUBBER_CREATED_DAYS_AGO),
            wateringConfidence = RUBBER_PRE_ADAPTED_CONFIDENCE
        )
        val logs = DemoDataTime.wateringHistoryLogs(
            anchor,
            RUBBER_LAST_WATER_DAYS_AGO,
            RUBBER_WATER_INTERVAL_DAYS,
            MID_HISTORY_DEPTH_DAYS
        )
        return DemoPlantSeed(plant, logs)
    }
}
