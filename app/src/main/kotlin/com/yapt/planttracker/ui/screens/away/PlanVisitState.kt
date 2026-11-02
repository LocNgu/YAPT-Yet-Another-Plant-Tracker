package com.yapt.planttracker.ui.screens.away

import com.yapt.planttracker.domain.away.AwayPeriods
import com.yapt.planttracker.domain.away.SitterRhythm
import com.yapt.planttracker.domain.away.SitterSelection
import com.yapt.planttracker.domain.away.VisitProjection
import com.yapt.planttracker.domain.away.VisitProjectionInput
import com.yapt.planttracker.domain.away.VisitProjector
import com.yapt.planttracker.domain.away.applySelection
import com.yapt.planttracker.domain.away.seedSelection
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

/**
 * The owner's edits to the suggested sitter list, kept apart from [PlanVisitInput.unticked] (the Confirm
 * checklist). [seededDay] is the saved-visit day [selection] was last replaced from, so landing on that day
 * again after leaving it seeds afresh, while a gap change on it keeps the edits.
 */
data class SitterSelectionState(val selection: SitterSelection = SitterSelection(), val seededDay: LocalDate? = null)

internal data class PlanVisitInput(
    val pickedDay: LocalDate?,
    val gapDays: Int?,
    val unticked: Set<Long>,
    val sitterSelection: SitterSelectionState,
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
    val selectionState = resolveSelection(projection, tripWithVisits.visits, input.sitterSelection)
    val selected = projection.applySelection(selectionState.selection)
    return PlanVisitUiState.Plan(
        today = data.today,
        trip = trip,
        earliestDay = earliest,
        latestDay = trip.lastDay,
        visitDay = visitDay,
        gapDays = gap,
        projection = projection,
        selected = selected,
        selectionState = selectionState,
        summary = selected.summary(),
        sharing = input.sharing
    )
}

/**
 * A day with a saved PLANNED visit starts from that list; any other day keeps the edits as they are. The
 * projector ignores a same-day PLANNED visit, so the saved list never feeds back into [projection].
 */
private fun resolveSelection(
    projection: VisitProjection,
    visits: List<SitterVisit>,
    current: SitterSelectionState
): SitterSelectionState {
    val saved = visits.firstOrNull { it.day == projection.visitDay && it.state == SitterVisitState.PLANNED }
    return when {
        saved == null -> if (current.seededDay == null) current else current.copy(seededDay = null)
        current.seededDay == projection.visitDay -> current
        else -> SitterSelectionState(
            seedSelection(projection, saved.plants.mapTo(HashSet()) { it.plantId }),
            projection.visitDay
        )
    }
}

/**
 * The trip's latest visit before today, when it was never answered. A visit on today itself may not have
 * happened yet, and planning it must not flip the screen into its own checklist, so it waits for tomorrow.
 */
private fun visitToConfirm(visits: List<SitterVisit>, today: LocalDate): SitterVisit? = visits
    .filter { it.day < today }
    .maxWithOrNull(compareBy({ it.day }, { it.id }))
    ?.takeIf { it.state == SitterVisitState.PLANNED }

internal fun confirmState(visit: SitterVisit, plants: List<Plant>, unticked: Set<Long>): PlanVisitUiState.Confirm {
    val byId = plants.filter { it.isVisible }.associateBy { it.id }
    val rows = visit.plants
        .mapNotNull { byId[it.plantId] }
        .sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
        .map { ConfirmRow(it, ticked = it.id !in unticked) }
    return PlanVisitUiState.Confirm(visit, rows)
}
