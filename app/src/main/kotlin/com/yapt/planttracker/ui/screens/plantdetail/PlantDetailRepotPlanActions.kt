package com.yapt.planttracker.ui.screens.plantdetail

import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.domain.schedule.RepotPlanSeason
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * Sets (or replaces) the one-off planned repot from the Repot tab's season picker (#809, product
 * ADR-0057). [season] comes from `SeasonalRepotting.upcomingSeasons()`, so [RepotPlanSeason.startAtMillis]
 * is already the target season's first day at start of day; [now] is the plan-made instant that later
 * decides which REPOT logs clear it. A plan needs no repotting interval.
 *
 * Writes only the plan columns through [com.yapt.planttracker.data.repository.PlantRepository.setRepotPlan]
 * — never a full-row `updatePlant()`. It still takes [PlantDetailViewModel.intervalEditMutex]: that lock
 * serializes the full-row writers on this screen (each re-reads the plant fresh inside it), and running
 * the plan write inside it means none of them can read the plant just before this write and put the old
 * plan straight back.
 */
fun PlantDetailViewModel.setRepotPlan(season: RepotPlanSeason, now: Long = System.currentTimeMillis()) {
    viewModelScope.launch {
        intervalEditMutex.withLock {
            plantRepository.setRepotPlan(plantId, season.startAtMillis, now, now)
        }
    }
}

/** Clears the planned repot (#809); the recurring interval date, if any, takes over again. See [setRepotPlan]. */
fun PlantDetailViewModel.clearRepotPlan(now: Long = System.currentTimeMillis()) {
    viewModelScope.launch {
        intervalEditMutex.withLock {
            plantRepository.clearRepotPlan(plantId, now)
        }
    }
}
