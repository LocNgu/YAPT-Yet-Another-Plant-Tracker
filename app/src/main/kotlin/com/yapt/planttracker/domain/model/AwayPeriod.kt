package com.yapt.planttracker.domain.model

import java.time.LocalDate

/**
 * A trip: the inclusive local calendar days the owner is away (product ADR-0065). [visitEveryDays] is
 * the sitter rhythm chosen in trip setup, or null when none was chosen.
 */
data class AwayPeriod(
    val id: Long = 0,
    val firstDay: LocalDate,
    val lastDay: LocalDate,
    val visitEveryDays: Int? = null,
    val createdAt: Long = System.currentTimeMillis()
)

enum class SitterVisitState { PLANNED, CONFIRMED, DID_NOT_HAPPEN }

/** [watered] is null until the visit is confirmed. */
data class SitterVisitPlant(
    val plantId: Long,
    val watered: Boolean? = null
)

data class SitterVisit(
    val id: Long = 0,
    val awayPeriodId: Long,
    val day: LocalDate,
    val state: SitterVisitState = SitterVisitState.PLANNED,
    val plants: List<SitterVisitPlant> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

data class AwayPeriodWithVisits(
    val period: AwayPeriod,
    val visits: List<SitterVisit>
)
