package com.yapt.planttracker.domain.usecase

import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.schedule.SeasonalRepotting
import kotlinx.coroutines.flow.first

/**
 * Clears a one-off planned repot when a newly inserted REPOT log supersedes it (#809, product ADR-0057)
 * — the repot-plan counterpart of `clearWateringOverrideIfActive` for `wateringDueDateOverride`. A
 * dedicated object, like [WateringLifecycleReset], because a REPOT log is written from two call sites
 * (`QuickLogUseCase` and `AddCareLogViewModel`) and the side effect must be identical from both.
 *
 * Only *new* logs count: editing or deleting a REPOT log never reaches here, so it can neither clear
 * nor resurrect a plan. The decision is [SeasonalRepotting.repotLogClearsPlan] — the log's local calendar
 * day on or after the plan-made day — so a repot backdated to before the plan was made keeps the plan.
 */
object RepotPlanReset {

    /**
     * Reads the plant fresh (the caller's snapshot may predate the plan) and, if a plan exists and
     * [repotLoggedAt] supersedes it, clears it with the column-specific [PlantRepository.clearRepotPlan]
     * — never a full-row `updatePlant()`, so it can't revert a concurrent write to any other column.
     * Must run after `WateringLifecycleReset.applyRepotReset()`, whose full-row write of the caller's
     * snapshot would otherwise put a stale plan back.
     */
    suspend fun clearIfSuperseded(
        plantId: Long,
        repotLoggedAt: Long,
        plantRepository: PlantRepository,
        now: Long = System.currentTimeMillis()
    ) {
        val plant = plantRepository.getPlantById(plantId).first()
        val supersedes = plant?.repotPlanSeasonStartAt != null &&
            SeasonalRepotting.repotLogClearsPlan(plant.repotPlanMadeAt, repotLoggedAt)
        if (supersedes) plantRepository.clearRepotPlan(plantId, now)
    }
}
