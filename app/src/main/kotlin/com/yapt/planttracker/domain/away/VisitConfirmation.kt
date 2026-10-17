package com.yapt.planttracker.domain.away

import com.yapt.planttracker.domain.model.AwayPeriodWithVisits
import com.yapt.planttracker.domain.model.SitterVisit
import com.yapt.planttracker.domain.model.SitterVisitState
import java.time.LocalDate

/** How many days after a trip's last away day Care keeps offering its unconfirmed sitter visits (product ADR-0065). */
const val VISIT_CONFIRM_WINDOW_DAYS = 14L

/**
 * The unconfirmed visits Care offers on [today], oldest first so each confirmed list feeds the next one's
 * "missed last time" state. Only trips that have ended count, from the first day back until
 * [VISIT_CONFIRM_WINDOW_DAYS] days after the last away day; a visit is dropped from the offer once it is
 * answered either way. A visit with no plants left has nothing to confirm and is not offered.
 */
fun visitsToConfirmAfterTrip(trips: List<AwayPeriodWithVisits>, today: LocalDate): List<SitterVisit> = trips
    .filter { it.period.lastDay < today && today <= it.period.lastDay.plusDays(VISIT_CONFIRM_WINDOW_DAYS) }
    .flatMap { it.visits }
    .filter { it.state == SitterVisitState.PLANNED && it.plants.isNotEmpty() }
    .sortedWith(compareBy({ it.day }, { it.id }))

/** The trip Settings lists visits for: the current or upcoming one, else the most recently ended. */
fun settingsVisitTrip(trips: List<AwayPeriodWithVisits>, today: LocalDate): AwayPeriodWithVisits? {
    val current = AwayPeriods.currentOrUpcoming(trips.map { it.period }, today)
    if (current != null) return trips.firstOrNull { it.period.id == current.id }
    return trips.filter { it.period.lastDay < today }.maxByOrNull { it.period.lastDay }
}

/** Settings can confirm a planned visit once its day has come, during the trip or after it. */
fun SitterVisit.canConfirmOn(today: LocalDate): Boolean =
    state == SitterVisitState.PLANNED && day <= today && plants.isNotEmpty()
