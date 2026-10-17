package com.yapt.planttracker.ui.screens.away

import com.yapt.planttracker.domain.away.AwayPeriods
import com.yapt.planttracker.domain.away.SitterRhythm
import com.yapt.planttracker.domain.away.VisitProjectionInput
import com.yapt.planttracker.domain.away.VisitProjector
import com.yapt.planttracker.domain.away.summary
import com.yapt.planttracker.domain.model.AwayPeriodWithVisits
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.SitterVisit
import com.yapt.planttracker.domain.model.SitterVisitState
import com.yapt.planttracker.domain.schedule.Hemisphere
import java.time.LocalDate

internal data class PlanVisitData(
    val trips: List<AwayPeriodWithVisits>,
    val plants: List<Plant>,
    val logs: List<CareLog>,
    val amplitude: Double,
    val today: LocalDate
)

internal data class PlanVisitInput(
    val pickedDay: LocalDate?,
    val gapDays: Int?,
    val unticked: Set<Long>,
    val confirmSkipped: Boolean,
    val sharing: Boolean
)

/** Which screen state the Plan a sitter visit flow is in, derived from the stored trips and the owner's input. */
internal fun planVisitState(data: PlanVisitData, input: PlanVisitInput, hemisphere: Hemisphere): PlanVisitUiState {
    val tripWithVisits = AwayPeriods.currentOrUpcoming(data.trips.map { it.period }, data.today)
        ?.let { current -> data.trips.first { it.period.id == current.id } }
    val earliest = tripWithVisits?.let { maxOf(data.today, it.period.firstDay) }
    val toConfirm = tripWithVisits?.let { visitToConfirm(it.visits, data.today) }
    return when {
        tripWithVisits == null || earliest == null || earliest > tripWithVisits.period.lastDay ->
            PlanVisitUiState.NoTrip
        toConfirm != null && !input.confirmSkipped -> confirmState(toConfirm, data.plants, input.unticked)
        else -> planState(data, input, hemisphere, tripWithVisits, earliest)
    }
}

private fun planState(
    data: PlanVisitData,
    input: PlanVisitInput,
    hemisphere: Hemisphere,
    tripWithVisits: AwayPeriodWithVisits,
    earliest: LocalDate
): PlanVisitUiState.Plan {
    val trip = tripWithVisits.period
    val visitDay = input.pickedDay?.takeIf { it in earliest..trip.lastDay } ?: earliest
    val gap = (input.gapDays ?: trip.visitEveryDays ?: SitterRhythm.DEFAULT_EVERY_DAYS)
        .coerceIn(PlanVisitViewModel.MIN_GAP_DAYS, PlanVisitViewModel.MAX_GAP_DAYS)
    val projection = VisitProjector.project(
        VisitProjectionInput(
            plants = data.plants,
            careLogs = data.logs,
            trip = trip,
            visits = tripWithVisits.visits,
            visitDay = visitDay,
            gapDays = gap,
            today = data.today,
            seasonalAmplitude = data.amplitude,
            hemisphere = hemisphere
        )
    )
    return PlanVisitUiState.Plan(
        today = data.today,
        trip = trip,
        earliestDay = earliest,
        latestDay = trip.lastDay,
        visitDay = visitDay,
        gapDays = gap,
        projection = projection,
        summary = projection.summary(),
        sharing = input.sharing
    )
}

/**
 * The trip's latest visit before today, when it was never answered. A visit on today itself may not have
 * happened yet, and planning it must not flip the screen into its own checklist, so it waits for tomorrow.
 */
private fun visitToConfirm(visits: List<SitterVisit>, today: LocalDate): SitterVisit? = visits
    .filter { it.day < today }
    .maxWithOrNull(compareBy({ it.day }, { it.id }))
    ?.takeIf { it.state == SitterVisitState.PLANNED }

private fun confirmState(visit: SitterVisit, plants: List<Plant>, unticked: Set<Long>): PlanVisitUiState {
    val byId = plants.filter { it.archivedAt == null }.associateBy { it.id }
    val rows = visit.plants
        .mapNotNull { byId[it.plantId] }
        .sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
        .map { ConfirmRow(it, ticked = it.id !in unticked) }
    return PlanVisitUiState.Confirm(visit, rows)
}
