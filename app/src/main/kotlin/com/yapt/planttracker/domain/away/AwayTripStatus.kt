package com.yapt.planttracker.domain.away

import com.yapt.planttracker.domain.model.AwayPeriod
import java.time.LocalDate

/** How many days before a trip starts the Care banner announces it (product ADR-0065). */
const val AWAY_BANNER_LEAD_DAYS = 7L

/** Where the owner stands with respect to their current or next trip, as of one local day. */
sealed interface AwayTripStatus {
    data object None : AwayTripStatus
    data class Upcoming(val trip: AwayPeriod) : AwayTripStatus
    data class Away(val trip: AwayPeriod) : AwayTripStatus
}

fun awayTripStatus(periods: List<AwayPeriod>, today: LocalDate): AwayTripStatus {
    val trip = AwayPeriods.currentOrUpcoming(periods, today) ?: return AwayTripStatus.None
    return if (trip.firstDay > today) AwayTripStatus.Upcoming(trip) else AwayTripStatus.Away(trip)
}

/** The Care banner shows while away, and from [AWAY_BANNER_LEAD_DAYS] days before a trip starts. */
fun AwayTripStatus.showsBanner(today: LocalDate): Boolean = when (this) {
    AwayTripStatus.None -> false
    is AwayTripStatus.Away -> true
    is AwayTripStatus.Upcoming -> trip.firstDay <= today.plusDays(AWAY_BANNER_LEAD_DAYS)
}

/**
 * Before you leave opens for the same [AWAY_BANNER_LEAD_DAYS] days as the banner's upcoming line, up to and
 * including the last day at home (product ADR-0076). Dates only, never when the trip was saved: true exactly when
 * the trip is upcoming and `firstDay - AWAY_BANNER_LEAD_DAYS <= today <= firstDay - 1`.
 */
fun AwayTripStatus.canOpenBeforeYouLeave(today: LocalDate): Boolean = when (this) {
    AwayTripStatus.None, is AwayTripStatus.Away -> false
    is AwayTripStatus.Upcoming ->
        trip.firstDay > today && trip.firstDay <= today.plusDays(AWAY_BANNER_LEAD_DAYS)
}
