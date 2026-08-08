package com.yapt.planttracker.domain.devmode

import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.Plant

/** One demo plant plus the care-log history that should be inserted alongside it. */
data class DemoPlantSeed(val plant: Plant, val careLogs: List<CareLog>)

/** The full demo dataset produced by [DemoData.generate]. */
data class DemoDataset(val plants: List<DemoPlantSeed>)

/**
 * Pure, deterministic generator for the developer-mode demo dataset (#523). Given a single `now`
 * anchor, [generate] always returns the exact same 8-plant dataset — no randomness, no I/O — so
 * it is reproducible in tests and safe to call from a Room transaction without side effects of
 * its own. Every [Plant]/[CareLog] here carries a placeholder `plantId`; the caller
 * (`DemoDataSeeder`) assigns the real, DB-generated id after insert.
 *
 * The actual anchor-time math, per-log helpers, and per-plant definitions live in
 * [DemoDataTime] and [DemoPlantBuilders] respectively — this object stays a thin entry point so
 * neither helper trips Detekt's `TooManyFunctions` (object threshold 11, #463).
 */
object DemoData {

    const val NAME_PREFIX = "[Demo] "

    fun generate(now: Long): DemoDataset =
        DemoDataset(DemoPlantBuilders.buildAll(DemoDataTime.anchorTimestamp(now)))
}
