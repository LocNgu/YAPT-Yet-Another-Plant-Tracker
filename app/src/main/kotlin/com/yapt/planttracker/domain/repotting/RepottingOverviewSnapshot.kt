package com.yapt.planttracker.domain.repotting

import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate

/** [hasActivePlants] tells the page's "add a plant" empty state apart from "nothing matches this chip". */
data class RepottingOverviewSnapshot(
    val threshold: RepottingOverviewThreshold,
    val overview: RepottingOverview,
    val hasActivePlants: Boolean
)

/**
 * The one reactive pipeline behind both the Repotting overview page and Settings' count subtitle
 * (#525, product ADR-0059), so the two can't drift. [today] is a day-change trigger (technical
 * ADR-0013, #550): callers pass `dayChangeTicker()` by default, and its emitted date is what the
 * overview is evaluated against. The hemisphere is read per emission so a timezone change is picked up.
 */
fun observeRepottingOverview(
    plants: Flow<List<Plant>>,
    lastRepotAtByPlantId: Flow<Map<Long, Long>>,
    threshold: Flow<RepottingOverviewThreshold>,
    today: Flow<LocalDate>
): Flow<RepottingOverviewSnapshot> = combine(plants, lastRepotAtByPlantId, threshold, today) {
        activePlants, lastRepot, selected, day ->
    RepottingOverviewSnapshot(
        threshold = selected,
        overview = RepottingOverviewBuilder.build(
            plants = activePlants,
            lastRepotAtByPlantId = lastRepot,
            threshold = selected,
            today = day,
            hemisphere = SeasonalWatering.currentHemisphere()
        ),
        hasActivePlants = activePlants.any { it.archivedAt == null }
    )
}
