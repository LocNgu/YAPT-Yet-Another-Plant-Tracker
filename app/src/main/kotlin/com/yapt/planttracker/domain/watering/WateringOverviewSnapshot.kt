package com.yapt.planttracker.domain.watering

import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** [hasActivePlants] is `false` for both "no plants" and "only archived ones" — the page's empty state. */
data class WateringOverviewSnapshot(
    val overview: WateringOverview,
    val hasActivePlants: Boolean
)

/**
 * The reactive pipeline behind the Watering overview (#901, product ADR-0063). [dayChange] is a
 * day-change trigger only (technical ADR-0035, #550): its value is ignored and [nowProvider] is read
 * once per emission so the intervals and the due dates are evaluated at the same instant. The
 * seasonal amplitude is an input so a change to that setting re-evaluates the page; the hemisphere is
 * read per emission so a timezone change is picked up.
 */
fun observeWateringOverview(
    plants: Flow<List<Plant>>,
    lastWateredAtByPlantId: Flow<Map<Long, Long>>,
    seasonalAmplitude: Flow<Double>,
    dayChange: Flow<*>,
    nowProvider: () -> Long = System::currentTimeMillis
): Flow<WateringOverviewSnapshot> = combine(plants, lastWateredAtByPlantId, seasonalAmplitude, dayChange) {
        activePlants, lastWatered, amplitude, _ ->
    WateringOverviewSnapshot(
        overview = WateringOverviewBuilder.build(
            plants = activePlants,
            lastWateredAtByPlantId = lastWatered,
            seasonalAmplitude = amplitude,
            now = nowProvider(),
            hemisphere = SeasonalWatering.currentHemisphere()
        ),
        hasActivePlants = activePlants.any { it.archivedAt == null }
    )
}
