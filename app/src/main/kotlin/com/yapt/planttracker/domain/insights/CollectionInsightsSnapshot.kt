package com.yapt.planttracker.domain.insights

import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantIssue
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** [hasActivePlants] is `false` for both "no plants" and "only archived or hidden ones". */
data class CollectionInsightsSnapshot(
    val insights: CollectionInsights,
    val hasActivePlants: Boolean
)

private data class InsightsInputs(
    val plants: List<Plant>,
    val careLogs: List<CareLog>,
    val issues: List<PlantIssue>,
    val awayPeriods: List<AwayPeriod>
)

/**
 * The reactive pipeline behind the Collection insights page (#1004, product ADR-0089). [dayChange] is a
 * trigger only (technical ADR-0035, #550): the rolling window is re-evaluated on every day change, and
 * [nowProvider] is read once per emission.
 */
@Suppress("LongParameterList")
fun observeCollectionInsights(
    plants: Flow<List<Plant>>,
    careLogs: Flow<List<CareLog>>,
    issues: Flow<List<PlantIssue>>,
    awayPeriods: Flow<List<AwayPeriod>>,
    seasonalAmplitude: Flow<Double>,
    dayChange: Flow<*>,
    nowProvider: () -> Long = System::currentTimeMillis,
    hemisphere: () -> Hemisphere = SeasonalWatering::currentHemisphere
): Flow<CollectionInsightsSnapshot> {
    val inputs = combine(plants, careLogs, issues, awayPeriods, ::InsightsInputs)
    return combine(inputs, seasonalAmplitude, dayChange) { data, amplitude, _ ->
        CollectionInsightsSnapshot(
            insights = CollectionInsightsBuilder.build(
                plants = data.plants,
                careLogs = data.careLogs,
                issues = data.issues,
                awayPeriods = data.awayPeriods,
                seasonalAmplitude = amplitude,
                now = nowProvider(),
                hemisphere = hemisphere()
            ),
            hasActivePlants = data.plants.any { it.isVisible }
        )
    }
}
